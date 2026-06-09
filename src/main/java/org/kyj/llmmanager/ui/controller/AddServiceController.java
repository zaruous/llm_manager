/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.service.ServiceCustomizer;
import org.kyj.llmmanager.service.ServicePackLoader;
import org.kyj.llmmanager.util.PlatformUtil;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 서비스 추가 다이얼로그 컨트롤러.
 * 직접 입력, 템플릿 선택, YAML 가져오기, Groovy 스크립트 커스터마이징을 지원한다.
 */
public class AddServiceController {

    /** 서비스 이름 입력 필드 */
    @FXML private TextField nameField;
    /** 서비스 설명 입력 필드 */
    @FXML private TextField descriptionField;
    /** 저장소 URL 입력 필드 */
    @FXML private TextField repoUrlField;
    /** 설치 디렉토리 입력 필드 */
    @FXML private TextField installDirField;
    /** 런타임 종류 선택 콤보박스 */
    @FXML private ComboBox<RuntimeType> runtimeCombo;
    /** 시작 명령어 입력 영역 */
    @FXML private TextArea startCommandArea;
    /** 설치 명령어 입력 영역 */
    @FXML private TextArea installCommandsArea;
    /** 서비스 포트 입력 필드 */
    @FXML private TextField portField;
    /** 사전 정의된 서비스 템플릿 선택 콤보박스 */
    @FXML private ComboBox<String> templateCombo;
    /** 서비스 정의를 동적으로 수정할 Groovy 스크립트 입력 영역 */
    @FXML private TextArea groovyScriptArea;

    /** 실행 인수 섹션 VBox (argSpecs 없으면 숨김) */
    @FXML private VBox argSpecsPane;
    /** 실행 인수 행이 추가될 컨테이너 */
    @FXML private VBox argSpecsContainer;

    /** YAML 서비스팩 파일을 로드하는 로더 */
    private final ServicePackLoader packLoader = new ServicePackLoader();
    /** Groovy 스크립트를 실행해 ServiceDefinition을 수정하는 커스터마이저 */
    private final ServiceCustomizer customizer = new ServiceCustomizer();
    /** 사용자가 '추가' 버튼을 누르면 채워지는 최종 ServiceDefinition. 취소 시 null. */
    private ServiceDefinition result;

    /** 템플릿 이름 → ServiceDefinition. lib/def/*.json에서 동적으로 로드. */
    private final Map<String, ServiceDefinition> templateDefs = new LinkedHashMap<>();

    /** 현재 폼에 표시된 ArgSpec 목록 */
    private List<ArgSpec> currentArgSpecs = new ArrayList<>();
    /** argSpec.name → 활성화 체크박스 */
    private final Map<String, CheckBox> argEnabledChecks = new LinkedHashMap<>();
    /** argSpec.name → 값 입력 컨트롤 (TextField / ComboBox / CheckBox) */
    private final Map<String, Control> argValueControls  = new LinkedHashMap<>();

    @FXML
    public void initialize() {
        runtimeCombo.getItems().addAll(RuntimeType.values());
        runtimeCombo.setValue(RuntimeType.PYTHON);

        templateCombo.getItems().add("직접 입력");

        // service-packs/*.yml 에서 서비스 팩 정의를 로드해 템플릿 목록에 추가
        packLoader.loadAll().forEach(def -> {
            if (def.getName() != null) {
                templateDefs.put(def.getName(), def);
                templateCombo.getItems().add(def.getName());
            }
        });

        // 고정 제공 제네릭 템플릿
        templateCombo.getItems().addAll("MCP Server (Node.js)", "Python FastAPI Server");

        templateCombo.setValue("직접 입력");
        templateCombo.setOnAction(e -> applyTemplate(templateCombo.getValue()));

        installDirField.setText(PlatformUtil.getDefaultInstallBase().toString());
    }

    /**
     * YAML 서비스팩 파일을 선택해 폼에 로드한다.
     */
    @FXML
    private void onImportYaml() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("YAML 서비스 팩 파일 선택");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("YAML 파일", "*.yml", "*.yaml"),
                new FileChooser.ExtensionFilter("모든 파일", "*.*")
        );
        Stage stage = (Stage) nameField.getScene().getWindow();
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            ServiceDefinition def = packLoader.load(file);
            populateForm(def);
            templateCombo.setValue("직접 입력");
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "YAML 파일 로드 실패:\n" + e.getMessage(), ButtonType.OK)
                    .showAndWait();
        }
    }

    /**
     * ServiceDefinition의 값을 폼 필드에 채운다.
     *
     * @param def 폼에 채울 ServiceDefinition
     */
    private void populateForm(ServiceDefinition def) {
        if (def.getName() != null)        nameField.setText(def.getName());
        if (def.getDescription() != null) descriptionField.setText(def.getDescription());
        if (def.getRepoUrl() != null)     repoUrlField.setText(def.getRepoUrl());
        if (def.getInstallDir() != null)  installDirField.setText(def.getInstallDir());
        if (def.getRuntimeType() != null) runtimeCombo.setValue(def.getRuntimeType());
        if (def.getStartCommand() != null) startCommandArea.setText(def.getStartCommand());

        if (def.getPort() != null) portField.setText(String.valueOf(def.getPort()));
        else portField.clear();

        if (def.getInstallCommands() != null && !def.getInstallCommands().isEmpty()) {
            installCommandsArea.setText(String.join("\n", def.getInstallCommands()));
        } else {
            installCommandsArea.clear();
        }

        if (def.getGroovyScript() != null) groovyScriptArea.setText(def.getGroovyScript());
        else groovyScriptArea.clear();

        // argSpecs 섹션 구성
        buildArgSpecsForm(def.getArgSpecs(), def.getArgValues());
    }

    /**
     * ArgSpec 목록으로 실행 인수 입력 폼을 동적으로 생성한다.
     * 각 행: [☑ 활성] [플래그명] [값 입력] [설명]
     *
     * @param specs     표시할 ArgSpec 목록
     * @param argValues 현재 저장된 값 맵 (없으면 defaultValue 사용)
     */
    private void buildArgSpecsForm(List<ArgSpec> specs, Map<String, String> argValues) {
        // argSpecsPane/argSpecsContainer가 null이면 FXML 주입 실패 → 무시
        if (argSpecsPane == null || argSpecsContainer == null) return;

        argSpecsContainer.getChildren().clear();
        argEnabledChecks.clear();
        argValueControls.clear();
        currentArgSpecs = new ArrayList<>(specs != null ? specs : List.of());

        boolean hasSpecs = !currentArgSpecs.isEmpty();
        argSpecsPane.setVisible(hasSpecs);
        argSpecsPane.setManaged(hasSpecs);
        if (!hasSpecs) return;

        // GridPane: [체크] [플래그] [값] [설명]
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.setPadding(new Insets(6, 4, 4, 4));

        ColumnConstraints chkCol  = new ColumnConstraints(24);
        ColumnConstraints flagCol = new ColumnConstraints(190, 190, 190);
        flagCol.setHalignment(HPos.LEFT);
        ColumnConstraints valCol  = new ColumnConstraints(120, 160, Double.MAX_VALUE);
        valCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints descCol = new ColumnConstraints(120, 160, Double.MAX_VALUE);
        descCol.setHgrow(Priority.SOMETIMES);
        descCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(chkCol, flagCol, valCol, descCol);

        int row = 0;
        for (ArgSpec spec : currentArgSpecs) {
            // 활성화 체크박스
            CheckBox enabledCb = new CheckBox();
            enabledCb.setSelected(spec.isEnabled());
            argEnabledChecks.put(spec.getName(), enabledCb);

            // 플래그 레이블
            Label flagLbl = new Label(spec.getFlag());
            flagLbl.getStyleClass().add("arg-flag");
            flagLbl.setWrapText(false);

            // 값 입력 컨트롤
            String curVal = argValues != null
                    ? argValues.getOrDefault(spec.getName(), spec.getDefaultValue())
                    : spec.getDefaultValue();
            Control valCtrl = buildArgControl(spec, curVal);
            argValueControls.put(spec.getName(), valCtrl);

            // 설명
            Label descLbl = new Label(spec.getDescription() != null ? spec.getDescription() : "");
            descLbl.getStyleClass().add("arg-desc");
            descLbl.setWrapText(true);
            descLbl.setMaxWidth(Double.MAX_VALUE);

            // 비활성 시 값 컨트롤 흐리게
            valCtrl.setStyle(spec.isEnabled() ? "" : "-fx-opacity: 0.5;");
            enabledCb.selectedProperty().addListener((obs, old, v) ->
                valCtrl.setStyle(v ? "" : "-fx-opacity: 0.5;"));

            grid.add(enabledCb, 0, row);
            grid.add(flagLbl,   1, row);
            grid.add(valCtrl,   2, row);
            grid.add(descLbl,   3, row);
            GridPane.setValignment(enabledCb, javafx.geometry.VPos.CENTER);
            row++;
        }

        argSpecsContainer.getChildren().add(grid);
    }

    /**
     * ArgSpec 타입에 맞는 입력 컨트롤을 생성한다.
     *
     * @param spec   인수 명세
     * @param curVal 현재 값 (없으면 defaultValue)
     * @return TextField(STRING/INTEGER), ComboBox(SELECT), CheckBox(BOOLEAN)
     */
    private Control buildArgControl(ArgSpec spec, String curVal) {
        String val = curVal != null ? curVal : "";
        return switch (spec.getType()) {
            case "SELECT" -> {
                ComboBox<String> cb = new ComboBox<>();
                if (spec.getOptions() != null) cb.getItems().addAll(spec.getOptions());
                cb.setValue(val.isBlank() && !cb.getItems().isEmpty() ? cb.getItems().get(0) : val);
                cb.setMaxWidth(Double.MAX_VALUE);
                yield cb;
            }
            case "BOOLEAN" -> {
                CheckBox cb = new CheckBox();
                cb.setSelected("true".equalsIgnoreCase(val));
                yield cb;
            }
            default -> {
                TextField tf = new TextField(val);
                tf.setPromptText(spec.getDefaultValue() != null ? spec.getDefaultValue() : "");
                tf.setMaxWidth(Double.MAX_VALUE);
                yield tf;
            }
        };
    }

    /**
     * 입력 컨트롤에서 현재 값을 문자열로 반환한다.
     */
    @SuppressWarnings("unchecked")
    private String getArgControlValue(Control ctrl) {
        if (ctrl instanceof TextField tf)          return tf.getText().trim();
        if (ctrl instanceof ComboBox<?> cb)        return cb.getValue() != null ? cb.getValue().toString() : "";
        if (ctrl instanceof CheckBox cb)           return String.valueOf(cb.isSelected());
        return "";
    }

    /**
     * Groovy 스크립트를 임시 적용해 결과를 미리보기 다이얼로그로 표시한다.
     */
    @FXML
    private void onPreviewGroovy() {
        String script = groovyScriptArea.getText();
        if (script.isBlank()) {
            new Alert(Alert.AlertType.INFORMATION, "Groovy 스크립트가 비어 있습니다.", ButtonType.OK)
                    .showAndWait();
            return;
        }

        ServiceDefinition def = buildDefinition();
        try {
            customizer.apply(def, script);

            TextArea ta = new TextArea(buildPreviewText(def));
            ta.setEditable(false);
            ta.setPrefSize(500, 360);
            ta.setStyle("-fx-font-family: monospace; -fx-font-size: 12;");

            Dialog<Void> dlg = new Dialog<>();
            dlg.setTitle("Groovy 적용 결과 미리보기");
            dlg.getDialogPane().setContent(ta);
            dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dlg.initOwner(nameField.getScene().getWindow());
            dlg.showAndWait();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "스크립트 실행 오류:\n" + e.getMessage(), ButtonType.OK)
                    .showAndWait();
        }
    }

    private String buildPreviewText(ServiceDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append("name        : ").append(def.getName()).append("\n");
        sb.append("description : ").append(def.getDescription()).append("\n");
        sb.append("runtime     : ").append(def.getRuntimeType()).append("\n");
        sb.append("repoUrl     : ").append(def.getRepoUrl()).append("\n");
        sb.append("installDir  : ").append(def.getInstallDir()).append("\n");
        sb.append("workingDir  : ").append(def.getWorkingDir()).append("\n");
        sb.append("startCommand: ").append(def.getStartCommand()).append("\n");
        sb.append("port        : ").append(def.getPort()).append("\n");
        if (!def.getInstallCommands().isEmpty()) {
            sb.append("installCmds :\n");
            def.getInstallCommands().forEach(c -> sb.append("  - ").append(c).append("\n"));
        }
        if (!def.getEnvVars().isEmpty()) {
            sb.append("envVars     :\n");
            def.getEnvVars().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        }
        if (!def.getArgSpecs().isEmpty()) {
            sb.append("args        :\n");
            def.getArgSpecs().forEach(a -> sb.append("  ").append(a.getFlag())
                    .append(" (").append(a.getType()).append(") default=").append(a.getDefaultValue()).append("\n"));
        }
        return sb.toString();
    }

    /**
     * 선택한 템플릿에 맞게 폼 필드를 자동으로 채운다.
     * lib/def 에서 로드된 builtin 템플릿은 populateForm()으로 처리하고,
     * 제네릭 템플릿은 직접 필드를 세팅한다.
     *
     * @param template 선택한 템플릿 이름
     */
    private void applyTemplate(String template) {
        if (template == null || "직접 입력".equals(template)) return;

        // lib/def 에서 로드된 builtin 정의가 있으면 폼에 채우고 종료
        ServiceDefinition builtin = templateDefs.get(template);
        if (builtin != null) {
            populateForm(builtin);
            return;
        }

        // 제네릭 템플릿 (파일 없이 하드코딩)
        switch (template) {
            case "MCP Server (Node.js)" -> {
                nameField.setText("MCP Server");
                descriptionField.setText("Model Context Protocol server");
                runtimeCombo.setValue(RuntimeType.NODE);
                startCommandArea.setText("node index.js");
                installCommandsArea.setText("npm install");
                portField.clear();
                groovyScriptArea.clear();
            }
            case "Python FastAPI Server" -> {
                nameField.setText("Python Server");
                descriptionField.setText("Python FastAPI application");
                runtimeCombo.setValue(RuntimeType.PYTHON);
                startCommandArea.setText("python main.py");
                installCommandsArea.setText("pip install -r requirements.txt");
                portField.clear();
                groovyScriptArea.clear();
            }
            default -> { }
        }
    }

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("설치 디렉토리 선택");
        File currentDir = new File(installDirField.getText());
        if (currentDir.exists()) chooser.setInitialDirectory(currentDir);
        Stage stage = (Stage) installDirField.getScene().getWindow();
        File dir = chooser.showDialog(stage);
        if (dir != null) installDirField.setText(dir.getAbsolutePath());
    }

    /**
     * 유효성 검사 → Groovy 적용 → result 저장 → 다이얼로그 닫기.
     */
    @FXML
    private void onConfirm() {
        if (nameField.getText().isBlank() || startCommandArea.getText().isBlank()) {
            new Alert(Alert.AlertType.WARNING, "이름과 시작 명령어는 필수입니다.", ButtonType.OK)
                    .showAndWait();
            return;
        }

        ServiceDefinition def = buildDefinition();

        String script = groovyScriptArea.getText();
        if (!script.isBlank()) {
            try {
                customizer.apply(def, script);
            } catch (Exception e) {
                new Alert(Alert.AlertType.ERROR, "Groovy 스크립트 오류:\n" + e.getMessage(), ButtonType.OK)
                        .showAndWait();
                return;
            }
        }

        result = def;
        ((Stage) nameField.getScene().getWindow()).close();
    }

    @FXML
    private void onCancel() {
        ((Stage) nameField.getScene().getWindow()).close();
    }

    /**
     * 현재 폼 입력 값으로 ServiceDefinition 객체를 생성해 반환한다.
     *
     * @return 폼 입력 값으로 구성된 ServiceDefinition
     */
    private ServiceDefinition buildDefinition() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(nameField.getText().trim());
        def.setDescription(descriptionField.getText().trim());
        def.setRepoUrl(repoUrlField.getText().trim());
        def.setInstallDir(installDirField.getText().trim());
        def.setRuntimeType(runtimeCombo.getValue());
        def.setStartCommand(startCommandArea.getText().trim());
        def.setWorkingDir(installDirField.getText().trim());

        List<String> installCmds = new ArrayList<>();
        for (String line : installCommandsArea.getText().split("\n")) {
            if (!line.isBlank()) installCmds.add(line.trim());
        }
        def.setInstallCommands(installCmds);

        if (!portField.getText().isBlank()) {
            try {
                def.setPort(Integer.parseInt(portField.getText().trim()));
            } catch (NumberFormatException ignored) {}
        }

        String script = groovyScriptArea.getText().trim();
        if (!script.isBlank()) def.setGroovyScript(script);

        // argSpecs: 폼에서 입력한 enabled 상태와 값을 반영
        if (!currentArgSpecs.isEmpty()) {
            List<ArgSpec> updatedSpecs = new ArrayList<>();
            Map<String, String> argValues = new LinkedHashMap<>();

            for (ArgSpec orig : currentArgSpecs) {
                // 원본 spec 복사 후 enabled 상태 갱신
                ArgSpec updated = copySpec(orig);
                CheckBox enabledCb = argEnabledChecks.get(orig.getName());
                if (enabledCb != null) updated.setEnabled(enabledCb.isSelected());
                updatedSpecs.add(updated);

                // 값 수집
                Control ctrl = argValueControls.get(orig.getName());
                if (ctrl != null) {
                    String val = getArgControlValue(ctrl);
                    if (!val.isBlank()) argValues.put(orig.getName(), val);
                }
            }
            def.setArgSpecs(updatedSpecs);
            def.setArgValues(argValues);

        } else if ("BGE-M3 Embedding Server".equals(templateCombo.getValue())) {
            // 기존 하드코딩 템플릿 fallback (builtin 선택이 아닌 경우)
            def.setArgSpecs(createBgeM3Args());
        }

        return def;
    }

    /** ArgSpec 을 필드 단위로 복사해 반환한다 (참조 공유 방지). */
    private ArgSpec copySpec(ArgSpec src) {
        ArgSpec dst = new ArgSpec();
        dst.setName(src.getName());
        dst.setFlag(src.getFlag());
        dst.setType(src.getType());
        dst.setDefaultValue(src.getDefaultValue());
        dst.setDescription(src.getDescription());
        dst.setOptions(src.getOptions() != null ? new ArrayList<>(src.getOptions()) : null);
        dst.setEnabled(src.isEnabled());
        return dst;
    }

    private List<ArgSpec> createBgeM3Args() {
        List<ArgSpec> specs = new ArrayList<>();

        ArgSpec port = new ArgSpec();
        port.setName("port");
        port.setFlag("--port");
        port.setType("INTEGER");
        port.setDefaultValue("3000");
        port.setDescription("서버 포트");
        specs.add(port);

        ArgSpec device = new ArgSpec();
        device.setName("device");
        device.setFlag("--device");
        device.setType("SELECT");
        device.setOptions(Arrays.asList("auto", "cpu", "cuda", "mps"));
        device.setDefaultValue("auto");
        device.setDescription("연산 장치");
        specs.add(device);

        ArgSpec fp16 = new ArgSpec();
        fp16.setName("fp16");
        fp16.setFlag("--fp16");
        fp16.setType("BOOLEAN");
        fp16.setDefaultValue("false");
        fp16.setDescription("FP16 모드 (CUDA 권장)");
        specs.add(fp16);

        ArgSpec batchSize = new ArgSpec();
        batchSize.setName("batch-size");
        batchSize.setFlag("--batch-size");
        batchSize.setType("INTEGER");
        batchSize.setDefaultValue("32");
        batchSize.setDescription("배치 크기");
        specs.add(batchSize);

        ArgSpec model = new ArgSpec();
        model.setName("model");
        model.setFlag("--model");
        model.setType("STRING");
        model.setDefaultValue("BAAI/bge-m3");
        model.setDescription("모델 이름");
        specs.add(model);

        return specs;
    }

    /**
     * 외부에서 ServiceDefinition을 주입해 폼을 미리 채운다. 기본 제공 서비스 선택 후 호출.
     *
     * @param def 폼에 미리 채울 ServiceDefinition
     */
    public void prefill(ServiceDefinition def) {
        populateForm(def);
        templateCombo.setValue("직접 입력");
    }

    public ServiceDefinition getResult() { return result; }
}
