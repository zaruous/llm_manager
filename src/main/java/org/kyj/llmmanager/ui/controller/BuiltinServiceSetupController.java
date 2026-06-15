/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.service.ServiceCustomizer;
import org.kyj.llmmanager.util.PlatformUtil;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * builtin 서비스 전용 설정 다이얼로그 컨트롤러.
 * 활성 argSpecs를 설명의 [그룹명] 태그 기준으로 섹션화하고,
 * 비활성 항목은 '고급 설정' TitledPane에 접어 둔다.
 */
public class BuiltinServiceSetupController {

    /** 서비스 이름 표시 레이블 */
    @FXML private Label serviceNameLabel;
    /** 서비스 설명 표시 레이블 */
    @FXML private Label serviceDescLabel;
    /** 설치 경로 입력 행 */
    @FXML private HBox installDirRow;
    /** 설치 경로 입력 필드 */
    @FXML private TextField installDirField;
    /** 동적으로 인수 섹션이 추가되는 컨테이너 */
    @FXML private VBox mainContent;

    private final ServiceCustomizer customizer = new ServiceCustomizer();

    /** setup() 호출 시 주입되는 원본 builtin 서비스 정의 */
    private ServiceDefinition sourceDef;
    /** onConfirm() 성공 후 채워지는 최종 결과. 취소 시 null. */
    private ServiceDefinition result;

    /** 전체 argSpecs 목록 (enabled + disabled) */
    private List<ArgSpec> allSpecs = new ArrayList<>();
    /** argSpec.name → 활성화 체크박스 */
    private final Map<String, CheckBox> argEnabledChecks = new LinkedHashMap<>();
    /** argSpec.name → 값 입력 컨트롤 (TextField / ComboBox / CheckBox) */
    private final Map<String, Control> argValueControls  = new LinkedHashMap<>();

    /** 설명 앞의 [그룹명] 태그를 추출하는 패턴 */
    private static final Pattern GROUP_TAG = Pattern.compile("^\\[([^]]+)]");

    /**
     * builtin 서비스 정의를 받아 다이얼로그 폼을 구성한다.
     *
     * @param def 설정 대상 builtin ServiceDefinition
     */
    public void setup(ServiceDefinition def) {
        this.sourceDef = def;
        allSpecs = def.getArgSpecs() != null ? new ArrayList<>(def.getArgSpecs()) : new ArrayList<>();

        serviceNameLabel.setText(def.getName());
        serviceDescLabel.setText(def.getDescription() != null ? def.getDescription() : "");

        // ${user.home} 등 경로 변수 치환 → null이면 이름 기반 계산
        String baseDir = (def.getInstallDir() != null && !def.getInstallDir().isBlank())
                ? PlatformUtil.resolvePath(def.getInstallDir())
                : defaultInstallDir(def);
        installDirField.setText(baseDir);

        buildArgSections(def.getArgValues());
    }

    /**
     * 서비스별 기본 설치 경로를 계산한다.
     * repoUrl 마지막 세그먼트를 우선 사용하고, 없으면 서비스 이름을 소문자-하이픈으로 변환한다.
     * 결과는 {@code ~/llm-services/<이름>} 형태이며, JAR 서비스의 설치 대상 디렉토리가 된다.
     *
     * @param def builtin ServiceDefinition
     * @return 서비스별 기본 설치 경로 문자열
     */
    private String defaultInstallDir(ServiceDefinition def) {
        String sub = null;
        if (def.getRepoUrl() != null && !def.getRepoUrl().isBlank()) {
            String url = def.getRepoUrl().trim().replaceAll("/$", "");
            sub = url.substring(url.lastIndexOf('/') + 1);
        }
        if (sub == null || sub.isBlank()) {
            sub = def.getName() == null ? "service"
                    : def.getName().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+$", "");
        }
        return PlatformUtil.getDefaultInstallBase().resolve(sub).toString();
    }

    /**
     * argSpecs를 활성/비활성으로 분리해 mainContent에 섹션을 구성한다.
     * 활성 스펙: [그룹] 태그가 있으면 그룹별 섹션 헤더+GridPane, 없으면 단일 GridPane.
     * 비활성 스펙: '고급 설정' TitledPane으로 접어 둔다.
     *
     * @param argValues 현재 저장된 인수 값 맵 (null 허용)
     */
    private void buildArgSections(Map<String, String> argValues) {
        mainContent.getChildren().clear();
        argEnabledChecks.clear();
        argValueControls.clear();

        List<ArgSpec> enabled  = allSpecs.stream().filter(ArgSpec::isEnabled).toList();
        List<ArgSpec> disabled = allSpecs.stream().filter(s -> !s.isEnabled()).toList();

        if (!enabled.isEmpty()) {
            boolean hasGroups = enabled.stream()
                    .anyMatch(s -> s.getDescription() != null
                                   && GROUP_TAG.matcher(s.getDescription()).find());
            if (hasGroups) {
                buildGroupedSections(enabled, argValues);
            } else {
                mainContent.getChildren().add(sectionLabel("실행 인수"));
                mainContent.getChildren().add(buildArgGrid(enabled, argValues));
            }
        }

        // 비활성 스펙 → 고급 설정 TitledPane
        if (!disabled.isEmpty()) {
            VBox inner = new VBox(6);
            inner.setPadding(new Insets(8, 4, 4, 4));
            Label hint = new Label("체크박스를 선택하면 해당 인수가 시작 명령어에 포함됩니다.");
            hint.getStyleClass().add("hint-label");
            inner.getChildren().add(hint);
            inner.getChildren().add(buildArgGrid(disabled, argValues));

            TitledPane advanced = new TitledPane("고급 설정", inner);
            advanced.setExpanded(false);
            advanced.setAnimated(true);
            VBox.setMargin(advanced, new Insets(8, 0, 0, 0));
            mainContent.getChildren().add(advanced);
        }
    }

    /**
     * [그룹] 태그 기준으로 스펙을 그룹화하고 각 그룹을 섹션 레이블 + GridPane으로 표시한다.
     *
     * @param specs     활성 ArgSpec 목록
     * @param argValues 현재 인수 값 맵
     */
    private void buildGroupedSections(List<ArgSpec> specs, Map<String, String> argValues) {
        LinkedHashMap<String, List<ArgSpec>> grouped = new LinkedHashMap<>();
        for (ArgSpec spec : specs) {
            grouped.computeIfAbsent(extractGroup(spec.getDescription()), k -> new ArrayList<>())
                   .add(spec);
        }
        for (Map.Entry<String, List<ArgSpec>> entry : grouped.entrySet()) {
            mainContent.getChildren().add(sectionLabel(entry.getKey()));
            GridPane grid = buildArgGrid(entry.getValue(), argValues);
            VBox.setMargin(grid, new Insets(0, 0, 6, 0));
            mainContent.getChildren().add(grid);
        }
    }

    /**
     * ArgSpec 목록으로 4열(체크|플래그|값|설명) GridPane을 생성한다.
     *
     * @param specs     표시할 ArgSpec 목록
     * @param argValues 현재 인수 값 맵 (없으면 defaultValue 사용)
     * @return 생성된 GridPane
     */
    private GridPane buildArgGrid(List<ArgSpec> specs, Map<String, String> argValues) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.setPadding(new Insets(4, 4, 4, 8));

        ColumnConstraints chkCol  = new ColumnConstraints(24);
        ColumnConstraints flagCol = new ColumnConstraints(180, 190, 190);
        flagCol.setHalignment(HPos.LEFT);
        ColumnConstraints valCol  = new ColumnConstraints(120, 160, Double.MAX_VALUE);
        valCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints descCol = new ColumnConstraints(100, 140, Double.MAX_VALUE);
        descCol.setHgrow(Priority.SOMETIMES);
        grid.getColumnConstraints().addAll(chkCol, flagCol, valCol, descCol);

        int row = 0;
        for (ArgSpec spec : specs) {
            CheckBox enabledCb = new CheckBox();
            enabledCb.setSelected(spec.isEnabled());
            argEnabledChecks.put(spec.getName(), enabledCb);

            String keyText = (spec.getFlag() != null && !spec.getFlag().isBlank())
                    ? spec.getFlag()
                    : spec.getName();
            Label flagLbl = new Label(keyText);
            flagLbl.getStyleClass().add("arg-flag");

            String curVal = argValues != null
                    ? argValues.getOrDefault(spec.getName(), spec.getDefaultValue())
                    : spec.getDefaultValue();
            Control valCtrl = buildArgControl(spec, curVal);
            argValueControls.put(spec.getName(), valCtrl);

            // [그룹] 태그를 제거한 설명 텍스트
            String rawDesc = spec.getDescription() != null ? spec.getDescription() : "";
            String displayDesc = GROUP_TAG.matcher(rawDesc).replaceFirst("").trim();
            Label descLbl = new Label(displayDesc);
            descLbl.getStyleClass().add("arg-desc");
            descLbl.setWrapText(true);
            descLbl.setMaxWidth(Double.MAX_VALUE);

            // 비활성 시 값 컨트롤 흐리게 표시
            valCtrl.setStyle(spec.isEnabled() ? "" : "-fx-opacity: 0.5;");
            enabledCb.selectedProperty().addListener((obs, old, v) ->
                    valCtrl.setStyle(v ? "" : "-fx-opacity: 0.5;"));

            grid.add(enabledCb, 0, row);
            grid.add(flagLbl,   1, row);
            grid.add(valCtrl,   2, row);
            grid.add(descLbl,   3, row);
            GridPane.setValignment(enabledCb, VPos.CENTER);
            row++;
        }
        return grid;
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

    @SuppressWarnings("unchecked")
    private String getArgControlValue(Control ctrl) {
        if (ctrl instanceof TextField tf)   return tf.getText().trim();
        if (ctrl instanceof ComboBox<?> cb) return cb.getValue() != null ? cb.getValue().toString() : "";
        if (ctrl instanceof CheckBox cb)    return String.valueOf(cb.isSelected());
        return "";
    }

    /**
     * 설명 문자열에서 [그룹명] 접두어를 추출한다. 없으면 "기타"를 반환한다.
     *
     * @param desc 인수 설명 문자열 (null 허용)
     * @return 그룹명
     */
    private String extractGroup(String desc) {
        if (desc == null) return "기타";
        Matcher m = GROUP_TAG.matcher(desc);
        return m.find() ? m.group(1) : "기타";
    }

    /**
     * 섹션 구분에 쓸 굵은 레이블을 생성한다.
     *
     * @param text 섹션 제목
     * @return 스타일이 적용된 Label
     */
    private Label sectionLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("section-title");
        VBox.setMargin(lbl, new Insets(8, 0, 2, 0));
        return lbl;
    }

    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("설치 디렉토리 선택");
        String current = installDirField.getText();
        if (current != null && !current.isBlank()) {
            File f = new File(current);
            if (f.exists()) chooser.setInitialDirectory(f);
        }
        Stage stage = (Stage) installDirField.getScene().getWindow();
        File dir = chooser.showDialog(stage);
        if (dir != null) installDirField.setText(dir.getAbsolutePath());
    }

    /**
     * 입력 검증 → ServiceDefinition 생성 → Groovy 적용 → 다이얼로그 닫기.
     */
    @FXML
    private void onConfirm() {
        String installDir = installDirField.getText().trim();
        if (installDir.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "설치 경로를 입력해 주세요.", ButtonType.OK).showAndWait();
            return;
        }

        ServiceDefinition def = buildDefinition(installDir);

        if (def.getGroovyScript() != null && !def.getGroovyScript().isBlank()) {
            try {
                customizer.apply(def, def.getGroovyScript());
            } catch (Exception e) {
                new Alert(Alert.AlertType.ERROR, "Groovy 스크립트 오류:\n" + e.getMessage(),
                        ButtonType.OK).showAndWait();
                return;
            }
        }

        result = def;
        ((Stage) serviceNameLabel.getScene().getWindow()).close();
    }

    @FXML
    private void onCancel() {
        ((Stage) serviceNameLabel.getScene().getWindow()).close();
    }

    /**
     * 폼 입력 값과 원본 builtin 정의의 기본 정보를 합쳐 ServiceDefinition을 생성한다.
     * ID는 새로 발급해 서비스 레지스트리 충돌을 방지한다.
     * Groovy 스크립트가 있으면 이후 {@code customizer.apply()}가 workingDir·startCommand를 덮어쓴다.
     *
     * @param installDir 사용자가 입력한 설치 경로
     * @return 생성된 ServiceDefinition
     */
    private ServiceDefinition buildDefinition(String installDir) {
        ServiceDefinition def = new ServiceDefinition();   // 새 UUID 발급
        def.setName(sourceDef.getName());
        def.setDescription(sourceDef.getDescription());
        def.setRuntimeType(sourceDef.getRuntimeType());
        def.setRepoUrl(sourceDef.getRepoUrl());
        def.setStartCommand(sourceDef.getStartCommand());
        def.setInstallCommands(sourceDef.getInstallCommands() != null
                ? new ArrayList<>(sourceDef.getInstallCommands()) : new ArrayList<>());
        def.setPort(sourceDef.getPort());
        def.setHealthCheckPath(sourceDef.getHealthCheckPath());
        def.setGroovyScript(sourceDef.getGroovyScript());
        def.setAutoStart(sourceDef.isAutoStart());
        def.setBuiltin(true);

        def.setInstallDir(installDir);
        // Groovy 스크립트가 없으면 installDir을 working dir로 사용
        def.setWorkingDir(installDir);

        List<ArgSpec> updatedSpecs = new ArrayList<>();
        Map<String, String> argVals = new LinkedHashMap<>();

        for (ArgSpec orig : allSpecs) {
            ArgSpec updated = copySpec(orig);
            CheckBox enabledCb = argEnabledChecks.get(orig.getName());
            if (enabledCb != null) updated.setEnabled(enabledCb.isSelected());
            updatedSpecs.add(updated);

            Control ctrl = argValueControls.get(orig.getName());
            if (ctrl != null) {
                String val = getArgControlValue(ctrl);
                if (!val.isBlank()) argVals.put(orig.getName(), val);
            }
        }
        def.setArgSpecs(updatedSpecs);
        def.setArgValues(argVals);

        return def;
    }

    /** ArgSpec을 참조 공유 없이 복사한다. */
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

    public ServiceDefinition getResult() { return result; }
}
