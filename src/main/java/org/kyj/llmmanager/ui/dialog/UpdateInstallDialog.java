/*
 * 작성자 : kyj
 * 작성일 : 2026-06-14
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.service.UpdateChecker.UpdateInfo;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * 업데이트 정보를 표시하고 zip 다운로드·설치를 수행하는 다이얼로그.
 * [설치] 클릭 시 zip을 내려받고 update.bat를 실행한 뒤 앱을 종료한다.
 */
public class UpdateInstallDialog {

    private final Stage owner;
    private final UpdateInfo update;
    private final String currentVersion;

    /**
     * @param owner          오너 Stage
     * @param update         GitHub Release에서 얻은 업데이트 정보
     * @param currentVersion 현재 앱 버전 (app.properties에서 로드)
     */
    public UpdateInstallDialog(Stage owner, UpdateInfo update, String currentVersion) {
        this.owner          = owner;
        this.update         = update;
        this.currentVersion = currentVersion;
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
            installBtn.setDisable(true);
            laterBtn.setDisable(true);
            progressBar.setVisible(true);
            startDownload(stage, progressBar, progressLabel);
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
     * 백그라운드 스레드에서 zip을 다운로드하고 update.bat를 생성·실행한다.
     */
    private void startDownload(Stage stage, ProgressBar bar, Label label) {
        CompletableFuture.runAsync(() -> {
            try {
                Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "llm-manager-update");
                Files.createDirectories(tmpDir);

                String fileName = update.zipUrl().substring(update.zipUrl().lastIndexOf('/') + 1);
                Path zipPath = tmpDir.resolve(fileName);

                Platform.runLater(() -> label.setText("다운로드 중..."));

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

                Platform.runLater(() -> {
                    bar.setProgress(1.0);
                    label.setText("설치 준비 중...");
                });

                // 현재 실행 파일 위치에서 설치 디렉토리 산출 — 배포판: LLMManager.exe의 부모 디렉토리
                Path installDir = ProcessHandle.current().info().command()
                        .map(cmd -> Path.of(cmd).getParent())
                        .orElse(null);

                Path batPath = tmpDir.resolve("update.bat");
                Files.writeString(batPath, buildUpdateBat(zipPath, installDir), StandardCharsets.UTF_8);

                // 앱과 분리해 실행 (start /B) — 앱 종료 후에도 bat가 계속 동작
                new ProcessBuilder("cmd.exe", "/c", "start", "/B", "", batPath.toString()).start();

                Platform.runLater(() -> {
                    stage.close();
                    Platform.exit();
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    bar.setProgress(0);
                    label.setText("오류: " + ex.getMessage());
                    label.setStyle("-fx-text-fill: #ff6666;");
                });
            }
        });
    }

    /**
     * zip 압축 해제 후 설치 디렉토리에 덮어쓰고 새 버전을 재실행하는 배치 파일 내용을 반환한다.
     *
     * @param zipPath    다운로드된 zip 경로
     * @param installDir 현재 설치 디렉토리 (null이면 %~dp0 폴백)
     */
    private String buildUpdateBat(Path zipPath, Path installDir) {
        String installDirStr = installDir != null ? installDir.toString() : "%~dp0";
        return "@echo off\r\n"
             + "chcp 65001 > nul\r\n"
             + "timeout /t 3 /nobreak > nul\r\n"
             + "\r\n"
             + "set INSTALL_DIR=" + installDirStr + "\r\n"
             + "set ZIP_PATH=" + zipPath + "\r\n"
             + "set EXTRACT_DIR=%TEMP%\\llm-manager-update\\extracted\r\n"
             + "\r\n"
             + "powershell -Command \"Expand-Archive -Path '%ZIP_PATH%' -DestinationPath '%EXTRACT_DIR%' -Force\"\r\n"
             + "xcopy /E /Y \"%EXTRACT_DIR%\\LLMManager\\*\" \"%INSTALL_DIR%\\\"\r\n"
             + "\r\n"
             + "start \"\" \"%INSTALL_DIR%\\LLMManager.exe\"\r\n"
             + "\r\n"
             + "rmdir /S /Q \"%EXTRACT_DIR%\"\r\n";
    }
}
