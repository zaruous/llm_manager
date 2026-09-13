/*
 * 작성자 : kyj
 * 작성일 : 2026-06-14
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.service.UpdateChecker.UpdateInfo;
import org.kyj.llmmanager.service.UpdateInstaller;
import org.kyj.llmmanager.service.UpdateOutcome;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 업데이트 정보를 표시하고 zip 다운로드·압축 해제·설치를 수행하는 다이얼로그.
 *
 * <p>[설치] 순서: 다운로드 → 임시 디렉토리에 압축 해제(엔트리 수 검증) → 서비스 프로세스 정리
 * ({@link AppContext#shutdown()}) → 교체 스크립트를 앱과 분리해 기동 → 앱 종료.
 * 서비스 정리를 스크립트 기동보다 앞에 두는 이유: 정리 과정의 {@code taskkill}이 도는 동안
 * 업데이터 프로세스가 존재하면 재사용된 PID로 맞아 죽을 수 있다 (2026-09-13 실패 원인).
 * 결과는 재기동 후 {@link UpdateOutcome}으로 사용자에게 보고된다.
 */
public class UpdateInstallDialog {
    private static final Logger log = LoggerFactory.getLogger(UpdateInstallDialog.class);

    /** 두 다이얼로그에서 동시에 [설치]가 눌리는 것을 막는다 — 같은 zip 경로를 겹쳐 쓰면 해제가 깨진다 */
    private static final AtomicBoolean IN_PROGRESS = new AtomicBoolean(false);

    private final Stage owner;
    private final UpdateInfo update;
    private final String currentVersion;
    private final AppContext ctx;

    /**
     * @param owner          오너 Stage
     * @param update         GitHub Release에서 얻은 업데이트 정보
     * @param currentVersion 현재 앱 버전 (app.properties에서 로드)
     * @param ctx            서비스 프로세스 정리에 사용할 앱 컨텍스트
     */
    public UpdateInstallDialog(Stage owner, UpdateInfo update, String currentVersion, AppContext ctx) {
        this.owner          = owner;
        this.update         = update;
        this.currentVersion = currentVersion;
        this.ctx            = ctx;
    }

    /**
     * 업데이트 다이얼로그를 모달로 표시한다.
     */
    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("업데이트");
        stage.setResizable(false);

        Label curLabel = new Label("현재 버전: v" + currentVersion);
        Label newLabel = new Label("최신 버전: " + update.tagName());
        newLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #33aaff;");

        TextArea notesArea = new TextArea(
                update.releaseNotes().isBlank() ? "(릴리즈 노트 없음)" : update.releaseNotes());
        notesArea.setEditable(false);
        notesArea.setWrapText(true);
        notesArea.setPrefHeight(200);
        VBox.setVgrow(notesArea, Priority.ALWAYS);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        Label progressLabel = new Label();
        progressLabel.setStyle("-fx-text-fill: #888888;");

        Button installBtn = new Button("설치");
        Button laterBtn   = new Button("나중에");
        installBtn.setDefaultButton(true);

        // zip 산출물이 없으면 설치 버튼 비활성
        if (update.zipUrl() == null) {
            installBtn.setDisable(true);
            progressLabel.setText("zip 산출물이 없습니다 — GitHub Releases에서 수동으로 설치해 주세요.");
        }

        laterBtn.setOnAction(e -> stage.close());
        installBtn.setOnAction(e -> {
            if (!IN_PROGRESS.compareAndSet(false, true)) {
                progressLabel.setText("이미 업데이트가 진행 중입니다.");
                return;
            }
            installBtn.setDisable(true);
            laterBtn.setDisable(true);
            progressBar.setVisible(true);
            startInstall(stage, progressBar, progressLabel);
        });

        HBox btnBox = new HBox(8, installBtn, laterBtn);
        btnBox.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10,
                curLabel, newLabel,
                new Separator(),
                new Label("릴리즈 노트:"),
                notesArea,
                new Separator(),
                progressBar, progressLabel,
                btnBox);
        root.setPadding(new Insets(16));

        stage.setScene(SceneFactory.create(root, 540, 430));
        stage.showAndWait();
    }

    /**
     * 백그라운드 스레드에서 다운로드·압축 해제·서비스 정리·스크립트 기동을 순서대로 수행하고 앱을 종료한다.
     * 어느 단계든 실패하면 앱은 계속 실행되고 오류를 라벨에 표시한다.
     */
    private void startInstall(Stage stage, ProgressBar bar, Label label) {
        CompletableFuture.runAsync(() -> {
            try {
                Path tmpDir = UpdateInstaller.workDir();
                Files.createDirectories(tmpDir);

                String fileName = update.zipUrl().substring(update.zipUrl().lastIndexOf('/') + 1);
                Path zipPath = tmpDir.resolve(fileName);

                // 1) 다운로드
                Platform.runLater(() -> label.setText("다운로드 중..."));
                download(zipPath, bar, label);

                // 2) 설치 경로 — 모르면 진행하지 않는다 (%~dp0 폴백은 후행 백슬래시가 따옴표를 깨뜨렸다)
                Path installDir = UpdateInstaller.detectInstallDir();
                if (installDir == null) {
                    throw new IllegalStateException(
                            "설치 경로를 찾을 수 없습니다. LLMManager.exe로 실행된 앱에서만 자동 업데이트를 지원합니다.");
                }

                // 3) 압축 해제 + 엔트리 수 검증 — 앱이 살아있는 동안 끝내 부분 해제본이 설치되는 일을 막는다
                Path extractDir = tmpDir.resolve("extracted");
                Path srcDir = UpdateInstaller.extract(zipPath, extractDir, (done, total) ->
                        Platform.runLater(() -> {
                            bar.setProgress(total > 0 ? (double) done / total : 0);
                            label.setText("압축 해제 중... " + done + " / " + total);
                        }));
                if (!Files.exists(srcDir.resolve("LLMManager.exe"))) {
                    throw new IllegalStateException("zip 안에 LLMManager.exe가 없습니다: " + srcDir);
                }

                // 4) 서비스 프로세스 정리를 먼저 끝낸다 — 여기서 도는 taskkill이 업데이터를 맞추지 못하게
                Platform.runLater(() -> label.setText("서비스 종료 중..."));
                ctx.shutdown();

                // 5) 마커 기록 후 교체 스크립트를 앱과 분리해 기동
                String attemptId = UUID.randomUUID().toString().substring(0, 8);
                String expected  = stripV(update.tagName());
                UpdateOutcome.writePending(tmpDir, attemptId, expected, installDir);

                UpdateInstaller.ScriptParams params = new UpdateInstaller.ScriptParams(
                        installDir, srcDir, zipPath, extractDir,
                        tmpDir.resolve(UpdateOutcome.LOG_FILE),
                        tmpDir.resolve(UpdateOutcome.RESULT_FILE),
                        ProcessHandle.current().pid(), attemptId, expected, true);
                UpdateInstaller.launchDetached(tmpDir.resolve("update.bat"), UpdateInstaller.buildScript(params));
                log.info("update script launched: attempt={} expected=v{} installDir={}",
                        attemptId, expected, installDir);

                Platform.runLater(() -> {
                    bar.setProgress(1.0);
                    label.setText("앱을 종료하고 설치를 진행합니다...");
                    // FX 스레드에서 System.exit() 직접 호출 시 glass.dll 크래시 — 데몬 스레드에서 지연 종료
                    Thread exitThread = new Thread(() -> {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        System.exit(0);
                    });
                    exitThread.setDaemon(true);
                    exitThread.start();
                    stage.close();
                    Platform.exit();
                });

            } catch (Exception ex) {
                IN_PROGRESS.set(false);
                log.warn("update aborted before script launch", ex);
                Platform.runLater(() -> {
                    bar.setProgress(0);
                    label.setText("오류: " + ex.getMessage());
                    label.setStyle("-fx-text-fill: #ff6666;");
                });
            }
        });
    }

    /**
     * zip을 내려받으며 진행률을 표시한다.
     *
     * @param zipPath 저장 경로 (기존 파일은 덮어쓴다)
     * @param bar     진행 바
     * @param label   진행 텍스트
     * @throws Exception HTTP 오류 또는 I/O 실패 시
     */
    private void download(Path zipPath, ProgressBar bar, Label label) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<InputStream> res = client.send(
                HttpRequest.newBuilder(URI.create(update.zipUrl()))
                        .timeout(Duration.ofMinutes(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (res.statusCode() != 200)
            throw new Exception("다운로드 실패: HTTP " + res.statusCode());

        long total = res.headers().firstValueAsLong("content-length").orElse(-1L);
        try (InputStream in = res.body();
             OutputStream out = Files.newOutputStream(zipPath)) {
            byte[] buf = new byte[65536];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    final double progress = (double) downloaded / total;
                    final long dl = downloaded;
                    Platform.runLater(() -> {
                        bar.setProgress(progress);
                        label.setText(String.format("%.1f / %.1f MB",
                                dl / 1_048_576.0, total / 1_048_576.0));
                    });
                }
            }
        }
        // 크기를 알 때는 실제 받은 양과 대조한다 — 잘린 zip이 해제 단계까지 가지 않도록
        if (total > 0 && Files.size(zipPath) != total) {
            throw new Exception("다운로드가 불완전합니다: " + Files.size(zipPath) + " / " + total + " bytes");
        }
    }

    /** "v1.2.1" → "1.2.1". 마커·스크립트에는 접두사 없는 버전을 기록한다. */
    private static String stripV(String tag) {
        String s = tag == null ? "" : tag.strip();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }
}
