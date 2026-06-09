/*
 * 작성자 : kyj
 * 작성일 : 2026-06-09
 */
package org.kyj.llmmanager.setup;

import org.kyj.llmmanager.util.SceneFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 앱 시작 전 환경 체크리스트를 표시하는 다이얼로그.
 * 각 SetupItem의 설치 여부를 비동기로 확인하고, 미설치 항목에 설치 버튼을 제공한다.
 * required 항목이 모두 통과해야 '계속' 버튼이 활성화된다.
 */
public class SetupCheckDialog {

    private static final Logger log = LoggerFactory.getLogger(SetupCheckDialog.class);

    // ── UI 상태 ────────────────────────────────────────────────
    private final Map<SetupItem, Label>             statusLabels   = new EnumMap<>(SetupItem.class);
    private final Map<SetupItem, ProgressIndicator> spinners       = new EnumMap<>(SetupItem.class);
    private final Map<SetupItem, Button>            installButtons = new EnumMap<>(SetupItem.class);
    private final Map<SetupItem, Boolean>           results        = new EnumMap<>(SetupItem.class);

    private TextArea logArea;
    private Button   continueBtn;
    private Stage    stage;

    private final SetupChecker checker;

    public SetupCheckDialog() {
        this.checker = new SetupChecker();
    }

    /** 테스트 전용 생성자 — 항목별 결과를 미리 지정한 checker를 주입한다. */
    public SetupCheckDialog(SetupChecker checker) {
        this.checker = checker;
    }
    /** 설치 스크립트가 실행 중인지 추적 — 중복 실행 방지 */
    private final AtomicBoolean installing = new AtomicBoolean(false);

    /**
     * 다이얼로그를 표시하고 사용자가 '계속' 또는 '건너뜀'을 클릭할 때까지 블로킹한다.
     */
    public void showAndWait() {
        stage = new Stage();
        stage.initStyle(StageStyle.DECORATED);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("환경 설정 확인");
        stage.setResizable(false);
        // 사용자가 창을 직접 닫으면 건너뜀으로 처리
        stage.setOnCloseRequest(e -> stage.close());

        stage.setScene(SceneFactory.create(buildRoot(), 640));
        SceneFactory.autoHeight(stage);
        stage.showAndWait();
    }

    // ─────────────────────────────────────────────────────────
    // UI 구성
    // ─────────────────────────────────────────────────────────

    private BorderPane buildRoot() {
        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildChecklist());
        root.setBottom(buildFooter());
        return root;
    }

    private VBox buildHeader() {
        Label title = new Label("구동 환경 확인");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label subtitle = new Label("BGE-M3 서버에 필요한 항목을 확인합니다. 미설치 항목은 설치 버튼으로 설치하세요.");
        subtitle.setStyle("-fx-text-fill: -color-fg-muted;");
        subtitle.setWrapText(true);

        VBox header = new VBox(6, title, subtitle, new Separator());
        header.setPadding(new Insets(20, 20, 12, 20));
        return header;
    }

    private VBox buildChecklist() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        grid.setPadding(new Insets(4, 20, 16, 20));

        // 헤더 행
        Label hItem   = bold("항목");
        Label hDesc   = bold("설명");
        Label hStatus = bold("상태");
        grid.addRow(0, hItem, hDesc, hStatus, new Label());
        grid.add(new Separator(), 0, 1, 4, 1);

        int row = 2;
        for (SetupItem item : SetupItem.values()) {
            Label nameLabel = new Label(item.getDisplayName());
            if (item.isRequired()) {
                Label req = new Label("필수");
                req.setStyle("-fx-background-color: -color-accent-emphasis; " +
                             "-fx-text-fill: white; -fx-padding: 1 5 1 5; " +
                             "-fx-background-radius: 3; -fx-font-size: 10px;");
                HBox nameBox = new HBox(6, nameLabel, req);
                nameBox.setAlignment(Pos.CENTER_LEFT);
                grid.add(nameBox, 0, row);
            } else {
                grid.add(nameLabel, 0, row);
            }

            Label descLabel = new Label(item.getDescription());
            descLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
            descLabel.setWrapText(true);
            descLabel.setMaxWidth(260);
            grid.add(descLabel, 1, row);

            // 상태: 스피너 + 텍스트 레이블을 HBox로 겹침
            ProgressIndicator spinner = new ProgressIndicator(-1);
            spinner.setPrefSize(18, 18);
            spinner.setVisible(true);
            spinners.put(item, spinner);

            Label statusLabel = new Label("확인 중...");
            statusLabel.setStyle("-fx-text-fill: -color-fg-muted;");
            statusLabels.put(item, statusLabel);

            HBox statusBox = new HBox(6, spinner, statusLabel);
            statusBox.setAlignment(Pos.CENTER_LEFT);
            statusBox.setMinWidth(120);
            grid.add(statusBox, 2, row);

            // 설치 버튼
            Button installBtn = new Button("설치");
            installBtn.setVisible(false);
            installBtn.setOnAction(e -> startInstall(item));
            installButtons.put(item, installBtn);
            grid.add(installBtn, 3, row);

            row++;
        }

        // 컬럼 너비 설정
        grid.getColumnConstraints().addAll(
                col(140), col(280), col(130), col(70)
        );

        // 로그 영역
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(7);
        logArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");

        Label logTitle = bold("설치 로그");
        logTitle.setPadding(new Insets(8, 0, 4, 0));

        ScrollPane scroll = new ScrollPane(logArea);
        scroll.setFitToWidth(true);
        scroll.setPadding(new Insets(0, 20, 0, 20));

        VBox center = new VBox(grid, new Separator(), logTitle, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox.setMargin(logTitle, new Insets(0, 0, 0, 20));

        // 체크리스트 렌더 완료 후 비동기로 전체 검사 시작
        Platform.runLater(this::runAllChecks);
        return center;
    }

    private VBox buildFooter() {
        continueBtn = new Button("계속 →");
        continueBtn.setDefaultButton(true);
        continueBtn.setDisable(true);
        continueBtn.setStyle("-fx-font-weight: bold;");
        continueBtn.setOnAction(e -> stage.close());

        Button skipBtn = new Button("건너뜀");
        skipBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(10, spacer, skipBtn, continueBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 20, 16, 20));

        VBox box = new VBox(new Separator(), footer);
        return box;
    }

    // ─────────────────────────────────────────────────────────
    // 검사 로직
    // ─────────────────────────────────────────────────────────

    /**
     * 모든 항목을 순차적으로 비동기 검사한다.
     * 검사 중 UI 블로킹을 막기 위해 별도 스레드에서 실행한다.
     */
    private void runAllChecks() {
        new Thread(() -> {
            for (SetupItem item : SetupItem.values()) {
                setStatus(item, CheckStatus.CHECKING);
                boolean ok = checker.check(item);
                results.put(item, ok);
                setStatus(item, ok ? CheckStatus.OK : CheckStatus.FAIL);
            }
            Platform.runLater(this::refreshContinueButton);
        }, "setup-checker").start();
    }

    /**
     * 단일 항목을 재검사한다. 설치 완료 후 호출한다.
     *
     * @param item 재검사할 항목
     */
    private void recheckItem(SetupItem item) {
        new Thread(() -> {
            setStatus(item, CheckStatus.CHECKING);
            boolean ok = checker.check(item);
            results.put(item, ok);
            setStatus(item, ok ? CheckStatus.OK : CheckStatus.FAIL);
            Platform.runLater(this::refreshContinueButton);
        }, "setup-recheck-" + item.name()).start();
    }

    // ─────────────────────────────────────────────────────────
    // 설치 실행
    // ─────────────────────────────────────────────────────────

    /**
     * 항목에 연결된 PowerShell 설치 스크립트를 실행하고 로그를 스트리밍한다.
     *
     * @param item 설치할 항목
     */
    private void startInstall(SetupItem item) {
        if (installing.getAndSet(true)) {
            appendLog("다른 설치가 진행 중입니다. 완료 후 다시 시도하세요.");
            return;
        }

        Path script = resolveScript(item.getScriptPath());
        if (script == null) {
            appendLog("[오류] 설치 스크립트를 찾을 수 없습니다: " + item.getScriptPath());
            installing.set(false);
            return;
        }

        setStatus(item, CheckStatus.INSTALLING);
        installButtons.get(item).setDisable(true);
        appendLog("\n─── " + item.getDisplayName() + " 설치 시작 ───");
        appendLog("스크립트: " + script.toAbsolutePath());

        new Thread(() -> {
            try {
                List<String> cmd = new java.util.ArrayList<>(List.of(
                        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", script.toAbsolutePath().toString()));
                if (item == SetupItem.NVIDIA_DRIVER || item == SetupItem.CUDA) {
                    cmd.add("-NonInteractive");
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                // stdout 을 실시간으로 로그 영역에 스트리밍
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String l = line;
                        Platform.runLater(() -> appendLog(l));
                    }
                }

                int exit = proc.waitFor();
                Platform.runLater(() -> {
                    if (exit == 0) {
                        appendLog("─── 설치 완료 (종료 코드: 0) ───");
                    } else {
                        appendLog("─── 설치 종료 (코드: " + exit + ") — 로그를 확인하세요 ───");
                    }
                });
            } catch (Exception e) {
                log.error("설치 스크립트 실행 실패: {}", item, e);
                Platform.runLater(() -> appendLog("[오류] " + e.getMessage()));
            } finally {
                installing.set(false);
                Platform.runLater(() -> installButtons.get(item).setDisable(false));
                recheckItem(item);
            }
        }, "setup-install-" + item.name()).start();
    }

    // ─────────────────────────────────────────────────────────
    // UI 상태 업데이트
    // ─────────────────────────────────────────────────────────

    /**
     * 지정 항목의 상태 아이콘·텍스트·버튼 가시성을 갱신한다.
     *
     * @param item   갱신할 항목
     * @param status 새 상태
     */
    private void setStatus(SetupItem item, CheckStatus status) {
        Platform.runLater(() -> {
            Label lbl     = statusLabels.get(item);
            ProgressIndicator sp = spinners.get(item);
            Button btn    = installButtons.get(item);

            switch (status) {
                case CHECKING -> {
                    sp.setVisible(true);
                    lbl.setText("확인 중...");
                    lbl.setStyle("-fx-text-fill: -color-fg-muted;");
                    btn.setVisible(false);
                }
                case OK -> {
                    sp.setVisible(false);
                    lbl.setText("✓  설치됨");
                    lbl.setStyle("-fx-text-fill: -color-success-fg;");
                    btn.setVisible(false);
                }
                case FAIL -> {
                    sp.setVisible(false);
                    lbl.setText("✗  미설치");
                    lbl.setStyle("-fx-text-fill: -color-danger-fg;");
                    btn.setVisible(true);
                }
                case INSTALLING -> {
                    sp.setVisible(true);
                    lbl.setText("설치 중...");
                    lbl.setStyle("-fx-text-fill: -color-accent-fg;");
                    btn.setVisible(false);
                }
            }
        });
    }

    /**
     * required 항목이 모두 통과했으면 '계속' 버튼을 활성화한다.
     */
    private void refreshContinueButton() {
        boolean allRequiredOk = true;
        for (SetupItem item : SetupItem.values()) {
            if (item.isRequired() && !Boolean.TRUE.equals(results.get(item))) {
                allRequiredOk = false;
                break;
            }
        }
        continueBtn.setDisable(!allRequiredOk);
    }

    private void appendLog(String line) {
        if (!logArea.getText().isEmpty()) logArea.appendText("\n");
        logArea.appendText(line);
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    // ─────────────────────────────────────────────────────────
    // 유틸
    // ─────────────────────────────────────────────────────────

    /**
     * 스크립트 경로를 해석한다.
     * 1) 작업 디렉토리 기준 상대 경로 (개발 모드)
     * 2) JAR 위치 기준 경로 (프로덕션 패키징)
     *
     * @param relativePath 앱 루트 기준 상대 경로
     * @return 실제 존재하는 Path, 없으면 null
     */
    private Path resolveScript(String relativePath) {
        Path candidate = Path.of(relativePath);
        if (Files.exists(candidate)) return candidate;

        try {
            Path jar = Path.of(
                    SetupCheckDialog.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            // JAR이 app/lib 또는 app 안에 있는 경우 두 단계 위까지 탐색
            for (Path dir = jar.getParent(); dir != null; dir = dir.getParent()) {
                Path p = dir.resolve(relativePath);
                if (Files.exists(p)) return p;
                if (dir.getParent() == null || dir.equals(dir.getParent())) break;
            }
        } catch (Exception e) {
            log.warn("스크립트 경로 해석 실패: {}", e.getMessage());
        }
        return null;
    }

    private Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    private javafx.scene.layout.ColumnConstraints col(double width) {
        javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints(width);
        return c;
    }

    // ─────────────────────────────────────────────────────────
    // 내부 상태 enum
    // ─────────────────────────────────────────────────────────

    private enum CheckStatus {
        CHECKING, OK, FAIL, INSTALLING
    }
}
