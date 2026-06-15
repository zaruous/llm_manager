/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.service.InstallationService;
import org.kyj.llmmanager.service.PluginManager;
import org.kyj.llmmanager.service.SystemMonitorService;
import org.kyj.llmmanager.setup.SetupCheckDialog;
import org.kyj.llmmanager.util.PlatformUtil;
import org.kyj.llmmanager.ui.cell.ServiceListCell;
import org.kyj.llmmanager.ui.dialog.ServiceDetailDialog;
import org.kyj.llmmanager.util.CommandBuilder;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import org.kyj.llmmanager.ui.dialog.AddServiceDialog;
import org.kyj.llmmanager.ui.dialog.CursorAgentDialog;
import org.kyj.llmmanager.ui.dialog.HelpDialog;
import org.kyj.llmmanager.ui.dialog.PluginCommandRunDialog;
import org.kyj.llmmanager.ui.dialog.PluginManagerDialog;
import org.kyj.llmmanager.ui.dialog.SettingsDialog;
import org.kyj.llmmanager.ui.dialog.UpdateInstallDialog;
import org.kyj.llmmanager.ui.dialog.WikiBrowserDialog;
import org.kyj.llmmanager.ui.dialog.WikiIngestDialog;
import org.kyj.llmmanager.ui.dialog.WikiQueryDialog;
import org.kyj.llmmanager.service.UpdateChecker;
import javafx.concurrent.Task;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.beans.value.ChangeListener;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 메인 화면 컨트롤러.
 * 서비스 목록 표시, 서비스 선택 시 상세 정보(개요/로그/설정/설치) 표시,
 * 서비스 시작·중지·재시작·추가·삭제를 처리한다.
 */
public class MainController implements Initializable {

    // ---- Menu bar ----
    /** 메뉴바 (Stage 참조 획득에 사용) */
    @FXML private MenuBar menuBar;

    /** 플러그인 메뉴. 플러그인별 command 서브메뉴를 동적으로 구성한다. */
    @FXML private Menu pluginMenu;

    /** 플러그인 메뉴의 고정 항목(실행·관리). 동적 재구성 시에도 하단에 유지된다. */
    private List<MenuItem> pluginMenuStaticItems;

    // ---- Left panel ----
    /** 좌측 패널: 검색 필드 */
    @FXML private TextField searchField;
    /** 좌측 패널: 서비스 목록 */
    @FXML private ListView<ServiceInstance> serviceListView;

    // ---- Detail header ----
    /** 선택된 서비스의 상태 요약 헤더 - 상태 색상 점 */
    @FXML private Circle detailStatusDot;
    /** 선택된 서비스의 상태 요약 헤더 - 서비스 이름 */
    @FXML private Label detailNameLabel;
    /** 선택된 서비스의 상태 요약 헤더 - 상태 텍스트 */
    @FXML private Label detailStatusLabel;

    // ---- Overview tab ----
    /** 개요 탭: PID 표시 */
    @FXML private Label pidLabel;
    /** 개요 탭: 포트 표시 */
    @FXML private Label portLabel;
    /** 개요 탭: 업타임 표시 */
    @FXML private Label uptimeLabel;
    /** 서비스 시작 버튼 */
    @FXML private Button startBtn;
    /** 서비스 중지 버튼 */
    @FXML private Button stopBtn;
    /** 서비스 재시작 버튼 */
    @FXML private Button restartBtn;

    // ---- Logs tab ----
    /** 로그 탭: 로그 출력 영역 */
    @FXML private TextArea logArea;
    /** 로그 탭: 자동 스크롤 체크박스 */
    @FXML private CheckBox autoScrollCheck;
    /** 로그 탭: 로그 필터 입력 필드 */
    @FXML private TextField filterField;
    /** 로그 탭: 프로세스 stdout/stderr 디코딩 인코딩 선택 */
    @FXML private ComboBox<String> logEncodingCombo;

    // ---- Config tab ----
    /** 설정 탭: 실행 인수 입력 영역 */
    @FXML private VBox argsContainer;
    /** 설정 탭: 환경변수 입력 영역 */
    @FXML private VBox envVarsContainer;
    /** 설정 탭: 앱 시작 시 서비스 자동 실행 여부 */
    @FXML private CheckBox autoStartCheck;

    // ---- Install tab ----
    /** 설치 탭: 설치 경로 표시 레이블 */
    @FXML private Label installDirLabel;
    /** 설치 탭: 작업 경로 표시 레이블 */
    @FXML private Label workingDirLabel;
    /** 설치 탭: 설치 로그 출력 영역 */
    @FXML private TextArea installLogArea;
    /** 설치 탭: 진행률 표시 바 */
    @FXML private ProgressBar progressBar;
    /** 설치 탭: 설치 버튼 */
    @FXML private Button installBtn;
    /** 설치 탭: 제거 버튼 */
    @FXML private Button uninstallBtn;

    // ---- Status bar ----
    /** 하단 상태 바 (전체/실행 중 서비스 수) */
    @FXML private Label statusBarLabel;

    // ---- Dashboard (홈 뷰) ----
    /** 서비스 상세 헤더 HBox. 서비스 미선택 시 숨김. */
    @FXML private HBox         detailHeader;

    // ---- 시스템 리소스 패널 ----
    /** CPU 사용률 게이지 */
    @FXML private ProgressBar cpuBar;
    /** 물리 메모리 사용률 게이지 */
    @FXML private ProgressBar memBar;
    /** JVM 힙 사용률 게이지 */
    @FXML private ProgressBar jvmBar;
    /** CPU 수치 레이블 */
    @FXML private Label cpuLabel;
    /** 물리 메모리 수치 레이블 */
    @FXML private Label memLabel;
    /** JVM 힙 수치 레이블 */
    @FXML private Label jvmLabel;
    /** 2초 간격 시스템 리소스 UI 갱신 타이머 */
    private Timeline systemStatsTimer;
    /** 대시보드 뷰 컨테이너. 서비스 미선택 시 기본으로 표시. */
    @FXML private BorderPane dashboardPane;
    /** 서비스 카드를 배치하는 FlowPane */
    @FXML private FlowPane dashboardCardPane;
    /** 대시보드 요약 - 전체 서비스 수 */
    @FXML private Label dashTotalLabel;
    /** 대시보드 요약 - 실행 중 서비스 수 */
    @FXML private Label dashRunningLabel;
    /** 대시보드 요약 - 오류 서비스 수 (오류 없으면 숨김) */
    @FXML private Label dashErrorLabel;
    /** 서비스 상세 탭패인. 서비스 선택 시 표시. */
    @FXML private TabPane detailTabPane;

    // ---- State ----
    /** 앱 전역 컨텍스트 */
    private AppContext ctx;
    /** ListView에 바인딩된 서비스 인스턴스 Observable 목록 */
    private final ObservableList<ServiceInstance> instanceList = FXCollections.observableArrayList();
    /** 현재 선택된 서비스 인스턴스 */
    private ServiceInstance selectedInstance;
    /**
     * 선택 서비스의 로그 추가 이벤트를 감지하는 리스너.
     * 선택 변경 시 이전 리스너를 반드시 해제한다.
     */
    private ListChangeListener<LogEntry> logListener;
    /** 선택 서비스 상태 변경 리스너. 선택 변경 시 이전 리스너를 해제한다. */
    private ChangeListener<ServiceStatus> selectedStatusListener;
    /** 대시보드 카드 재빌드 시 이전 카드 리스너를 해제하기 위한 cleanup 목록. */
    private final List<Runnable> dashboardListenerCleanups = new ArrayList<>();
    /** 설정 탭의 인수 이름 → 입력 컨트롤 맵 */
    private final Map<String, Control> argControls = new HashMap<>();
    /** 1초 간격으로 업타임 레이블을 갱신하는 스케줄러 */
    private ScheduledExecutorService uptimeScheduler;
    /** 로그 탭 TextArea에 현재 표시 중인 행 수. 5000행 초과 시 앞 1000행을 제거한다. */
    private int logLineCount = 0;
    /** 서비스 선택 시 콤보 값을 맞추는 동안 저장 이벤트를 막는다. */
    private boolean updatingLogEncoding = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        logEncodingCombo.setItems(FXCollections.observableArrayList(
                "시스템 기본값", "UTF-8", "MS949", "EUC-KR", "ISO-8859-1"));
        logEncodingCombo.setValue("시스템 기본값");

        serviceListView.setItems(instanceList);
        serviceListView.setCellFactory(lv -> new ServiceListCell(this::removeService));

        serviceListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> onServiceSelected(selected));

        // 더블클릭: 서비스 상세 팝업 표시
        serviceListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2
                    && event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                ServiceInstance sel = serviceListView.getSelectionModel().getSelectedItem();
                if (sel != null) openDetailDialog(sel);
            }
        });

        // Search filter
        searchField.textProperty().addListener((obs, old, val) -> filterList(val));

        // Uptime timer
        uptimeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "uptime-timer");
            t.setDaemon(true);
            return t;
        });
        uptimeScheduler.scheduleAtFixedRate(() -> {
            if (selectedInstance != null
                    && selectedInstance.getStatus() == ServiceStatus.RUNNING) {
                Platform.runLater(() -> {
                    if (uptimeLabel != null)
                        uptimeLabel.setText(selectedInstance.getUptimeString());
                });
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * AppContext를 주입하고 서비스 목록을 로드한다. FXML initialize() 이후 호출.
     *
     * @param ctx 주입할 앱 전역 컨텍스트
     */
    public void initializeContext(AppContext ctx) {
        this.ctx = ctx;
        loadServices();
        showDashboard();

        // 플러그인 메뉴 동적 구성 — 메뉴를 열 때마다 재구성해 '플러그인 관리'의 새로고침을 반영
        pluginMenuStaticItems = new ArrayList<>(pluginMenu.getItems());
        pluginMenu.setOnShowing(e -> rebuildPluginMenu());
        rebuildPluginMenu();

        // 시스템 리소스 UI 타이머 — 2초마다 최신 수집값을 화면에 반영
        systemStatsTimer = new Timeline(
                new KeyFrame(Duration.seconds(2), e -> updateSystemStats()));
        systemStatsTimer.setCycleCount(Timeline.INDEFINITE);
        systemStatsTimer.play();
        updateSystemStats();  // 즉시 초기 표시
    }

    /**
     * ServiceRegistry에서 서비스를 읽어 instanceList를 갱신한다.
     */
    private void loadServices() {
        instanceList.clear();
        for (ServiceDefinition def : ctx.getServiceRegistry().getAll()) {
            ServiceInstance inst = ctx.getProcessManager().getOrCreate(def);
            if (ctx.getInstallationService().isInstalled(def)
                    && inst.getStatus() == ServiceStatus.NOT_INSTALLED) {
                inst.setStatus(ServiceStatus.INSTALLED);
            }
            instanceList.add(inst);
        }
        updateStatusBar();
    }

    /**
     * 검색어로 서비스 목록을 필터링한다.
     *
     * @param query 검색어 (null 또는 빈 문자열이면 전체 표시)
     */
    private void filterList(String query) {
        // Re-populate with filtered items
        List<ServiceInstance> all = new ArrayList<>();
        for (ServiceDefinition def : ctx.getServiceRegistry().getAll()) {
            ServiceInstance inst = ctx.getProcessManager().getOrCreate(def);
            all.add(inst);
        }
        instanceList.clear();
        if (query == null || query.isBlank()) {
            instanceList.addAll(all);
        } else {
            String q = query.toLowerCase();
            for (ServiceInstance inst : all) {
                if (inst.getDefinition().getName().toLowerCase().contains(q)) {
                    instanceList.add(inst);
                }
            }
        }
    }

    // =========================================================
    // Service selection
    // =========================================================

    /**
     * 서비스 선택 시 이전 로그 리스너를 해제하고 새 서비스 정보를 상세 패널에 표시한다.
     *
     * @param instance 새로 선택된 ServiceInstance
     */
    private void onServiceSelected(ServiceInstance instance) {
        detachSelectedServiceListeners();
        if (instance == null) {
            selectedInstance = null;
            return;
        }

        selectedInstance = instance;
        showDetail();  // 서비스 선택 시 상세 뷰로 전환

        // Status listener
        selectedStatusListener = (obs, old, newVal) -> refreshDetail();
        instance.statusProperty().addListener(selectedStatusListener);
        refreshDetail();
        refreshLogs();
        refreshLogEncoding();
        refreshConfig();
        refreshInstall();
    }

    /** 선택 서비스에 붙어 있던 상세 화면 리스너를 해제한다. */
    private void detachSelectedServiceListeners() {
        if (selectedInstance != null && logListener != null) {
            selectedInstance.getLogs().removeListener(logListener);
            logListener = null;
        }
        if (selectedInstance != null && selectedStatusListener != null) {
            selectedInstance.statusProperty().removeListener(selectedStatusListener);
            selectedStatusListener = null;
        }
    }

    /**
     * 선택 서비스의 상태·PID·포트·업타임을 헤더와 개요 탭에 갱신한다.
     */
    private void refreshDetail() {
        if (selectedInstance == null) return;
        ServiceDefinition def = selectedInstance.getDefinition();
        ServiceStatus status = selectedInstance.getStatus();

        detailNameLabel.setText(def.getName());
        detailStatusLabel.setText(status.getLabel());
        try {
            detailStatusDot.setFill(Color.web(status.getColor()));
        } catch (Exception ignored) {}

        pidLabel.setText(selectedInstance.getPid() > 0
                ? String.valueOf(selectedInstance.getPid()) : "-");
        portLabel.setText(def.getPort() != null ? String.valueOf(def.getPort()) : "-");
        uptimeLabel.setText(selectedInstance.getUptimeString());

        boolean running = status == ServiceStatus.RUNNING;
        boolean canStart = status == ServiceStatus.STOPPED
                || status == ServiceStatus.INSTALLED
                || status == ServiceStatus.ERROR;

        startBtn.setDisable(!canStart);
        stopBtn.setDisable(!running);
        restartBtn.setDisable(!running);
        updateStatusBar();
    }

    // =========================================================
    // Overview tab actions
    // =========================================================

    @FXML
    private void onStart() {
        if (selectedInstance == null) return;
        ctx.getProcessManager().start(selectedInstance);
    }

    @FXML
    private void onStop() {
        if (selectedInstance == null) return;
        ctx.getProcessManager().stop(selectedInstance);
    }

    @FXML
    private void onRestart() {
        if (selectedInstance == null) return;
        ctx.getProcessManager().restart(selectedInstance);
    }

    // =========================================================
    // Logs tab
    // =========================================================

    private void refreshLogs() {
        if (selectedInstance == null) return;
        logArea.clear();
        logLineCount = 0;
        for (LogEntry entry : selectedInstance.getLogs()) {
            appendLogEntry(entry);
        }

        logListener = change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    change.getAddedSubList().forEach(this::appendLogEntry);
                }
            }
        };
        selectedInstance.getLogs().addListener(logListener);
    }

    private void refreshLogEncoding() {
        if (selectedInstance == null) return;
        String charset = selectedInstance.getDefinition().getLogCharset();
        updatingLogEncoding = true;
        try {
            logEncodingCombo.setValue(charset == null || charset.isBlank()
                    ? "시스템 기본값" : charset);
        } finally {
            updatingLogEncoding = false;
        }
    }

    private void appendLogEntry(LogEntry entry) {
        String filter = filterField.getText();
        if (filter != null && !filter.isBlank()
                && !entry.getMessage().contains(filter)) return;
        logArea.appendText("[" + entry.getTimeString() + "] " + entry.getMessage() + "\n");
        logLineCount++;

        // 5000행 초과 시 앞 1000행 제거 — TextArea 메모리 과다 사용 방지
        if (logLineCount > 5000) {
            String text = logArea.getText();
            int idx = 0;
            for (int i = 0; i < 1000; i++) {
                int next = text.indexOf('\n', idx);
                if (next == -1) { idx = text.length(); break; }
                idx = next + 1;
            }
            logArea.setText(text.substring(idx));
            logLineCount -= 1000;
        }

        if (autoScrollCheck.isSelected()) {
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    @FXML
    private void onClear() {
        logArea.clear();
        logLineCount = 0;
    }

    @FXML
    private void onFilter() {
        if (selectedInstance == null) return;
        logArea.clear();
        selectedInstance.getLogs().forEach(this::appendLogEntry);
    }

    @FXML
    private void onLogEncodingChanged() {
        if (updatingLogEncoding || selectedInstance == null || ctx == null) return;
        String selected = logEncodingCombo.getValue();
        String charset = "시스템 기본값".equals(selected) ? "" : selected;
        ServiceDefinition def = selectedInstance.getDefinition();
        def.setLogCharset(charset);
        ctx.getServiceRegistry().update(def);
        if (selectedInstance.getStatus() == ServiceStatus.RUNNING) {
            ctx.getLogService().addSystemLog(selectedInstance,
                    "로그 인코딩 변경은 다음 시작부터 적용됩니다: "
                            + (charset.isBlank() ? "시스템 기본값" : charset));
        }
    }

    // =========================================================
    // Config tab
    // =========================================================

    private void refreshConfig() {
        if (selectedInstance == null) return;
        ServiceDefinition def = selectedInstance.getDefinition();
        autoStartCheck.setSelected(def.isAutoStart());
        buildArgForm(def);
        buildEnvForm(def);
    }

    /**
     * ArgSpec 목록을 GridPane으로 배치한다.
     * 0열(라벨) / 1열(입력 컨트롤) / 2열(설명)이 모든 행에 걸쳐 동일 너비로 정렬된다.
     *
     * @param def 인수 명세를 포함하는 ServiceDefinition
     */
    private void buildArgForm(ServiceDefinition def) {
        argsContainer.getChildren().clear();
        argControls.clear();

        if (def.getArgSpecs().isEmpty()) return;

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(4, 0, 4, 0));

        // 0열: 라벨 — 고정 너비
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(140);
        labelCol.setPrefWidth(140);
        labelCol.setMaxWidth(140);
        labelCol.setHgrow(Priority.NEVER);

        // 1열: 입력 컨트롤 — 고정 너비
        ColumnConstraints controlCol = new ColumnConstraints();
        controlCol.setMinWidth(200);
        controlCol.setPrefWidth(200);
        controlCol.setMaxWidth(200);
        controlCol.setHgrow(Priority.NEVER);

        // 2열: 설명 — 남은 공간을 채움
        ColumnConstraints descCol = new ColumnConstraints();
        descCol.setMinWidth(0);
        descCol.setHgrow(Priority.ALWAYS);
        descCol.setFillWidth(true);

        grid.getColumnConstraints().addAll(labelCol, controlCol, descCol);

        int row = 0;
        for (ArgSpec spec : def.getArgSpecs()) {
            Label label = new Label(spec.getName() + ":");
            label.setStyle("-fx-text-fill: #aaaaaa;");

            String currentVal = def.getArgValues().getOrDefault(
                    spec.getName(), spec.getDefaultValue());

            Control control;
            if ("BOOLEAN".equals(spec.getType())) {
                CheckBox cb = new CheckBox();
                cb.setSelected("true".equalsIgnoreCase(currentVal));
                control = cb;
            } else if ("SELECT".equals(spec.getType()) && spec.getOptions() != null) {
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().addAll(spec.getOptions());
                combo.setValue(currentVal);
                combo.setMaxWidth(Double.MAX_VALUE);
                control = combo;
            } else {
                TextField tf = new TextField(currentVal != null ? currentVal : "");
                tf.setMaxWidth(Double.MAX_VALUE);
                if (spec.getDefaultValue() != null)
                    tf.setPromptText(spec.getDefaultValue());
                control = tf;
            }

            argControls.put(spec.getName(), control);
            grid.add(label, 0, row);
            grid.add(control, 1, row);

            if (spec.getDescription() != null) {
                Label desc = new Label(spec.getDescription());
                desc.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");
                desc.setWrapText(true);
                grid.add(desc, 2, row);
            }

            row++;
        }

        argsContainer.getChildren().add(grid);
    }

    /**
     * 환경변수 맵을 키=값 행으로 설정 탭에 표시한다.
     *
     * @param def 환경변수 맵을 포함하는 ServiceDefinition
     */
    private void buildEnvForm(ServiceDefinition def) {
        envVarsContainer.getChildren().clear();
        for (Map.Entry<String, String> entry : def.getEnvVars().entrySet()) {
            addEnvRow(entry.getKey(), entry.getValue());
        }
    }

    private void addEnvRow(String key, String value) {
        HBox row = new HBox(8);
        row.setPadding(new Insets(2, 0, 2, 0));
        TextField keyField = new TextField(key);
        keyField.setPrefWidth(150);
        TextField valField = new TextField(value);
        valField.setPrefWidth(250);
        Button removeBtn = new Button("X");
        removeBtn.setOnAction(e -> envVarsContainer.getChildren().remove(row));
        row.getChildren().addAll(keyField, new Label("="), valField, removeBtn);
        envVarsContainer.getChildren().add(row);
    }

    @FXML
    private void onAddEnvVar() {
        addEnvRow("", "");
    }

    /**
     * 설정 탭의 인수 값과 환경변수를 ServiceDefinition에 저장하고 파일에 기록한다.
     */
    @FXML
    @SuppressWarnings("unchecked")
    private void onSaveConfig() {
        if (selectedInstance == null) return;
        ServiceDefinition def = selectedInstance.getDefinition();
        def.setAutoStart(autoStartCheck.isSelected());

        // Save arg values
        for (Map.Entry<String, Control> entry : argControls.entrySet()) {
            String name = entry.getKey();
            Control ctrl = entry.getValue();
            String value;
            if (ctrl instanceof CheckBox cb) {
                value = String.valueOf(cb.isSelected());
            } else if (ctrl instanceof ComboBox<?> combo) {
                value = combo.getValue() != null ? combo.getValue().toString() : "";
            } else {
                value = ((TextField) ctrl).getText();
            }
            def.getArgValues().put(name, value);
        }

        // Save env vars
        Map<String, String> envVars = new HashMap<>();
        for (var node : envVarsContainer.getChildren()) {
            if (node instanceof HBox row) {
                if (row.getChildren().size() >= 3) {
                    TextField keyField = (TextField) row.getChildren().get(0);
                    TextField valField = (TextField) row.getChildren().get(2);
                    if (!keyField.getText().isBlank()) {
                        envVars.put(keyField.getText(), valField.getText());
                    }
                }
            }
        }
        def.getEnvVars().clear();
        def.getEnvVars().putAll(envVars);

        ctx.getServiceRegistry().update(def);
        showInfo("설정이 저장되었습니다.");
    }

    // =========================================================
    // Install tab
    // =========================================================

    private void refreshInstall() {
        if (selectedInstance == null) return;
        ServiceDefinition def = selectedInstance.getDefinition();

        installDirLabel.setText(def.getInstallDir() != null ? def.getInstallDir() : "-");
        workingDirLabel.setText(def.getWorkingDir() != null ? def.getWorkingDir() : "-");

        installLogArea.clear();
        progressBar.setProgress(0);
        boolean installed = ctx.getInstallationService().isInstalled(def);
        installBtn.setDisable(installed);
        uninstallBtn.setDisable(!installed);
    }

    @FXML
    private void onInstall() {
        if (selectedInstance == null) return;
        ServiceDefinition def = selectedInstance.getDefinition();

        // repoUrl 없는 JAVA 서비스 → JAR 파일 직접 선택/복사 플로우
        boolean isJarOnly = def.getRuntimeType() == RuntimeType.JAVA
                && (def.getRepoUrl() == null || def.getRepoUrl().isBlank());
        if (isJarOnly) {
            handleJarInstall(def);
            return;
        }

        // 일반 플로우: git clone + installCommands
        installLogArea.clear();
        progressBar.setProgress(-1);
        installBtn.setDisable(true);

        ctx.getInstallationService().install(def, selectedInstance,
                new InstallationService.ProgressCallback() {
                    @Override public void onLog(String message) {
                        Platform.runLater(() -> installLogArea.appendText(message + "\n"));
                    }
                    @Override public void onDone(boolean success) {
                        Platform.runLater(() -> {
                            progressBar.setProgress(success ? 1.0 : 0);
                            installBtn.setDisable(success);
                            uninstallBtn.setDisable(!success);
                            if (success && selectedInstance != null)
                                selectedInstance.setStatus(ServiceStatus.INSTALLED);
                        });
                    }
                });
    }

    /**
     * repoUrl 없는 JAVA 서비스 전용 설치 플로우.
     * lib/ 폴더에 번들된 JAR이 있으면 자동 복사하고, 없으면 파일 선택 다이얼로그를 연다.
     *
     * <ul>
     *   <li>installDir 미설정 → 오류 안내</li>
     *   <li>JAR 이미 존재 → "이미 설치됨" 안내</li>
     *   <li>lib/ 번들 JAR 발견 → 자동 복사 (파일 선택 불필요)</li>
     *   <li>번들 JAR 없음 → 파일 선택 → installDir 생성 → 복사</li>
     * </ul>
     *
     * @param def 설치할 서비스 정의
     */
    private void handleJarInstall(ServiceDefinition def) {
        if (def.getInstallDir() == null || def.getInstallDir().isBlank()) {
            installLogArea.appendText("오류: 설치 경로가 설정되지 않았습니다.\n"
                    + "서비스 수정(더블클릭 → 수정)에서 설치 경로를 지정해 주세요.\n");
            return;
        }

        Path installDir = Path.of(def.getInstallDir());

        // 이미 설치된 경우
        if (ctx.getInstallationService().isInstalled(def)) {
            installLogArea.clear();
            installLogArea.appendText("이미 설치되어 있습니다.\n경로: " + installDir + "\n");
            installBtn.setDisable(true);
            uninstallBtn.setDisable(false);
            return;
        }

        // lib/ 폴더에 번들 JAR 탐색 → 있으면 파일 선택 없이 바로 복사
        File sourceJar = findBundledJar(def);
        if (sourceJar == null) {
            // 번들 JAR 없음: 파일 선택 다이얼로그
            FileChooser chooser = new FileChooser();
            chooser.setTitle("설치할 JAR 파일 선택");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("JAR 파일", "*.jar"));
            File initDir = installDir.toFile().exists()
                    ? installDir.toFile()
                    : new File(System.getProperty("user.home"));
            chooser.setInitialDirectory(initDir);
            sourceJar = chooser.showOpenDialog((Stage) menuBar.getScene().getWindow());
            if (sourceJar == null) return;   // 취소
        }

        final File jar = sourceJar;
        installLogArea.clear();
        progressBar.setProgress(-1);
        installBtn.setDisable(true);

        new Thread(() -> {
            try {
                // installDir 없으면 생성
                if (!installDir.toFile().exists()) {
                    Files.createDirectories(installDir);
                    Platform.runLater(() -> installLogArea.appendText(
                            "디렉토리 생성: " + installDir + "\n"));
                }

                // JAR 복사
                Path dest = installDir.resolve(jar.getName());
                Platform.runLater(() -> installLogArea.appendText(
                        "복사 중: " + jar.getAbsolutePath() + "\n"
                        + "   →  " + dest + "\n"));
                Files.copy(jar.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);

                Platform.runLater(() -> {
                    installLogArea.appendText("설치 완료.\n");
                    progressBar.setProgress(1.0);
                    installBtn.setDisable(true);
                    uninstallBtn.setDisable(false);
                    if (selectedInstance != null)
                        selectedInstance.setStatus(ServiceStatus.INSTALLED);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    installLogArea.appendText("오류: " + e.getMessage() + "\n");
                    progressBar.setProgress(0);
                    installBtn.setDisable(false);
                });
            }
        }, "jar-install-" + def.getName()).start();
    }

    /**
     * 서비스의 startCommand에서 JAR 파일명을 추출해 번들 lib/ 폴더에서 파일을 탐색한다.
     *
     * <p>탐색 순서:
     * <ol>
     *   <li>배포 환경: 실행 중인 메인 JAR와 같은 디렉토리(app/lib/)에서 탐색</li>
     *   <li>개발 환경 fallback: CWD 기준 lib/ 폴더</li>
     * </ol>
     *
     * @param def 서비스 정의
     * @return 번들 lib/ 에 존재하는 JAR File, 없으면 null
     */
    private File findBundledJar(ServiceDefinition def) {
        String cmd = def.getStartCommand();
        if (cmd == null || cmd.isBlank()) return null;

        List<String> tokens = CommandBuilder.splitCommand(cmd);
        for (int i = 0; i < tokens.size() - 1; i++) {
            if ("-jar".equalsIgnoreCase(tokens.get(i))) {
                String jarName = tokens.get(i + 1);
                // 배포 환경: 메인 JAR가 위치한 lib/ 디렉토리에서 탐색
                try {
                    Path codeLoc = Path.of(MainController.class
                            .getProtectionDomain().getCodeSource().getLocation().toURI());
                    if (Files.isRegularFile(codeLoc)) {
                        File candidate = codeLoc.getParent().resolve(jarName).toFile();
                        if (candidate.exists()) return candidate;
                    }
                } catch (Exception ignored) {}
                // 개발 환경 fallback: CWD 기준 lib/
                File bundled = Path.of("lib", jarName).toFile();
                if (bundled.exists()) return bundled;
                break;
            }
        }
        return null;
    }

    @FXML
    private void onUninstall() {
        if (selectedInstance == null) return;
        ServiceDefinition def = selectedInstance.getDefinition();

        // 서비스가 실행 중이면 먼저 경고 확인
        if (selectedInstance.getStatus() == ServiceStatus.RUNNING
                || selectedInstance.getStatus() == ServiceStatus.STARTING) {
            Alert warn = new Alert(Alert.AlertType.WARNING,
                    "서비스가 현재 실행 중입니다.\n중지 후 제거하는 것을 권장합니다.\n\n그래도 제거하시겠습니까?",
                    ButtonType.YES, ButtonType.NO);
            warn.setTitle("실행 중 제거 경고");
            Optional<ButtonType> result = warn.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.YES) return;
        }

        // installDir이 있으면 런타임 타입과 무관하게 디렉토리 삭제 여부 확인
        if (def.getInstallDir() != null) {
            Path installDir = Path.of(def.getInstallDir());
            if (installDir.toFile().exists()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "설치 디렉토리를 삭제하시겠습니까?\n" + installDir,
                        ButtonType.YES, ButtonType.NO);
                confirm.setTitle("제거 확인");
                confirm.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.YES) {
                        new Thread(() -> {
                            try {
                                deleteDirectory(installDir);
                                Platform.runLater(() -> {
                                    installLogArea.appendText("삭제 완료: " + installDir + "\n");
                                    markUninstalled();
                                });
                            } catch (Exception e) {
                                Platform.runLater(() ->
                                        installLogArea.appendText("삭제 오류: " + e.getMessage() + "\n"));
                            }
                        }, "uninstall-" + def.getName()).start();
                        return;
                    }
                    // 아니오: 파일은 남기고 상태만 변경
                    markUninstalled();
                });
                return;
            }
        }

        markUninstalled();
    }

    /** 설치 탭 UI를 미설치 상태로 초기화한다. */
    private void markUninstalled() {
        if (selectedInstance != null)
            selectedInstance.setStatus(ServiceStatus.NOT_INSTALLED);
        installBtn.setDisable(false);
        uninstallBtn.setDisable(true);
        installLogArea.appendText("제거되었습니다.\n");
    }

    /**
     * 디렉토리와 하위 파일을 재귀 삭제한다.
     * Windows에서 .git 내부 파일이 읽기 전용인 경우 쓰기 가능으로 변경 후 삭제한다.
     *
     * @param dir 삭제할 디렉토리 경로
     * @throws Exception 삭제 실패 시
     */
    private void deleteDirectory(Path dir) throws Exception {
        if (!dir.toFile().exists()) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> {
                      try {
                          // 읽기 전용 파일(Windows .git 내부 등)은 쓰기 허용 후 삭제
                          p.toFile().setWritable(true);
                          Files.delete(p);
                      } catch (Exception e) {
                          installLogArea.appendText("삭제 실패: " + p + " (" + e.getMessage() + ")\n");
                      }
                  });
        }
    }

    // =========================================================
    // Toolbar actions
    // =========================================================

    /**
     * 기본 제공 서비스가 있으면 선택 다이얼로그를 먼저 표시하고, 그 결과로 추가 폼을 연다.
     */
    @FXML
    private void onAddService() {
        Stage owner = (Stage) menuBar.getScene().getWindow();

        // 기본 제공 서비스가 있으면 선택 다이얼로그 먼저 표시
        List<org.kyj.llmmanager.model.ServiceDefinition> builtins =
                ctx.getServicePackLoader().loadAll();

        ServiceDefinition prefill = null;
        if (!builtins.isEmpty()) {
            try {
                javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                        getClass().getResource("/org/kyj/llmmanager/builtin-services.fxml"));
                Stage builtinStage = new Stage();
                builtinStage.setTitle("서비스 추가");
                builtinStage.setScene(SceneFactory.create(loader.load(), 720));
                builtinStage.initOwner(owner);
                builtinStage.initModality(javafx.stage.Modality.WINDOW_MODAL);
                SceneFactory.autoHeight(builtinStage);

                BuiltinServicesController ctrl = loader.getController();
                ctrl.setItems(builtins);
                builtinStage.showAndWait();

                if (ctrl.isManualRequested()) {
                    // 직접 입력: 빈 폼 열기
                } else if (ctrl.getSelected() != null) {
                    prefill = ctrl.getSelected();
                } else {
                    return; // 취소
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        AddServiceDialog dialog = new AddServiceDialog(owner, prefill);
        dialog.showAndWait().ifPresent(def -> {
            ctx.getServiceRegistry().add(def);
            ServiceInstance inst = ctx.getProcessManager().getOrCreate(def);
            instanceList.add(inst);
            serviceListView.getSelectionModel().selectLast();
            updateStatusBar();
        });
    }

    @FXML
    private void onSettings() {
        new SettingsDialog((Stage) menuBar.getScene().getWindow()).showAndWait();
    }

    /**
     * GitHub Releases를 조회해 새 버전이 있으면 UpdateInstallDialog를 표시한다.
     * 조회는 백그라운드 스레드에서 실행하고 결과는 FX 스레드에서 처리한다.
     */
    @FXML
    private void onCheckUpdate() {
        Stage owner = (Stage) menuBar.getScene().getWindow();
        UpdateChecker checker = UpdateChecker.fromProperties();

        Task<java.util.Optional<UpdateChecker.UpdateInfo>> task = new Task<>() {
            @Override
            protected java.util.Optional<UpdateChecker.UpdateInfo> call() throws Exception {
                return checker.checkForUpdate();
            }
        };
        task.setOnSucceeded(e -> {
            java.util.Optional<UpdateChecker.UpdateInfo> info = task.getValue();
            if (info.isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION,
                        "현재 최신 버전입니다 (v" + checker.getCurrentVersion() + ").").showAndWait();
            } else {
                new UpdateInstallDialog(owner, info.get(), checker.getCurrentVersion()).show();
            }
        });
        task.setOnFailed(e ->
                new Alert(Alert.AlertType.ERROR,
                        "업데이트 확인 실패: " + task.getException().getMessage()).showAndWait());

        new Thread(task, "update-checker").start();
    }

    /**
     * 환경 체크리스트 다이얼로그를 열어 필요 라이브러리 설치 상태 확인·설치를 수행한다.
     * 설치 스크립트가 PowerShell 기반이라 Windows 외 OS에서는 안내만 표시한다.
     */
    @FXML
    private void onSetupCheck() {
        if (!PlatformUtil.isWindows()) {
            new Alert(Alert.AlertType.INFORMATION, "필요 라이브러리 설치는 Windows에서만 지원됩니다.").showAndWait();
            return;
        }
        new SetupCheckDialog().showAndWait();
    }

    @FXML
    private void onPlugins() {
        new PluginManagerDialog((Stage) menuBar.getScene().getWindow()).show();
    }

    @FXML
    private void onRunPluginCommand() {
        new PluginCommandRunDialog((Stage) menuBar.getScene().getWindow()).show();
    }

    /**
     * 플러그인 메뉴를 현재 로드된 플러그인 command로 재구성한다.
     * 플러그인이 하나면 command를 메뉴에 바로 나열하고,
     * 둘 이상이면 플러그인별 서브메뉴로 묶는다. 고정 항목(실행·관리)은 항상 하단에 유지.
     */
    private void rebuildPluginMenu() {
        List<PluginManager.PluginCommandContribution> commands =
                ctx != null && ctx.getPluginManager() != null
                        ? ctx.getPluginManager().getCommands() : List.of();

        // getCommands()가 플러그인명 순으로 정렬해 반환하므로 LinkedHashMap으로 순서 유지
        Map<String, List<PluginManager.PluginCommandContribution>> byPlugin = new LinkedHashMap<>();
        for (PluginManager.PluginCommandContribution c : commands) {
            byPlugin.computeIfAbsent(c.pluginName(), k -> new ArrayList<>()).add(c);
        }

        List<MenuItem> items = new ArrayList<>();
        if (byPlugin.size() == 1) {
            for (PluginManager.PluginCommandContribution c : commands) {
                items.add(pluginCommandMenuItem(c));
            }
        } else {
            for (Map.Entry<String, List<PluginManager.PluginCommandContribution>> e : byPlugin.entrySet()) {
                Menu sub = new Menu(e.getKey());
                for (PluginManager.PluginCommandContribution c : e.getValue()) {
                    sub.getItems().add(pluginCommandMenuItem(c));
                }
                items.add(sub);
            }
        }
        if (!items.isEmpty()) items.add(new SeparatorMenuItem());
        items.addAll(pluginMenuStaticItems);
        pluginMenu.getItems().setAll(items);
    }

    /**
     * 플러그인 command 하나를 실행 다이얼로그(해당 command 선택 상태)로 여는 메뉴 항목을 만든다.
     *
     * @param c 메뉴 항목으로 만들 command 기여 정보
     * @return command 제목을 라벨로 갖는 메뉴 항목
     */
    private MenuItem pluginCommandMenuItem(PluginManager.PluginCommandContribution c) {
        MenuItem item = new MenuItem(c.command().getTitle());
        // Cursor Agent는 전용 CLI 세션 UX(디렉토리 선택 → 대화 화면)로 실행
        if ("cursor.runAgent".equals(c.command().getId())) {
            item.setOnAction(e -> new CursorAgentDialog(
                    (Stage) menuBar.getScene().getWindow(), c).show());
        } else if ("wiki.query".equals(c.command().getId())) {
            // 위키 질의는 과거 문답 이력이 쌓이는 CLI 세션 UX로 실행
            item.setOnAction(e -> new WikiQueryDialog(
                    (Stage) menuBar.getScene().getWindow(), c).show());
        } else if ("wiki.ingest".equals(c.command().getId())) {
            // 문서 수집은 파일 멀티 선택 + raw/ 분류 복사 전용 UX로 실행
            item.setOnAction(e -> new WikiIngestDialog(
                    (Stage) menuBar.getScene().getWindow(), c).show());
        } else if ("wiki.browse".equals(c.command().getId())) {
            // 위키 브라우저는 트리 + 마크다운 뷰 전용 창으로 실행
            item.setOnAction(e -> new WikiBrowserDialog(
                    (Stage) menuBar.getScene().getWindow()).show());
        } else {
            item.setOnAction(e -> new PluginCommandRunDialog((Stage) menuBar.getScene().getWindow())
                    .show(c.command().getId()));
        }
        return item;
    }

    /**
     * 서비스별 마크다운 도움말을 WebView 다이얼로그로 표시한다.
     */
    @FXML
    private void onHelp() {
        new HelpDialog((Stage) menuBar.getScene().getWindow()).show();
    }

    /**
     * 내장 API 서버의 Swagger UI를 기본 브라우저로 연다.
     * API 서버가 비활성이면 안내 메시지를 표시한다.
     */
    @FXML
    private void onOpenApiServer() {
        var apiServer = ctx.getApiServer();
        if (!apiServer.isRunning()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "API 서버가 실행 중이 아닙니다.\n설정 → 환경설정에서 'API 서버 활성화'를 켜 주세요.",
                    ButtonType.OK).showAndWait();
            return;
        }
        String host = apiServer.getHost();
        String browserHost = host == null || host.isBlank() || "0.0.0.0".equals(host.trim())
                ? "localhost" : host.trim();
        String url = "http://" + browserHost + ":" + apiServer.getPort() + "/swagger-ui";
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR,
                    "브라우저를 열 수 없습니다:\n" + url, ButtonType.OK).showAndWait();
        }
    }

    /** 서비스 선택을 해제하고 대시보드 홈 뷰로 돌아간다. */
    @FXML
    private void onDashboard() {
        serviceListView.getSelectionModel().clearSelection();
        selectedInstance = null;
        showDashboard();
    }

    @FXML
    private void onLlmSkillsInstall() {
        openLlmSkills(false);
    }

    @FXML
    private void onLlmSkillsLoad() {
        openLlmSkills(true);
    }

    private void openLlmSkills(boolean loadTab) {
        try {
            FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/org/kyj/llmmanager/llm-skills.fxml"));
            Stage stage = new Stage();
            stage.setTitle("LLM 스킬 & 룰 설치");
            stage.setScene(SceneFactory.create(loader.load(), 1240, 760));
            LlmSkillsController controller = loader.getController();
            if (loadTab) controller.selectLoadTab();
            else controller.selectInstallTab();
            stage.setMinWidth(1120);
            stage.setMinHeight(680);
            stage.initOwner(menuBar.getScene().getWindow());
            stage.initModality(Modality.NONE);
            stage.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void onRefresh() {
        loadServices();
        showDashboard();  // 새로고침 후 대시보드로 복귀
    }

    /**
     * 확인 후 선택 서비스를 중지하고 Registry에서 제거한다. 메뉴/단축키 진입점.
     */
    @FXML
    private void onDeleteService() {
        if (selectedInstance == null) return;
        removeService(selectedInstance);
    }

    /**
     * 확인 다이얼로그 후 서비스를 중지·Registry 제거·UI 갱신한다.
     * 우클릭 컨텍스트 메뉴와 메뉴바 Delete 키 양쪽에서 호출된다.
     *
     * @param inst 제거할 서비스 인스턴스
     */
    private void removeService(ServiceInstance inst) {
        if (inst == null) return;
        ServiceDefinition def = inst.getDefinition();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "'" + def.getName() + "' 서비스를 목록에서 제거하시겠습니까?",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("서비스 제거");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn != ButtonType.YES) return;
            if (inst.getStatus() == ServiceStatus.RUNNING) {
                ctx.getProcessManager().stop(inst);
            }
            ctx.getServiceRegistry().remove(def.getId());
            ctx.getProcessManager().remove(def.getId());
            instanceList.remove(inst);
            if (inst == selectedInstance) {
                detachSelectedServiceListeners();
                selectedInstance = null;
                showDashboard();
            }
            updateStatusBar();
        });
    }

    // =========================================================
    // Helpers
    // =========================================================

    // =========================================================
    // 대시보드 / 상세 뷰 전환
    // =========================================================

    /** 대시보드 홈 뷰를 표시하고 서비스 카드를 빌드한다. */
    private void showDashboard() {
        detailHeader.setVisible(false);
        detailHeader.setManaged(false);
        dashboardPane.setVisible(true);
        dashboardPane.setManaged(true);
        detailTabPane.setVisible(false);
        detailTabPane.setManaged(false);
        buildDashboardCards();
    }

    /** 서비스 상세 뷰를 표시한다. 선택된 서비스가 있을 때 호출. */
    private void showDetail() {
        detailHeader.setVisible(true);
        detailHeader.setManaged(true);
        dashboardPane.setVisible(false);
        dashboardPane.setManaged(false);
        detailTabPane.setVisible(true);
        detailTabPane.setManaged(true);
    }

    /**
     * 현재 서비스 목록을 기반으로 대시보드 카드를 생성한다.
     * statusProperty 리스너로 상태 변경 시 카드 내 색상·버튼을 실시간 갱신한다.
     */
    private void buildDashboardCards() {
        clearDashboardCardListeners();
        dashboardCardPane.getChildren().clear();
        List<ServiceInstance> instances = ctx.getProcessManager().getAllInstances();
        for (ServiceInstance inst : instances) {
            dashboardCardPane.getChildren().add(createServiceCard(inst));
        }
        updateDashboardSummary(instances);
    }

    /** 이전 대시보드 카드가 등록한 리스너를 해제한다. */
    private void clearDashboardCardListeners() {
        dashboardListenerCleanups.forEach(Runnable::run);
        dashboardListenerCleanups.clear();
    }

    /**
     * 서비스 인스턴스 하나에 대한 카드 노드를 생성한다.
     *
     * @param inst 카드를 만들 서비스 인스턴스
     * @return 완성된 카드 VBox
     */
    private javafx.scene.Node createServiceCard(ServiceInstance inst) {
        // 상태 점
        Circle dot = new Circle(6);
        applyCardDotColor(dot, inst.getStatus());

        // 서비스 이름 — 길면 말줄임표
        Label nameLabel = new Label(inst.getDefinition().getName());
        nameLabel.getStyleClass().add("card-name");
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        nameLabel.setMaxWidth(160);

        javafx.scene.layout.HBox nameRow = new javafx.scene.layout.HBox(8, dot, nameLabel);
        nameRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // 상태 텍스트
        Label statusLabel = new Label(inst.getStatus().getLabel());
        statusLabel.getStyleClass().add("card-status");

        // 포트
        Label portLabel = new Label(
                inst.getDefinition().getPort() != null ? "Port: " + inst.getDefinition().getPort() : "");
        portLabel.getStyleClass().add("card-port");

        // 설명 — 한 줄, 넘치면 말줄임표
        Label descLabel = new Label(
                inst.getDefinition().getDescription() != null ? inst.getDefinition().getDescription() : "");
        descLabel.getStyleClass().add("arg-desc");
        descLabel.setWrapText(false);
        descLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        descLabel.setMaxWidth(Double.MAX_VALUE);

        // 시작/중지 버튼
        Button actionBtn = new Button(cardActionLabel(inst.getStatus()));
        actionBtn.setStyle(cardActionStyle(inst.getStatus()));
        actionBtn.setMaxWidth(Double.MAX_VALUE);
        actionBtn.setOnAction(e -> {
            if (inst.getStatus() == ServiceStatus.RUNNING) {
                ctx.getProcessManager().stop(inst);
            } else if (inst.getStatus() == ServiceStatus.STOPPED
                    || inst.getStatus() == ServiceStatus.INSTALLED
                    || inst.getStatus() == ServiceStatus.ERROR) {
                ctx.getProcessManager().start(inst);
            }
        });

        // 상세 보기 버튼
        Button detailBtn = new Button("상세 보기");
        detailBtn.setStyle("-fx-font-size: 10px;");
        detailBtn.setMaxWidth(Double.MAX_VALUE);
        detailBtn.setOnAction(e -> {
            serviceListView.getSelectionModel().select(inst);
        });

        // ── 메모리 ProgressBar + 수치 레이블 ──
        ProgressBar memBar = new ProgressBar(0);
        memBar.setMaxWidth(Double.MAX_VALUE);
        memBar.setPrefHeight(8);
        memBar.setStyle("-fx-accent: #5588bb;");

        Label memDetailLabel = new Label();
        memDetailLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #8888bb;");

        Canvas sparkline = new Canvas(182, 44);

        VBox memSection = new VBox(3, memBar, memDetailLabel, sparkline);
        // 항상 공간 유지 — 미실행 시 플레이스홀더로 표시
        resetCardMem(memBar, memDetailLabel, sparkline);

        Runnable updateMem = () -> {
            long rss     = inst.getMemoryBytes();
            long virtual = inst.getVirtualMemoryBytes();
            if (rss > 0 && inst.getStatus() == ServiceStatus.RUNNING) {
                memBar.setProgress(virtual > 0 ? (double) rss / virtual : 0);
                memBar.setStyle("-fx-accent: #5588bb;");
                memDetailLabel.setText(formatCardMemory(rss) + " / " + formatCardMemory(virtual));
                memDetailLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #8888bb;");
                drawCardSparkline(sparkline, inst.getMemoryHistory());
            } else {
                resetCardMem(memBar, memDetailLabel, sparkline);
            }
        };
        ChangeListener<Number> memoryListener = (obs, o, n) -> updateMem.run();
        inst.memoryBytesProperty().addListener(memoryListener);
        dashboardListenerCleanups.add(() ->
                inst.memoryBytesProperty().removeListener(memoryListener));

        // statusProperty 구독 → 카드 실시간 갱신
        ChangeListener<ServiceStatus> statusListener = (obs, old, newStatus) -> {
            applyCardDotColor(dot, newStatus);
            statusLabel.setText(newStatus.getLabel());
            actionBtn.setText(cardActionLabel(newStatus));
            actionBtn.setStyle(cardActionStyle(newStatus));
            if (newStatus != ServiceStatus.RUNNING) {
                inst.setMemoryBytes(0);
                resetCardMem(memBar, memDetailLabel, sparkline);
            }
            updateDashboardSummary(ctx.getProcessManager().getAllInstances());
        };
        inst.statusProperty().addListener(statusListener);
        dashboardListenerCleanups.add(() ->
                inst.statusProperty().removeListener(statusListener));

        // 스페이서 — 설명과 버튼 사이 여백을 채워 버튼 위치를 카드 하단에 고정
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox card = new VBox(8, nameRow, statusLabel, portLabel, memSection, descLabel,
                spacer, new Separator(), actionBtn, detailBtn);
        card.setPadding(new Insets(14));
        card.setPrefWidth(230);
        card.setMaxWidth(230);
        card.getStyleClass().add("service-card");

        // 카드 클릭 시 메모리 상세 팝업
        card.setOnMouseClicked(e -> showCardMemoryPopup(inst));
        card.setStyle(card.getStyle() + "-fx-cursor: hand;");

        return card;
    }

    private void applyCardDotColor(Circle dot, ServiceStatus status) {
        try { dot.setFill(Color.web(status.getColor())); }
        catch (Exception ignored) { dot.setFill(Color.GRAY); }
    }

    private String cardActionLabel(ServiceStatus status) {
        return status == ServiceStatus.RUNNING ? "■  중지" : "▶  시작";
    }

    private String cardActionStyle(ServiceStatus status) {
        return status == ServiceStatus.RUNNING
                ? "-fx-background-color:#5a2d2d; -fx-text-fill:#dd8888; -fx-background-radius:4;"
                : "-fx-background-color:#2d5a2d; -fx-text-fill:#88dd88; -fx-background-radius:4;";
    }

    /** 메모리 섹션을 미실행 플레이스홀더 상태로 초기화한다. */
    private void resetCardMem(ProgressBar bar, Label lbl, Canvas canvas) {
        bar.setProgress(0);
        bar.setStyle("-fx-accent: #3a3a4a;");
        lbl.setText("서비스 미실행");
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #444455;");
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setStroke(Color.web("#2a2a3a"));
        gc.setLineWidth(1);
        gc.setLineDashes(4);
        gc.strokeLine(0, canvas.getHeight() / 2, canvas.getWidth(), canvas.getHeight() / 2);
        gc.setLineDashes(0);
    }

    private String formatCardMemory(long bytes) {
        if (bytes <= 0) return "0 MB";
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)        return String.format("%.0f MB", bytes / (1024.0 * 1024));
        return String.format("%.0f KB", bytes / 1024.0);
    }

    private void drawCardSparkline(Canvas canvas, List<Long> history) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);
        if (history.size() < 2) return;
        long max = history.stream().mapToLong(Long::longValue).max().orElse(1);
        if (max == 0) return;
        double xStep = w / (history.size() - 1);
        gc.setFill(Color.web("#5588bb", 0.25));
        gc.beginPath(); gc.moveTo(0, h);
        for (int i = 0; i < history.size(); i++) {
            gc.lineTo(i * xStep, h - h * 0.9 * history.get(i) / max);
        }
        gc.lineTo(w, h); gc.closePath(); gc.fill();
        gc.setStroke(Color.web("#5588bb", 0.85)); gc.setLineWidth(1.5);
        gc.beginPath();
        for (int i = 0; i < history.size(); i++) {
            double x = i * xStep, y = h - h * 0.9 * history.get(i) / max;
            if (i == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
        }
        gc.stroke();
        int last = history.size() - 1;
        double lx = last * xStep, ly = h - h * 0.9 * history.get(last) / max;
        gc.setFill(Color.web("#88bbee")); gc.fillOval(lx - 3, ly - 3, 6, 6);
    }

    /**
     * 카드 클릭 시 서비스 메모리 이력을 확대 그래프로 보여주는 팝업을 연다.
     *
     * @param inst 조회할 서비스 인스턴스
     */
    private void showCardMemoryPopup(ServiceInstance inst) {
        if (inst.getStatus() != ServiceStatus.RUNNING) return;
        Stage popup = new Stage();
        popup.setTitle(inst.getDefinition().getName() + " — 메모리 모니터링");
        popup.initModality(Modality.NONE);

        long rss     = inst.getMemoryBytes();
        long virtual = inst.getVirtualMemoryBytes();

        Label titleLbl = new Label(inst.getDefinition().getName());
        titleLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label rssLbl = new Label("RSS(물리): " + formatCardMemory(rss));
        rssLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #88bbee;");
        Label totalLbl = new Label("가상 할당량: " + formatCardMemory(virtual));
        totalLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");

        ProgressBar bar = new ProgressBar(virtual > 0 ? (double) rss / virtual : 0);
        bar.setMaxWidth(Double.MAX_VALUE); bar.setPrefHeight(12);
        bar.setStyle("-fx-accent: #5588bb;");

        Label pctLbl = new Label(virtual > 0 ? String.format("%.1f%% 상주 중", 100.0 * rss / virtual) : "");
        pctLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");

        Canvas bigChart = new Canvas(460, 160);
        drawCardSparkline(bigChart, inst.getMemoryHistory());

        Label hintLbl = new Label("최근 " + inst.getMemoryHistory().size() + "회 샘플 (10초 간격)");
        hintLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555566;");

        VBox root = new VBox(10, titleLbl, rssLbl, totalLbl, bar, pctLbl,
                new Separator(), bigChart, hintLbl);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1e1e2e;");

        ChangeListener<Number> popupMemoryListener = (obs, o, n) -> {
            if (!popup.isShowing()) return;
            long r = n.longValue();
            long v = inst.getVirtualMemoryBytes();
            rssLbl.setText("RSS(물리): " + formatCardMemory(r));
            totalLbl.setText("가상 할당량: " + formatCardMemory(v));
            bar.setProgress(v > 0 ? (double) r / v : 0);
            pctLbl.setText(v > 0 ? String.format("%.1f%% 상주 중", 100.0 * r / v) : "");
            hintLbl.setText("최근 " + inst.getMemoryHistory().size() + "회 샘플 (10초 간격)");
            drawCardSparkline(bigChart, inst.getMemoryHistory());
        };
        inst.memoryBytesProperty().addListener(popupMemoryListener);
        popup.setOnHidden(e -> inst.memoryBytesProperty().removeListener(popupMemoryListener));

        popup.setScene(new Scene(root, 500, 380));
        popup.show();
    }

    /** 대시보드 상단 요약 레이블을 갱신한다. */
    private void updateDashboardSummary(List<ServiceInstance> instances) {
        long running = instances.stream().filter(i -> i.getStatus() == ServiceStatus.RUNNING).count();
        long error   = instances.stream().filter(i -> i.getStatus() == ServiceStatus.ERROR).count();
        dashTotalLabel  .setText("전체 " + instances.size() + "개");
        dashRunningLabel.setText("실행 중 " + running + "개");
        dashErrorLabel  .setText(error > 0 ? "오류 " + error + "개" : "");
        dashErrorLabel  .setVisible(error > 0);
        dashErrorLabel  .setManaged(error > 0);
    }

    /**
     * 하단 상태 바를 현재 서비스 수와 실행 중 수로 갱신한다.
     */
    private void updateStatusBar() {
        long running = instanceList.stream()
                .filter(i -> i.getStatus() == ServiceStatus.RUNNING).count();
        statusBarLabel.setText("서비스: " + instanceList.size() + "개  |  실행중: " + running + "개");
    }

    // =========================================================
    // 시스템 리소스 패널
    // =========================================================

    /**
     * SystemMonitorService에서 최신 수집값을 읽어 CPU·메모리 게이지와 레이블을 갱신한다.
     * 2초 간격 Timeline에서 JavaFX 스레드로 호출된다.
     */
    private void updateSystemStats() {
        if (ctx == null || cpuBar == null) return;
        SystemMonitorService mon = ctx.getSystemMonitor();
        if (mon == null) return;

        // ── CPU ──────────────────────────────────────────────
        double cpu = Math.max(0, Math.min(1, mon.getCpuLoad()));
        cpuBar.setProgress(cpu);
        cpuBar.setStyle("-fx-accent: " + gaugeColor(cpu) + ";");
        cpuLabel.setText(String.format("%5.1f %%   %d코어 / %d스레드",
                cpu * 100, mon.getPhysicalCores(), mon.getLogicalCores()));

        // ── 물리 메모리 ──────────────────────────────────────
        long usedMem  = mon.getUsedMemory();
        long totalMem = mon.getTotalMemory();
        double memRatio = totalMem > 0 ? (double) usedMem / totalMem : 0;
        memBar.setProgress(memRatio);
        memBar.setStyle("-fx-accent: " + gaugeColor(memRatio) + ";");
        memLabel.setText(String.format("%s / %s  (%4.1f %%)",
                SystemMonitorService.formatBytes(usedMem),
                SystemMonitorService.formatBytes(totalMem),
                memRatio * 100));

        // ── JVM 힙 ───────────────────────────────────────────
        Runtime rt      = Runtime.getRuntime();
        long jvmUsed    = rt.totalMemory() - rt.freeMemory();
        long jvmMax     = rt.maxMemory();
        double jvmRatio = jvmMax > 0 ? (double) jvmUsed / jvmMax : 0;
        jvmBar.setProgress(jvmRatio);
        jvmBar.setStyle("-fx-accent: " + gaugeColor(jvmRatio) + ";");
        jvmLabel.setText(String.format("%s / %s  (%4.1f %%)",
                SystemMonitorService.formatBytes(jvmUsed),
                SystemMonitorService.formatBytes(jvmMax),
                jvmRatio * 100));
    }

    /**
     * 비율에 따른 게이지 색상을 CSS 색상 문자열로 반환한다.
     * 0~49%: 초록, 50~79%: 노랑, 80%+: 빨강
     */
    private String gaugeColor(double ratio) {
        if (ratio < 0.50) return "#44BB44";
        if (ratio < 0.80) return "#FFAA00";
        return "#EE4444";
    }

    /**
     * 서비스 상세 다이얼로그를 열고, 수정 저장 시 UI를 갱신한다.
     *
     * @param inst 상세를 볼 서비스 인스턴스
     */
    private void openDetailDialog(ServiceInstance inst) {
        new ServiceDetailDialog(
                (Stage) menuBar.getScene().getWindow(),
                inst,
                ctx,
                () -> {
                    // 포트·이름 등 정의 변경은 status 리스너에 연결되지 않으므로
                    // 대시보드 가시 여부와 무관하게 항상 카드를 재빌드한다.
                    buildDashboardCards();
                    if (inst.equals(selectedInstance)) refreshDetail();
                }
        ).show();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        alert.showAndWait();
    }
}
