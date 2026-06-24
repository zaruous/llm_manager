/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.StartupManager;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.model.plugin.PluginSettingsField;
import org.kyj.llmmanager.model.plugin.PluginSettingsSection;
import org.kyj.llmmanager.service.PluginManager.PluginSettingsTabContribution;
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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

        TextField apiHostField = new TextField(settings.getApiServerHost());
        apiHostField.setPrefWidth(120);
        apiHostField.setMaxWidth(120);
        apiHostField.setPromptText("127.0.0.1");

        PasswordField apiTokenField = new PasswordField();
        apiTokenField.setText(settings.getApiServerToken());
        apiTokenField.setPromptText("서비스 제어 API 토큰");
        apiTokenField.setMaxWidth(Double.MAX_VALUE);

        Button generateTokenBtn = new Button("생성");
        generateTokenBtn.setOnAction(e -> apiTokenField.setText(generateToken()));

        CheckBox allowUnauthenticatedControlCheck =
                new CheckBox("서비스 시작/중지/재시작 API를 토큰 없이 허용");
        allowUnauthenticatedControlCheck.setSelected(
                settings.isApiServerAllowUnauthenticatedControl());

        Hyperlink apiLink = new Hyperlink(apiSwaggerUrl(
                settings.getApiServerHost(), settings.getApiServerPort()));
        apiLink.setStyle("-fx-font-size: 11px;");
        apiLink.setOnAction(e -> openBrowser(apiLink.getText()));
        apiPortField.textProperty().addListener((obs, old, val) ->
            apiLink.setText(apiSwaggerUrl(apiHostField.getText(), parsePort(val, 8185))));
        apiHostField.textProperty().addListener((obs, old, val) ->
            apiLink.setText(apiSwaggerUrl(val, parsePort(apiPortField.getText(), 8185))));

        HBox apiPortRow = new HBox(8,
                new Label("호스트:"), apiHostField,
                new Label("포트:"), apiPortField,
                apiLink);
        apiPortRow.setAlignment(Pos.CENTER_LEFT);

        HBox apiTokenRow = new HBox(8, new Label("제어 토큰:"), apiTokenField, generateTokenBtn);
        apiTokenRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(apiTokenField, Priority.ALWAYS);

        VBox apiSettingsBox = new VBox(8, apiPortRow, apiTokenRow, allowUnauthenticatedControlCheck);
        apiSettingsBox.setDisable(!settings.isApiServerEnabled());
        apiEnabledCheck.selectedProperty().addListener((obs, old, val) ->
            apiSettingsBox.setDisable(!val));

        Label apiNote = new Label("※ 저장 즉시 적용됩니다.");
        apiNote.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");

        pathApiBox.getChildren().addAll(
            installLbl, installGrid,
            new Separator(),
            apiLbl, apiEnabledCheck, apiSettingsBox, apiNote
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
        List<Runnable> pluginSettingSavers = new ArrayList<>();
        addPluginSettingsTabs(stage, tabPane, settings, pluginSettingSavers);

        // ── 하단 버튼 ──────────────────────────────────────────────
        Button saveBtn   = new Button("저장");
        Button cancelBtn = new Button("취소");
        saveBtn.setDefaultButton(true);
        saveBtn.setPrefWidth(80);
        cancelBtn.setPrefWidth(80);

        saveBtn.setOnAction(e -> {
            var apiServer = AppContext.getInstance().getApiServer();
            String previousApiHost = apiServer.getHost();
            int previousApiPort = apiServer.getPort();

            // 런타임 설정
            AppSettings updated = settings;
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
            String  apiHost    = apiHostField.getText().trim().isBlank()
                    ? "127.0.0.1" : apiHostField.getText().trim();
            updated.setApiServerEnabled(apiEnabled);
            updated.setApiServerPort(apiPort);
            updated.setApiServerHost(apiHost);
            updated.setApiServerToken(apiTokenField.getText().trim());
            updated.setApiServerAllowUnauthenticatedControl(
                    allowUnauthenticatedControlCheck.isSelected());
            pluginSettingSavers.forEach(Runnable::run);

            AppContext.getInstance().getAppSettingsRepository().save(updated);

            // API 서버 즉시 적용
            if (apiEnabled && !apiServer.isRunning()) {
                apiServer.start(apiHost, apiPort);
            } else if (apiEnabled && apiServer.isRunning()
                    && (!previousApiHost.equals(apiHost) || previousApiPort != apiPort)) {
                apiServer.start(apiHost, apiPort);
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
        stage.setScene(SceneFactory.create(root, 560));
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

    private void addPluginSettingsTabs(Stage stage, TabPane tabPane, AppSettings settings,
                                       List<Runnable> savers) {
        var pluginManager = AppContext.getInstance().getPluginManager();
        if (pluginManager == null) return;

        for (PluginSettingsTabContribution contribution : pluginManager.getSettingsTabs()) {
            VBox box = new VBox(14);
            box.setPadding(new Insets(14));

            Label pluginLabel = new Label(contribution.pluginName());
            pluginLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            box.getChildren().add(pluginLabel);

            boolean firstSection = true;
            for (PluginSettingsSection section : contribution.tab().getSections()) {
                if (!firstSection) box.getChildren().add(new Separator());
                firstSection = false;
                if (section.getTitle() != null && !section.getTitle().isBlank()) {
                    box.getChildren().add(sectionLabel(section.getTitle()));
                }
                GridPane grid = tabGrid();
                int row = 0;
                for (PluginSettingsField field : section.getFields()) {
                    row = addPluginField(stage, grid, row, contribution.pluginId(), field,
                            settings, savers);
                }
                box.getChildren().add(grid);
            }
            if ("wiki-agent".equals(contribution.pluginId())) {
                box.getChildren().add(new Separator());
                box.getChildren().add(createWikiIndexMetadataSection(stage));
            }

            tabPane.getTabs().add(tab(contribution.tab().getTitle(), box));
        }
    }

    private VBox createWikiIndexMetadataSection(Stage stage) {
        Label title = sectionLabel("수집·임베딩 크기 정책 및 색인 메타데이터");
        Label explanation = new Label("""
                • contentMaxBytes(현재 10 MiB): MCP wiki_ingest의 content 필드에 직접 넣는 \
                UTF-8 원문만 제한합니다. file_path, Cursor 요약, 임베딩 청크 제한은 아닙니다.
                • Cursor 수집: 작은 파일은 5개·512 KiB 배치, 200 KiB 초과 텍스트는 \
                40,000자 목표·60,000자 상한 섹션으로 나눠 요약합니다. 4 MiB 초과 PDF는 \
                5페이지씩 판독합니다.
                • 임베딩: 최종 wiki/*.md만 대상으로 하며 아래 목표/최대/중첩 청크 설정을 \
                적용합니다. 파일별 실제 색인 여부는 현재 전처리 해시와 SQLite 해시를 비교합니다.
                """);
        explanation.setWrapText(true);
        explanation.getStyleClass().add("text-muted");

        Button inspect = new Button("선택 디렉토리 색인 상태 확인");
        inspect.setOnAction(e -> new WikiIndexStatusDialog(stage).showAndWait());
        HBox action = new HBox(inspect);
        action.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, title, explanation, action);
    }

    private int addPluginField(Stage stage, GridPane grid, int row, String pluginId,
                               PluginSettingsField field, AppSettings settings,
                               List<Runnable> savers) {
        String kind = field.getKind() == null ? "string" : field.getKind().trim().toLowerCase();
        String label = field.getLabel() != null && !field.getLabel().isBlank()
                ? field.getLabel() : field.getKey();
        String suffix = field.isRequired() ? " *:" : ":";

        if ("env".equals(kind)) {
            String value = System.getenv(field.getKey());
            Label state = new Label(value == null || value.isBlank() ? "미설정" : "감지됨");
            state.setStyle(value == null || value.isBlank()
                    ? "-fx-text-fill: #ffb86c;" : "-fx-text-fill: #8be9fd;");
            Label envName = new Label(field.getKey());
            envName.setStyle("-fx-text-fill: #aaaaaa;");
            HBox envBox = new HBox(10, envName, state);
            envBox.setAlignment(Pos.CENTER_LEFT);
            grid.add(new Label(label + suffix), 0, row);
            grid.add(envBox, 1, row, 2, 1);
            return row + 1;
        }

        if ("select".equals(kind)) {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll(field.getOptions());
            String current = settings.getPluginSetting(pluginId, field.getKey(), field.getDefaultValue());
            if (current != null && !current.isBlank() && !combo.getItems().contains(current)) {
                combo.getItems().add(current);
            }
            combo.setValue(current);
            combo.setMaxWidth(Double.MAX_VALUE);
            savers.add(() -> settings.setPluginSetting(pluginId, field.getKey(), combo.getValue()));
            grid.add(new Label(label + suffix), 0, row);
            grid.add(combo, 1, row, 2, 1);
            return row + 1;
        }

        TextField textField = field.isSecret() ? new PasswordField() : new TextField();
        textField.setText(settings.getPluginSetting(pluginId, field.getKey(), field.getDefaultValue()));
        textField.setMaxWidth(Double.MAX_VALUE);
        Button browse = null;
        if ("directory".equals(kind)) {
            browse = browseBtn(stage, textField);
        }
        savers.add(() -> {
            if (!field.isSecret()) {
                String value = textField.getText().trim();
                settings.setPluginSetting(pluginId, field.getKey(), value);
                // wiki 기본 워크스페이스 저장 시 골격이 없으면 즉석 초기화 제안
                if ("wiki-agent".equals(pluginId) && "wiki.defaultCwd".equals(field.getKey())
                        && !value.isBlank()) {
                    Path wsPath = Path.of(value).toAbsolutePath().normalize();
                    if (!org.kyj.llmmanager.service.WikiWorkspaceInitializer.isInitialized(wsPath)) {
                        Alert initAlert = new Alert(Alert.AlertType.CONFIRMATION,
                                "raw/, wiki/, graph/ 골격을 지금 생성할까요?\n" + wsPath,
                                ButtonType.YES, ButtonType.NO);
                        initAlert.initOwner(stage);
                        initAlert.setTitle("위키 골격 초기화");
                        initAlert.setHeaderText("선택한 디렉토리에 위키 골격(wiki/index.md)이 없습니다.");
                        initAlert.showAndWait().ifPresent(btn -> {
                            if (btn != ButtonType.YES) return;
                            try {
                                org.kyj.llmmanager.service.WikiWorkspaceInitializer.initialize(wsPath);
                            } catch (Exception ex) {
                                new Alert(Alert.AlertType.ERROR,
                                        "골격 초기화 실패: " + ex.getMessage(),
                                        ButtonType.OK).showAndWait();
                            }
                        });
                    }
                }
            }
        });
        addGridSection(grid, row, label + suffix, textField, browse);
        return row + 1;
    }

    private String apiSwaggerUrl(String host, int port) {
        String browserHost = host == null || host.isBlank() || "0.0.0.0".equals(host.trim())
                ? "localhost" : host.trim();
        return "http://" + browserHost + ":" + port + "/swagger-ui";
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void openBrowser(String url) {
        try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); }
        catch (Exception ignored) {}
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait();
    }
}
