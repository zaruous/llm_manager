/*
 * 작성자 : kyj
 * 작성일 : 2026-06-10
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.LlmTool;
import org.kyj.llmmanager.model.SkillFile;
import org.kyj.llmmanager.model.SkillPack;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.service.PluginCommandExecutor.PluginCommandRequest;
import org.kyj.llmmanager.service.PluginDependencyInstaller;
import org.kyj.llmmanager.service.PluginManager.PluginCommandContribution;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Cursor Agent를 CLI 세션 UX로 실행하는 전용 다이얼로그.
 *
 * 1단계에서 작업 디렉토리·모델을 선택하고, 2단계에서 터미널형 대화 화면으로 전환해
 * 프롬프트를 반복 입력·실행한다. 실행은 PluginCommandExecutor의 sidecar 스트리밍을 사용한다.
 */
public class CursorAgentDialog {

    private final Stage owner;
    private final PluginCommandContribution contribution;

    private Stage stage;
    private BorderPane root;

    // ── 1단계: 시작 화면 ─────────────────────────────────────
    private TextField cwdField;
    private ComboBox<String> modelCombo;
    private Button startBtn;
    private Label cwdStateLabel;

    // ── 시작 화면 의존성(@cursor/sdk) 상태 ───────────────────
    private Label depsStateLabel;
    private Button depsInstallBtn;
    private TextArea depsLogArea;
    /** SDK 의존성 충족 여부. 시작 버튼은 디렉토리 유효 + 이 값이 모두 참일 때 활성화. */
    private boolean depsReady = false;

    // ── 2단계: 세션 화면 ─────────────────────────────────────
    private TextArea terminalArea;
    private TextArea promptArea;
    private Button sendBtn;
    /** 실행 중인 에이전트를 강제 종료하는 중지 버튼. 실행 중일 때만 활성화된다. */
    private Button stopBtn;
    private ProgressIndicator runningIndicator;
    /** 헤더의 cwd·모델 표시 라벨. /model 명령으로 변경 시 갱신. */
    private Label sessionInfoLabel;

    // ── 프롬프트 자동완성 ────────────────────────────────────
    /**
     * '/' 명령·'@' 경로 자동완성 목록. 별도 Popup 윈도우는 키보드 포커스를 빼앗아
     * Enter/Tab이 입력창에 전달되지 않으므로, 입력창 위 인라인 패널로 표시한다.
     */
    private ListView<Suggestion> suggestList;
    /** 자동완성 대상 토큰의 시작 인덱스('/'·'@' 문자 위치). 적용 시 이 위치부터 치환. */
    private int suggestTokenStart = -1;

    /** 실행 중 중복 전송 방지. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 세션 시작 시 확정된 작업 디렉토리·모델. */
    private String sessionCwd;
    private String sessionModel;

    /** Cursor 대화 모드 — "agent"(기본) 또는 "plan". */
    private String sessionAgentMode = "agent";

    /** 세션에 로드된 커스텀 agent 정의 (이름 → {description, prompt}). 실행마다 sidecar로 전달. */
    private final Map<String, Map<String, String>> loadedAgents = new LinkedHashMap<>();

    /** sidecar에 전달할 설정 레이어 — 프로젝트(.cursor/rules)·사용자 규칙 로드. */
    private static final List<String> SETTING_SOURCES = List.of("project", "user");

    /** 자동완성 항목. insert가 토큰('/'·'@' 포함)을 통째로 대체한다. */
    private record Suggestion(String label, String description, String insert, boolean directory) {}

    /** 세션 내에서 로컬로 처리하는 슬래시 명령 목록. */
    private static final List<Suggestion> SLASH_COMMANDS = List.of(
            new Suggestion("/help",  "사용 가능한 명령 보기",                       "/help",   false),
            new Suggestion("/model", "모델 변경 (auto | composer-2)",              "/model ", false),
            new Suggestion("/mode",  "대화 모드 변경 (agent | plan)",              "/mode ",  false),
            new Suggestion("/skill", "현재 디렉토리 스킬·규칙 확인 및 팩 설치",      "/skill ", false),
            new Suggestion("/agent", "커스텀 agent 로드 (.cursor/agents/*.md)",    "/agent ", false),
            new Suggestion("/clear", "터미널 출력 지우기",                          "/clear",  false),
            new Suggestion("/cwd",   "작업 디렉토리 다시 선택",                     "/cwd",    false));

    public CursorAgentDialog(Stage owner, PluginCommandContribution contribution) {
        this.owner = owner;
        this.contribution = contribution;
    }

    /** 디렉토리 선택 화면부터 다이얼로그를 표시한다. */
    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Cursor Agent");

        root = new BorderPane();
        root.setCenter(buildStartPane());

        stage.setScene(SceneFactory.create(root, 860, 640));
        stage.setResizable(true);
        stage.show();
    }

    // ─────────────────────────────────────────────────────────
    // 1단계: 디렉토리 선택 시작 화면
    // ─────────────────────────────────────────────────────────

    private VBox buildStartPane() {
        var settings = AppContext.getInstance().getAppSettingsRepository().get();

        Label title = new Label("Cursor Agent");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label subtitle = new Label("에이전트를 실행할 작업 디렉토리를 선택하세요.");
        subtitle.getStyleClass().add("text-muted");

        cwdField = new TextField(
                settings.getPluginSetting(contribution.pluginId(), "cursor.defaultCwd", ""));
        cwdField.setPromptText("작업 디렉토리 경로");
        HBox.setHgrow(cwdField, Priority.ALWAYS);
        Button browseBtn = new Button("찾기");
        browseBtn.setOnAction(e -> chooseDirectory());
        HBox cwdRow = new HBox(8, cwdField, browseBtn);

        cwdStateLabel = new Label();
        cwdStateLabel.getStyleClass().add("text-muted");

        modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll("auto", "composer-2");
        modelCombo.setValue(settings.getPluginSetting(
                contribution.pluginId(), "cursor.defaultModel", "auto"));
        HBox modelRow = new HBox(8, new Label("모델:"), modelCombo);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        boolean apiKeyPresent = isEnvPresent("CURSOR_API_KEY");
        Label apiKeyLabel = new Label("CURSOR_API_KEY: " + (apiKeyPresent ? "감지됨" : "미설정"));
        apiKeyLabel.getStyleClass().add(apiKeyPresent ? "text-success" : "text-danger");

        depsStateLabel = new Label("@cursor/sdk: 확인 중...");
        depsStateLabel.getStyleClass().add("text-muted");
        depsInstallBtn = new Button("의존성 설치");
        depsInstallBtn.setVisible(false);
        depsInstallBtn.setManaged(false);
        depsInstallBtn.setOnAction(e -> installDependencies());
        HBox depsRow = new HBox(10, depsStateLabel, depsInstallBtn);
        depsRow.setAlignment(Pos.CENTER_LEFT);

        depsLogArea = new TextArea();
        depsLogArea.setEditable(false);
        depsLogArea.setWrapText(true);
        depsLogArea.setPrefRowCount(6);
        depsLogArea.setStyle(
                "-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:11px;");
        depsLogArea.setVisible(false);
        depsLogArea.setManaged(false);

        startBtn = new Button("세션 시작");
        startBtn.setDefaultButton(true);
        startBtn.setPrefWidth(120);
        startBtn.setOnAction(e -> startSession());
        Button closeBtn = new Button("닫기");
        closeBtn.setOnAction(e -> stage.close());
        HBox buttons = new HBox(8, startBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER);

        // 디렉토리 유효성에 따라 시작 버튼 활성화
        cwdField.textProperty().addListener((obs, old, v) -> validateCwd());
        validateCwd();

        VBox box = new VBox(14, title, subtitle, new Separator(),
                new Label("작업 디렉토리"), cwdRow, cwdStateLabel,
                modelRow, apiKeyLabel, depsRow, depsLogArea, new Separator(), buttons);
        box.setMaxWidth(520);
        box.setPadding(new Insets(28));

        // SDK 전역 설치 확인은 npm 프로세스 호출이라 백그라운드에서 수행
        checkDependenciesAsync();

        VBox wrapper = new VBox(box);
        wrapper.setAlignment(Pos.CENTER);
        VBox.setVgrow(wrapper, Priority.ALWAYS);
        return wrapper;
    }

    /** SDK 전역 설치 여부를 백그라운드에서 확인해 시작 버튼 게이트를 갱신한다. */
    private void checkDependenciesAsync() {
        var ctx = AppContext.getInstance();
        LoadedPlugin plugin = ctx.getPluginManager() != null
                ? ctx.getPluginManager().findPlugin(contribution.pluginId()) : null;
        if (plugin == null) {
            // 플러그인 메타를 못 찾으면 게이트로 막지 않는다 — 실행 시 sidecar가 다시 검증
            updateDepsUi(true);
            return;
        }
        new Thread(() -> {
            boolean installed = ctx.getPluginDependencyInstaller().isInstalled(plugin);
            Platform.runLater(() -> updateDepsUi(installed));
        }, "cursor-deps-check").start();
    }

    /** 의존성 확인 결과를 상태 라벨·설치 버튼·시작 버튼에 반영한다. */
    private void updateDepsUi(boolean installed) {
        depsReady = installed;
        depsStateLabel.getStyleClass().removeAll("text-muted", "text-success", "text-danger");
        if (installed) {
            depsStateLabel.setText("@cursor/sdk: 감지됨");
            depsStateLabel.getStyleClass().add("text-success");
            depsInstallBtn.setVisible(false);
            depsInstallBtn.setManaged(false);
        } else {
            depsStateLabel.setText("@cursor/sdk: 미설치 — 설치 후 세션을 시작할 수 있습니다.");
            depsStateLabel.getStyleClass().add("text-danger");
            depsInstallBtn.setVisible(true);
            depsInstallBtn.setManaged(true);
        }
        validateCwd();
    }

    /** 플러그인 의존성(@cursor/sdk 전역 설치)을 진행 로그와 함께 설치한다. */
    private void installDependencies() {
        var ctx = AppContext.getInstance();
        LoadedPlugin plugin = ctx.getPluginManager().findPlugin(contribution.pluginId());
        if (plugin == null) return;

        depsInstallBtn.setDisable(true);
        depsLogArea.clear();
        depsLogArea.setVisible(true);
        depsLogArea.setManaged(true);
        depsStateLabel.setText("@cursor/sdk: 설치 중...");

        ctx.getPluginDependencyInstaller().install(plugin, new PluginDependencyInstaller.ProgressCallback() {
            @Override
            public void onLog(String message) {
                Platform.runLater(() -> {
                    depsLogArea.appendText(message + "\n");
                    depsLogArea.setScrollTop(Double.MAX_VALUE);
                });
            }

            @Override
            public void onDone(boolean success) {
                Platform.runLater(() -> {
                    depsInstallBtn.setDisable(false);
                    if (success) {
                        updateDepsUi(true);
                    } else {
                        depsStateLabel.getStyleClass().removeAll("text-muted", "text-success");
                        depsStateLabel.setText("@cursor/sdk: 설치 실패 — 로그를 확인하세요.");
                        if (!depsStateLabel.getStyleClass().contains("text-danger")) {
                            depsStateLabel.getStyleClass().add("text-danger");
                        }
                    }
                });
            }
        });
    }

    /** 입력된 경로가 실제 디렉토리인지 검사해 상태 라벨·시작 버튼을 갱신한다. */
    private void validateCwd() {
        String value = cwdField.getText() == null ? "" : cwdField.getText().trim();
        boolean valid = !value.isBlank() && Files.isDirectory(Path.of(value));
        // 디렉토리 유효 + SDK 의존성 충족 시에만 세션 시작 가능
        startBtn.setDisable(!valid || !depsReady);
        if (value.isBlank()) {
            cwdStateLabel.setText("디렉토리를 선택하면 세션을 시작할 수 있습니다.");
            cwdStateLabel.getStyleClass().removeAll("text-danger");
        } else if (!valid) {
            cwdStateLabel.setText("존재하지 않는 디렉토리입니다.");
            if (!cwdStateLabel.getStyleClass().contains("text-danger")) {
                cwdStateLabel.getStyleClass().add("text-danger");
            }
        } else {
            cwdStateLabel.setText("이 디렉토리에서 Cursor Agent가 파일을 읽고 수정할 수 있습니다.");
            cwdStateLabel.getStyleClass().removeAll("text-danger");
        }
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("작업 디렉토리 선택");
        String current = cwdField.getText().trim();
        if (!current.isBlank()) {
            File dir = new File(current);
            if (dir.isDirectory()) chooser.setInitialDirectory(dir);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) cwdField.setText(selected.getAbsolutePath());
    }

    /** 선택값을 확정·저장하고 세션 화면으로 전환한다. */
    private void startSession() {
        sessionCwd = Path.of(cwdField.getText().trim()).toAbsolutePath().normalize().toString();
        sessionModel = modelCombo.getValue() != null ? modelCombo.getValue() : "auto";

        // 다음 실행을 위해 마지막 선택을 기본값으로 저장
        var repo = AppContext.getInstance().getAppSettingsRepository();
        var settings = repo.get();
        settings.setPluginSetting(contribution.pluginId(), "cursor.defaultCwd", sessionCwd);
        settings.setPluginSetting(contribution.pluginId(), "cursor.defaultModel", sessionModel);
        repo.save(settings);

        loadedAgents.clear();
        root.setCenter(buildSessionPane());
        stage.setTitle("Cursor Agent — " + sessionCwd);
        appendLine("Cursor Agent 세션 시작");
        appendLine("cwd: " + sessionCwd);
        appendLine("model: " + sessionModel);
        appendLine("/help로 명령 목록 확인");
        appendLine("");
        Platform.runLater(() -> promptArea.requestFocus());
    }

    // ─────────────────────────────────────────────────────────
    // 2단계: 터미널형 세션 화면
    // ─────────────────────────────────────────────────────────

    private BorderPane buildSessionPane() {
        sessionInfoLabel = new Label("● " + sessionCwd + "  ·  " + sessionModel);
        sessionInfoLabel.getStyleClass().add("text-muted");
        Button changeDirBtn = new Button("디렉토리 변경");
        // 실행 중이 아닐 때만 시작 화면으로 복귀 허용
        changeDirBtn.setOnAction(e -> {
            if (running.get()) return;
            root.setCenter(buildStartPane());
            stage.setTitle("Cursor Agent");
        });
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, sessionInfoLabel, headSpacer, changeDirBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));

        terminalArea = new TextArea();
        terminalArea.setEditable(false);
        terminalArea.setWrapText(true);
        terminalArea.setStyle(
                "-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:12px;");
        VBox.setVgrow(terminalArea, Priority.ALWAYS);

        promptArea = new TextArea();
        promptArea.setPromptText("프롬프트 입력 · Enter 전송 · Shift+Enter 줄바꿈 · / 명령 · @ 파일 링크");
        promptArea.setPrefRowCount(3);
        promptArea.setWrapText(true);
        initSuggestList();
        // 입력·커서 이동마다 자동완성 후보 갱신
        promptArea.textProperty().addListener((obs, old, v) -> updateSuggestions());
        promptArea.caretPositionProperty().addListener((obs, old, v) -> updateSuggestions());
        promptArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            // 자동완성 패널이 열려 있으면 방향키·Enter·Tab은 자동완성 조작에 사용
            if (suggestList.isVisible()) {
                int idx = suggestList.getSelectionModel().getSelectedIndex();
                switch (e.getCode()) {
                    case UP -> {
                        suggestList.getSelectionModel().select(Math.max(0, idx - 1));
                        suggestList.scrollTo(Math.max(0, idx - 1));
                        e.consume();
                        return;
                    }
                    case DOWN -> {
                        int next = Math.min(suggestList.getItems().size() - 1, idx + 1);
                        suggestList.getSelectionModel().select(next);
                        suggestList.scrollTo(next);
                        e.consume();
                        return;
                    }
                    case ENTER, TAB -> {
                        applySuggestion();
                        e.consume();
                        return;
                    }
                    case ESCAPE -> {
                        hideSuggestions();
                        e.consume();
                        return;
                    }
                    default -> { }
                }
            }
            // CLI처럼 Enter로 바로 전송, Shift+Enter는 줄바꿈
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                sendPrompt();
            }
        });
        HBox.setHgrow(promptArea, Priority.ALWAYS);

        runningIndicator = new ProgressIndicator();
        runningIndicator.setPrefSize(22, 22);
        runningIndicator.setVisible(false);

        sendBtn = new Button("전송");
        sendBtn.setPrefWidth(80);
        sendBtn.setOnAction(e -> sendPrompt());

        stopBtn = new Button("중지");
        stopBtn.setPrefWidth(80);
        stopBtn.setDisable(true);
        stopBtn.setStyle("-fx-base: #e74c3c; -fx-text-fill: white;");
        stopBtn.setOnAction(e -> {
            AppContext.getInstance().getPluginCommandExecutor()
                    .cancel(contribution.command().getId());
            appendLine("[중지 요청 — 프로세스 종료 중]");
        });

        Button closeBtn = new Button("닫기");
        closeBtn.setPrefWidth(80);
        closeBtn.setOnAction(e -> stage.close());
        VBox sideButtons = new VBox(8, sendBtn, stopBtn, closeBtn);
        sideButtons.setAlignment(Pos.TOP_CENTER);

        HBox inputRow = new HBox(10, promptArea, sideButtons, runningIndicator);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        inputRow.setPadding(new Insets(10, 14, 12, 14));

        VBox center = new VBox(terminalArea);
        center.setPadding(new Insets(0, 14, 0, 14));
        VBox.setVgrow(center, Priority.ALWAYS);

        BorderPane pane = new BorderPane();
        pane.setTop(new VBox(header, new Separator()));
        pane.setCenter(center);
        // 자동완성 패널은 입력창 바로 위에 인라인 표시 (숨김 시 공간 차지 없음)
        VBox bottom = new VBox(new Separator(), suggestList, inputRow);
        bottom.setPadding(new Insets(0));
        pane.setBottom(bottom);
        return pane;
    }

    /** 프롬프트를 sidecar로 전송하고 출력 스트림을 터미널 영역에 표시한다. */
    private void sendPrompt() {
        String prompt = promptArea.getText() == null ? "" : promptArea.getText().trim();
        if (prompt.isEmpty()) return;
        hideSuggestions();
        // 슬래시 명령은 sidecar로 보내지 않고 세션 내에서 처리
        if (prompt.startsWith("/")) {
            promptArea.clear();
            runLocalCommand(prompt);
            return;
        }
        if (!running.compareAndSet(false, true)) return;

        promptArea.clear();
        setRunningUi(true);
        appendLine("❯ " + prompt);

        var ctx = AppContext.getInstance();
        new Thread(() -> {
            var result = ctx.getPluginCommandExecutor().executeStreaming(
                    contribution.pluginId(),
                    contribution.command(),
                    new PluginCommandRequest(sessionCwd, prompt, sessionModel, "sdk",
                            new LinkedHashMap<>(), sessionAgentMode, SETTING_SOURCES,
                            new LinkedHashMap<>(loadedAgents)),
                    message -> Platform.runLater(() -> appendLine(message)));
            Platform.runLater(() -> {
                // 스트림에 이미 찍힌 메시지면 중복 출력하지 않는다
                if (!result.success() && (terminalArea.getText() == null
                        || !terminalArea.getText().contains(result.message()))) {
                    appendLine("[오류] " + result.message());
                }
                appendLine("");
                running.set(false);
                setRunningUi(false);
                promptArea.requestFocus();
            });
        }, "cursor-agent-session").start();
    }

    /**
     * 세션 로컬 슬래시 명령을 처리한다. /help·/clear·/model·/cwd 지원.
     *
     * @param input '/'로 시작하는 입력 전체
     */
    private void runLocalCommand(String input) {
        appendLine("❯ " + input);
        String[] parts = input.split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1].trim() : "";
        switch (parts[0]) {
            case "/help" -> {
                appendLine("사용 가능한 명령:");
                for (Suggestion s : SLASH_COMMANDS) {
                    appendLine("  " + s.label() + " — " + s.description());
                }
                appendLine("  @경로 — 작업 디렉토리 기준 파일·디렉토리 링크 삽입");
            }
            case "/clear" -> terminalArea.clear();
            case "/model" -> {
                if (arg.isBlank()) {
                    appendLine("현재 모델: " + sessionModel + " (사용 가능: auto, composer-2)");
                } else {
                    sessionModel = arg;
                    sessionInfoLabel.setText("● " + sessionCwd + "  ·  " + sessionModel);
                    var repo = AppContext.getInstance().getAppSettingsRepository();
                    var settings = repo.get();
                    settings.setPluginSetting(contribution.pluginId(), "cursor.defaultModel", sessionModel);
                    repo.save(settings);
                    appendLine("모델 변경: " + sessionModel);
                }
            }
            case "/mode" -> {
                if (arg.isBlank()) {
                    appendLine("현재 모드: " + sessionAgentMode + " (사용 가능: agent, plan)");
                } else if ("agent".equals(arg) || "plan".equals(arg)) {
                    sessionAgentMode = arg;
                    appendLine("모드 변경: " + sessionAgentMode);
                } else {
                    appendLine("지원하지 않는 모드: " + arg + " (사용 가능: agent, plan)");
                }
            }
            case "/skill" -> runSkillCommand(arg);
            case "/agent" -> runAgentCommand(arg);
            case "/cwd" -> {
                if (running.get()) {
                    appendLine("실행 중에는 디렉토리를 변경할 수 없습니다.");
                } else {
                    root.setCenter(buildStartPane());
                    stage.setTitle("Cursor Agent");
                    return;
                }
            }
            default -> appendLine("알 수 없는 명령: " + parts[0] + " — /help 참고");
        }
        appendLine("");
    }

    /**
     * /skill 명령 처리. 인수가 없으면 ① 세션 cwd에 이미 있는 스킬·규칙·명령
     * (.cursor/skills, rules, commands — 실행 시 자동 적용)과 ② 앱 내장 설치 가능 팩을
     * 모두 보여준다. 팩 id를 주면 해당 팩 파일을 세션 cwd에 설치한다.
     *
     * @param arg 팩 id ("cursor-" 접두사 생략 가능). 빈 문자열이면 목록 표시
     */
    private void runSkillCommand(String arg) {
        var installer = AppContext.getInstance().getLlmSkillInstaller();
        LlmTool cursorTool = installer.loadTools().stream()
                .filter(t -> "cursor".equals(t.getId()))
                .findFirst().orElse(null);

        if (arg.isBlank()) {
            listProjectSkills();
            if (cursorTool != null) {
                appendLine("설치 가능한 앱 내장 팩 — /skill <id>로 설치:");
                for (SkillPack pack : cursorTool.getPacks()) {
                    boolean installed = pack.getFiles().stream().allMatch(
                            f -> Files.exists(Path.of(sessionCwd).resolve(f.getTargetPath())));
                    appendLine("  " + pack.getId() + " — " + pack.getName()
                            + (installed ? " [설치됨]" : ""));
                }
            }
            return;
        }

        if (cursorTool == null) {
            appendLine("Cursor 스킬 팩 정의를 찾을 수 없습니다.");
            return;
        }

        // "java"처럼 접두사 없이 입력해도 cursor-java 팩에 매칭
        SkillPack pack = cursorTool.getPacks().stream()
                .filter(p -> p.getId().equals(arg) || p.getId().equals("cursor-" + arg))
                .findFirst().orElse(null);
        if (pack == null) {
            appendLine("팩을 찾을 수 없습니다: " + arg + " — /skill로 목록 확인");
            return;
        }

        for (SkillFile sf : pack.getFiles()) {
            try {
                Path target = Path.of(sessionCwd).resolve(sf.getTargetPath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, installer.readSkillContent(sf, Map.of()),
                        java.nio.charset.StandardCharsets.UTF_8);
                appendLine("설치됨: " + sf.getTargetPath());
            } catch (Exception e) {
                appendLine("설치 실패: " + sf.getTargetPath() + " — " + e.getMessage());
            }
        }
        appendLine("다음 실행부터 규칙이 적용됩니다.");
    }

    /**
     * 세션 cwd에 이미 존재하는 스킬·규칙·명령을 터미널에 나열한다.
     * settingSources에 project가 포함되므로 이 항목들은 실행 시 자동 적용된다.
     */
    private void listProjectSkills() {
        Path cursorDir = Path.of(sessionCwd).resolve(".cursor");
        boolean foundAny = false;

        // .cursor/skills/<이름>/SKILL.md 구조의 프로젝트 스킬
        List<Path> skillDirs = listSkillDirs(cursorDir.resolve("skills"));
        if (!skillDirs.isEmpty()) {
            foundAny = true;
            appendLine("현재 디렉토리 스킬 (.cursor/skills) — 실행 시 자동 적용:");
            for (Path dir : skillDirs) {
                String desc = readMarkdownDescription(dir.resolve("SKILL.md"));
                appendLine("  " + dir.getFileName()
                        + (desc.isBlank() ? "" : " — " + desc));
            }
        }

        List<Path> rules = listFilesByExtension(cursorDir.resolve("rules"), ".mdc");
        if (!rules.isEmpty()) {
            foundAny = true;
            appendLine("현재 디렉토리 규칙 (.cursor/rules) — 실행 시 자동 적용:");
            for (Path file : rules) appendLine("  " + file.getFileName());
        }

        List<Path> commands = listFilesByExtension(cursorDir.resolve("commands"), ".md");
        if (!commands.isEmpty()) {
            foundAny = true;
            appendLine("현재 디렉토리 명령 (.cursor/commands):");
            for (Path file : commands) appendLine("  " + file.getFileName());
        }

        if (!foundAny) {
            appendLine("현재 디렉토리에 스킬·규칙이 없습니다: " + cursorDir);
        }
        appendLine("");
    }

    /** SKILL.md가 있는 하위 디렉토리(=스킬)를 이름순으로 반환한다. */
    private List<Path> listSkillDirs(Path skillsRoot) {
        if (!Files.isDirectory(skillsRoot)) return List.of();
        try (Stream<Path> stream = Files.list(skillsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("SKILL.md")))
                    .sorted(Comparator.comparing(d -> d.getFileName().toString()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 지정 확장자의 파일을 이름순으로 반환한다. 디렉토리가 없으면 빈 목록. */
    private List<Path> listFilesByExtension(Path dir, String extension) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 마크다운의 frontmatter description 또는 첫 본문 줄을 한 줄 설명으로 읽는다 (최대 80자). */
    private String readMarkdownDescription(Path file) {
        try {
            String description = parseAgentDefinition("", Files.readString(file)).get("description");
            if (description == null) return "";
            return description.length() > 80 ? description.substring(0, 80) + "..." : description;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * /agent 명령 처리. 인수가 없으면 cwd/.cursor/agents/*.md 목록과 로드 상태를 보여주고,
     * 이름을 주면 해당 정의를 파싱해 세션에 로드한다. "clear"는 전체 해제.
     *
     * @param arg agent 이름(파일명, .md 생략 가능) 또는 "clear"
     */
    private void runAgentCommand(String arg) {
        Path agentsDir = Path.of(sessionCwd).resolve(".cursor").resolve("agents");

        if ("clear".equals(arg)) {
            loadedAgents.clear();
            appendLine("로드된 agent를 모두 해제했습니다.");
            return;
        }

        if (arg.isBlank()) {
            List<Path> files = listAgentFiles(agentsDir);
            if (files.isEmpty() && loadedAgents.isEmpty()) {
                appendLine("agent 정의가 없습니다. " + agentsDir + " 에 *.md 파일을 추가하세요.");
                return;
            }
            appendLine("agent 정의 — /agent <이름>으로 로드, /agent clear로 해제:");
            for (Path file : files) {
                String name = stripMdExtension(file.getFileName().toString());
                appendLine("  " + name + (loadedAgents.containsKey(name) ? " [로드됨]" : ""));
            }
            // 파일은 삭제됐지만 세션에 남아 있는 agent도 표시
            for (String name : loadedAgents.keySet()) {
                boolean onDisk = files.stream().anyMatch(
                        f -> stripMdExtension(f.getFileName().toString()).equals(name));
                if (!onDisk) appendLine("  " + name + " [로드됨 — 파일 없음]");
            }
            return;
        }

        Path file = agentsDir.resolve(arg.endsWith(".md") ? arg : arg + ".md");
        if (!Files.isRegularFile(file)) {
            appendLine("agent 파일이 없습니다: " + file);
            return;
        }
        try {
            String name = stripMdExtension(file.getFileName().toString());
            Map<String, String> def = parseAgentDefinition(name, Files.readString(file));
            loadedAgents.put(name, def);
            appendLine("agent 로드됨: " + name + " — " + def.get("description"));
            appendLine("다음 실행부터 서브에이전트로 사용 가능합니다. 프롬프트에서 이름으로 위임을 지시하세요.");
        } catch (Exception e) {
            appendLine("agent 로드 실패: " + e.getMessage());
        }
    }

    /** agents 디렉토리의 *.md 파일을 이름순으로 반환한다. 디렉토리가 없으면 빈 목록. */
    private List<Path> listAgentFiles(Path agentsDir) {
        if (!Files.isDirectory(agentsDir)) return List.of();
        try (Stream<Path> stream = Files.list(agentsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * agent 마크다운을 {description, prompt} 맵으로 파싱한다.
     * 선두 YAML frontmatter(---)의 description을 우선 사용하고,
     * 없으면 본문 첫 줄을 설명으로 쓴다. prompt는 frontmatter를 제외한 본문 전체.
     */
    private Map<String, String> parseAgentDefinition(String name, String content) {
        String description = name;
        String prompt = content;

        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end);
                prompt = content.substring(content.indexOf('\n', end + 1) + 1).trim();
                for (String line : frontmatter.split("\\R")) {
                    if (line.trim().startsWith("description:")) {
                        description = line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
        }
        if (description.equals(name)) {
            // frontmatter가 없으면 본문 첫 비어있지 않은 줄을 설명으로 사용
            for (String line : prompt.split("\\R")) {
                String trimmed = line.replaceAll("^#+\\s*", "").trim();
                if (!trimmed.isBlank()) {
                    description = trimmed;
                    break;
                }
            }
        }
        Map<String, String> def = new LinkedHashMap<>();
        def.put("description", description);
        def.put("prompt", prompt);
        return def;
    }

    private String stripMdExtension(String fileName) {
        return fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    // ─────────────────────────────────────────────────────────
    // 프롬프트 자동완성 ('/' 명령 · '@' 경로)
    // ─────────────────────────────────────────────────────────

    private void initSuggestList() {
        suggestList = new ListView<>();
        // 숨김 상태에서는 레이아웃 공간도 차지하지 않도록 visible과 managed를 함께 토글
        suggestList.setVisible(false);
        suggestList.setManaged(false);
        suggestList.setFocusTraversable(false);
        VBox.setMargin(suggestList, new Insets(6, 14, 0, 14));
        suggestList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Suggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.label());
                name.setStyle("-fx-font-family:'Consolas','Courier New',monospace;");
                Label desc = new Label(item.description());
                desc.getStyleClass().add("text-muted");
                HBox box = new HBox(10, name, desc);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                setText(null);
            }
        });
        suggestList.setOnMouseClicked(e -> applySuggestion());
    }

    private void hideSuggestions() {
        suggestList.setVisible(false);
        suggestList.setManaged(false);
    }

    /**
     * 커서 위치의 토큰을 분석해 자동완성 팝업을 갱신한다.
     * 입력 맨 앞의 '/'는 슬래시 명령, '@'로 시작하는 토큰은 cwd 기준 경로 후보를 보여준다.
     */
    private void updateSuggestions() {
        if (suggestList == null || promptArea == null) return;
        String text = promptArea.getText() == null ? "" : promptArea.getText();
        int caret = Math.min(promptArea.getCaretPosition(), text.length());

        // 커서 앞쪽으로 공백 직전까지가 현재 토큰
        int ws = caret - 1;
        while (ws >= 0 && !Character.isWhitespace(text.charAt(ws))) ws--;
        int tokenStart = ws + 1;
        String token = text.substring(tokenStart, caret);

        List<Suggestion> items;
        if (tokenStart == 0 && token.startsWith("/")) {
            items = SLASH_COMMANDS.stream()
                    .filter(s -> s.label().startsWith(token))
                    .toList();
        } else if (token.startsWith("@")) {
            items = fileSuggestions(token.substring(1));
        } else {
            hideSuggestions();
            return;
        }

        if (items.isEmpty()) {
            hideSuggestions();
            return;
        }
        suggestTokenStart = tokenStart;
        suggestList.setItems(FXCollections.observableArrayList(items));
        suggestList.getSelectionModel().selectFirst();
        // 항목 수에 맞춰 높이 조절 — 최대 8행
        double height = Math.min(items.size(), 8) * 28 + 10;
        suggestList.setPrefHeight(height);
        suggestList.setMaxHeight(height);
        suggestList.setVisible(true);
        suggestList.setManaged(true);
    }

    /**
     * 작업 디렉토리 기준 경로 후보를 만든다. partial은 '@' 뒤의 부분 경로로,
     * '/' 구분 하위 디렉토리 탐색을 지원한다.
     *
     * @param partial '@' 뒤에 입력된 부분 경로 (예: "src/ma")
     * @return 이름 prefix가 일치하는 항목 최대 20개 (디렉토리 우선 정렬)
     */
    private List<Suggestion> fileSuggestions(String partial) {
        int slash = partial.lastIndexOf('/');
        String dirPart = slash >= 0 ? partial.substring(0, slash + 1) : "";
        String prefix = partial.substring(slash + 1).toLowerCase();

        Path base;
        try {
            base = Path.of(sessionCwd).resolve(dirPart).normalize();
        } catch (InvalidPathException e) {
            return List.of();
        }
        if (!Files.isDirectory(base)) return List.of();

        try (Stream<Path> stream = Files.list(base)) {
            return stream
                    .filter(this::isVisiblePath)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith(prefix))
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .limit(20)
                    .map(p -> {
                        boolean dir = Files.isDirectory(p);
                        String name = p.getFileName().toString() + (dir ? "/" : "");
                        return new Suggestion(name, dir ? "디렉토리" : "파일",
                                "@" + dirPart + name, dir);
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 무거운 빌드 산출물·숨김 디렉토리는 경로 후보에서 제외한다. */
    private boolean isVisiblePath(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString();
        return !name.equals(".git")
                && !name.equals("node_modules")
                && !name.equals("build")
                && !name.equals(".gradle")
                && !name.equals("target")
                && !name.equals("dist")
                && !name.equals("__pycache__");
    }

    /** 선택된 자동완성 항목으로 현재 토큰을 치환한다. */
    private void applySuggestion() {
        Suggestion selected = suggestList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            hideSuggestions();
            return;
        }
        int caret = promptArea.getCaretPosition();
        promptArea.replaceText(suggestTokenStart, caret, selected.insert());
        // 디렉토리 선택 시 텍스트 변경 리스너가 하위 항목으로 패널을 다시 연다
        if (!selected.directory()) hideSuggestions();
        // 마우스 클릭으로 선택한 경우 입력 포커스를 되돌린다
        promptArea.requestFocus();
        promptArea.positionCaret(suggestTokenStart + selected.insert().length());
    }

    private void setRunningUi(boolean active) {
        sendBtn.setDisable(active);
        stopBtn.setDisable(!active);
        runningIndicator.setVisible(active);
    }

    private void appendLine(String line) {
        if (!terminalArea.getText().isEmpty()) terminalArea.appendText("\n");
        terminalArea.appendText(line != null ? line : "");
        terminalArea.setScrollTop(Double.MAX_VALUE);
    }

    private boolean isEnvPresent(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }
}
