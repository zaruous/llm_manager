/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.StartupManager;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.util.PlatformUtil;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;

/**
 * 앱 환경설정 다이얼로그.
 * TabPane으로 섹션을 분리해 높이를 줄이고 가독성을 높인다.
 * 저장 시 AppSettingsRepository → ~/.llm-manager/settings.json 기록.
 */
public class SettingsDialog {

    private final Stage owner;

    public SettingsDialog(Stage owner) {
        this.owner = owner;
    }

    public void showAndWait() {
        AppSettings settings = AppContext.getInstance().getAppSettingsRepository().get();
        Stage stage = new Stage();

        // ── 탭 공통 레이아웃 도우미 ─────────────────────────────────
        // 각 탭 콘텐츠의 너비는 탭패인이 결정하므로 내부 GridPane에 maxWidth 설정 필요

        // ═══════════════════════════════════════════════════════════
        // TAB 1: 런타임 경로
        // ═══════════════════════════════════════════════════════════
        GridPane rtGrid = tabGrid();

        TextField pythonCmdField  = field(settings.getPythonCommand(), 200);
        TextField pythonHomeField = field(settings.getPythonHome(), 200);
        pythonHomeField.setPromptText("비워 두면 PATH 기본값");
        Button    pythonHomeBtn   = browseBtn(stage, pythonHomeField);

        TextField nodeCmdField  = field(settings.getNodeCommand(), 200);
        TextField nodeHomeField = field(settings.getNodeHome(), 200);
        nodeHomeField.setPromptText("비워 두면 PATH 기본값");
        Button    nodeHomeBtn   = browseBtn(stage, nodeHomeField);

        TextField javaCmdField  = field(settings.getJavaCommand(), 200);
        TextField javaHomeField = field(settings.getJavaHome(), 200);
        javaHomeField.setPromptText("비워 두면 JAVA_HOME 환경변수");
        Button    javaHomeBtn   = browseBtn(stage, javaHomeField);

        int r = 0;
        addGridSection(rtGrid, r++, "Python 명령어:", pythonCmdField, null);
        addGridSection(rtGrid, r++, "Python 홈 경로:", pythonHomeField, pythonHomeBtn);
        addGridSection(rtGrid, r++, "Node.js 명령어:", nodeCmdField, null);
        addGridSection(rtGrid, r++, "Node.js 홈 경로:", nodeHomeField, nodeHomeBtn);
        addGridSection(rtGrid, r++, "Java 명령어:", javaCmdField, null);
        addGridSection(rtGrid, r,   "Java 홈(JAVA_HOME):", javaHomeField, javaHomeBtn);

        Tab runtimeTab = tab("런타임", rtGrid);

        // ═══════════════════════════════════════════════════════════
        // TAB 2: 경로 & API 서버
        // ═══════════════════════════════════════════════════════════
        VBox pathApiBox = new VBox(14);
        pathApiBox.setPadding(new Insets(14));

        // 기본 설치 경로
        Label installLbl = sectionLabel("서비스 기본 설치 경로");
        GridPane installGrid = tabGrid();
        TextField installBaseField = field(settings.getInstallBase(), 200);
        Button    installBaseBtn   = browseBtn(stage, installBaseField);
        addGridSection(installGrid, 0, "기본 설치 경로:", installBaseField, installBaseBtn);

        // API 서버
        Label apiLbl = sectionLabel("내장 API 서버 (Javalin + Swagger UI)");
        GridPane apiGrid = tabGrid();

        CheckBox apiEnabledCheck = new CheckBox("API 서버 활성화");
        apiEnabledCheck.setSelected(settings.isApiServerEnabled());

        TextField apiPortField = new TextField(String.valueOf(settings.getApiServerPort()));
        apiPortField.setPrefWidth(80);
        apiPortField.setMaxWidth(80);

        Hyperlink apiLink = new Hyperlink("http://localhost:" + settings.getApiServerPort() + "/swagger-ui");
        apiLink.setStyle("-fx-font-size: 11px;");
        apiLink.setOnAction(e -> openBrowser(apiLink.getText()));
        apiPortField.textProperty().addListener((obs, old, val) ->
            apiLink.setText("http://localhost:" + val + "/swagger-ui"));

        HBox apiPortRow = new HBox(8, new Label("포트:"), apiPortField, apiLink);
        apiPortRow.setAlignment(Pos.CENTER_LEFT);
        apiPortRow.setDisable(!settings.isApiServerEnabled());
        apiEnabledCheck.selectedProperty().addListener((obs, old, val) ->
            apiPortRow.setDisable(!val));

        Label apiNote = new Label("※ 저장 즉시 적용됩니다.");
        apiNote.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        pathApiBox.getChildren().addAll(
            installLbl, installGrid,
            new Separator(),
            apiLbl, apiEnabledCheck, apiPortRow, apiNote
        );

        Tab pathApiTab = tab("경로 & API 서버", pathApiBox);

        // ═══════════════════════════════════════════════════════════
        // TAB 3: 시작 프로그램
        // ═══════════════════════════════════════════════════════════
        VBox startupBox = new VBox(10);
        startupBox.setPadding(new Insets(14));

        boolean registered = StartupManager.isRegistered();
        CheckBox startupCheck = new CheckBox("시스템 시작 시 자동 실행 (Windows / Linux / macOS)");
        startupCheck.setSelected(registered);

        String detectedPath = StartupManager.detectLaunchPath();
        TextField launchCmdField = new TextField();
        launchCmdField.setPromptText("실행 명령어 (예: javaw -jar C:\\llm-manage.jar)");
        if (detectedPath != null) {
            launchCmdField.setText(
                    (PlatformUtil.isWindows() ? "javaw" : "java")
                    + " -jar \"" + detectedPath + "\"");
        }
        launchCmdField.setMaxWidth(Double.MAX_VALUE);
        launchCmdField.setDisable(!startupCheck.isSelected());
        startupCheck.selectedProperty().addListener((obs, old, val) ->
            launchCmdField.setDisable(!val));

        Label startupNote = new Label("※ JAR 배포 시 실행 명령어를 직접 입력하세요.");
        startupNote.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        startupBox.getChildren().addAll(startupCheck, launchCmdField, startupNote);
        Tab startupTab = tab("시작 프로그램", startupBox);

        // ═══════════════════════════════════════════════════════════
        // TabPane 조합
        // ═══════════════════════════════════════════════════════════
        TabPane tabPane = new TabPane(runtimeTab, pathApiTab, startupTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ── 하단 버튼 ──────────────────────────────────────────────
        Button saveBtn   = new Button("저장");
        Button cancelBtn = new Button("취소");
        saveBtn.setDefaultButton(true);
        saveBtn.setPrefWidth(80);
        cancelBtn.setPrefWidth(80);

        saveBtn.setOnAction(e -> {
            // 런타임 설정
            AppSettings updated = new AppSettings();
            updated.setPythonCommand(pythonCmdField.getText().trim());
            updated.setPythonHome(pythonHomeField.getText().trim());
            updated.setNodeCommand(nodeCmdField.getText().trim());
            updated.setNodeHome(nodeHomeField.getText().trim());
            updated.setJavaCommand(javaCmdField.getText().trim());
            updated.setJavaHome(javaHomeField.getText().trim());
            updated.setInstallBase(installBaseField.getText().trim());

            // API 서버
            boolean apiEnabled = apiEnabledCheck.isSelected();
            int     apiPort    = parsePort(apiPortField.getText(), 8185);
            updated.setApiServerEnabled(apiEnabled);
            updated.setApiServerPort(apiPort);

            AppContext.getInstance().getAppSettingsRepository().save(updated);

            // API 서버 즉시 적용
            var apiServer = AppContext.getInstance().getApiServer();
            if (apiEnabled && !apiServer.isRunning()) {
                apiServer.start(apiPort);
            } else if (apiEnabled && apiServer.isRunning() && apiServer.getPort() != apiPort) {
                apiServer.start(apiPort);
            } else if (!apiEnabled && apiServer.isRunning()) {
                apiServer.stop();
            }

            // 자동 시작
            boolean shouldRegister = startupCheck.isSelected();
            boolean wasRegistered  = StartupManager.isRegistered();
            if (shouldRegister && !wasRegistered) {
                String cmd = launchCmdField.getText().trim();
                if (cmd.isBlank()) { alert("실행 명령어를 입력해 주세요."); return; }
                boolean ok = StartupManager.register(cmd);
                alert(ok ? "자동 시작이 등록되었습니다." : "등록 실패 — 권한 또는 경로를 확인하세요.");
            } else if (!shouldRegister && wasRegistered) {
                boolean ok = StartupManager.unregister();
                alert(ok ? "자동 시작이 해제되었습니다." : "해제 실패.");
            }

            stage.close();
        });
        cancelBtn.setOnAction(e -> stage.close());

        HBox buttons = new HBox(10, saveBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 14, 8, 14));

        VBox root = new VBox(0, tabPane, new Separator(), buttons);

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("환경 설정");
        stage.setScene(SceneFactory.create(root, 680));
        stage.setMinWidth(620);
        stage.setMinHeight(420);
        stage.setResizable(true);
        SceneFactory.autoHeight(stage);
        stage.showAndWait();
    }

    // ── 레이아웃 헬퍼 ────────────────────────────────────────────────

    private Tab tab(String title, javafx.scene.Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: transparent;");
        Tab t = new Tab(title, sp);
        t.setClosable(false);
        return t;
    }

    /** 탭 내부 표준 GridPane (3열: 레이블 | 입력 | 버튼) */
    private GridPane tabGrid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.setPadding(new Insets(14));

        ColumnConstraints lbl = new ColumnConstraints(140);
        ColumnConstraints fld = new ColumnConstraints();
        fld.setHgrow(Priority.ALWAYS);
        fld.setFillWidth(true);
        ColumnConstraints btn = new ColumnConstraints(60);

        g.getColumnConstraints().addAll(lbl, fld, btn);
        return g;
    }

    /** 그리드에 한 행 추가. browseBtn이 null이면 버튼 열 비움. */
    private void addGridSection(GridPane g, int row, String label,
                                TextField field, Button browseBtn) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #aaaaaa;");
        g.add(lbl, 0, row);
        g.add(field, 1, row);
        if (browseBtn != null) g.add(browseBtn, 2, row);
    }

    private Label sectionLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        return lbl;
    }

    private TextField field(String value, double prefWidth) {
        TextField tf = new TextField(value != null ? value : "");
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    private Button browseBtn(Stage stage, TextField target) {
        Button btn = new Button("찾기");
        btn.setPrefWidth(56);
        btn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("디렉토리 선택");
            String cur = target.getText().trim();
            if (!cur.isBlank()) {
                File d = new File(cur);
                if (d.isDirectory()) dc.setInitialDirectory(d);
            }
            File sel = dc.showDialog(stage);
            if (sel != null) target.setText(sel.getAbsolutePath());
        });
        return btn;
    }

    private int parsePort(String text, int def) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private void openBrowser(String url) {
        try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); }
        catch (Exception ignored) {}
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait();
    }
}
