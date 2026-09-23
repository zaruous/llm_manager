/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.service.LlmSkillInstaller;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 스킬 & 룰 설치 탭 컨트롤러.
 * 내장 도구·팩을 선택해 지정한 프로젝트 경로에 스킬/룰 파일을 설치한다.
 */
public class LlmSkillsInstallController implements Initializable {

    @FXML private TextField projectPathField;
    @FXML private TextField projectNameField;
    @FXML private ComboBox<String> languageCombo;
    @FXML private TextField authorField;
    @FXML private VBox toolsContainer;
    @FXML private VBox packsContainer;
    @FXML private TextArea previewArea;
    @FXML private ListView<ProjectConfig> projectHistoryList;
    @FXML private Button installBtn;
    @FXML private Button backupInstallBtn;

    /** 도구 ID → 체크박스. 설치 대상 도구를 추적한다. */
    private final Map<String, CheckBox> toolChecks = new LinkedHashMap<>();

    /** 팩 ID → 체크박스. 설치 대상 팩을 추적한다. */
    private final Map<String, CheckBox> packChecks = new LinkedHashMap<>();

    /** 팩 목록 영역에 현재 표시 중인 도구 ID. 도구 목록을 다시 그려도 같은 도구를 유지한다. */
    private String shownToolId;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        languageCombo.setItems(FXCollections.observableArrayList(
                "Java", "Python", "TypeScript", "JavaScript", "Go", "Rust", "C#", "기타"));
        languageCombo.setValue("Java");

        authorField.setText(System.getProperty("user.name", ""));

        loadTools();
        loadProjectHistory();

        projectHistoryList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ProjectConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.getName() + "\n" + item.getPath());
            }
        });

        projectHistoryList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> { if (sel != null) loadProjectToForm(sel); });
    }

    /**
     * 도구·팩 체크박스를 다시 만든다. 이미 있던 도구·팩은 직전 체크 상태를 유지하고,
     * 새로 나타난 도구는 해제, 팩은 선택 상태로 시작한다.
     * (도구를 기본 선택하면 경로만 넣고 설치를 눌러도 모든 팩이 설치되고, CLAUDE.md처럼
     * 여러 팩이 같은 파일을 쓰는 충돌이 기본 상태에서 발생한다.)
     */
    private void loadTools() {
        Map<String, Boolean> prevTools = selectionOf(toolChecks);
        Map<String, Boolean> prevPacks = selectionOf(packChecks);

        toolsContainer.getChildren().clear();
        toolChecks.clear();
        packChecks.clear();

        List<LlmTool> tools = AppContext.getInstance().getLlmSkillInstaller().loadTools();

        // 팩 체크박스를 미리 등록해 buildProjectConfig()가 항상 모든 팩을 참조할 수 있게 한다
        for (LlmTool tool : tools) {
            for (SkillPack pack : tool.getPacks()) {
                CheckBox cb = new CheckBox();
                cb.setSelected(prevPacks.getOrDefault(pack.getId(), true));
                cb.selectedProperty().addListener((obs, o, v) -> updatePreview());
                packChecks.put(pack.getId(), cb);
            }
        }

        // GridPane: 체크박스(가변 너비) | 버튼(고정 100px) → 버튼이 항상 같은 열에 정렬
        GridPane grid = buildGrid(new Insets(4));

        int row = 0;
        for (LlmTool tool : tools) {
            CheckBox cb = new CheckBox(tool.getDisplayName());
            cb.setSelected(prevTools.getOrDefault(tool.getId(), false));
            cb.setMaxWidth(Double.MAX_VALUE);
            cb.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
            Tooltip.install(cb, new Tooltip(tool.getDescription()));
            toolChecks.put(tool.getId(), cb);
            cb.selectedProperty().addListener((obs, old, val) -> updatePreview());

            Button viewBtn = new Button("팩 선택");
            viewBtn.setPrefWidth(100);
            LlmTool capturedTool = tool;
            viewBtn.setOnAction(e -> showPacks(capturedTool));

            grid.add(cb, 0, row);
            grid.add(viewBtn, 1, row);
            row++;
        }

        toolsContainer.getChildren().add(grid);
        VBox.setVgrow(grid, Priority.ALWAYS);

        if (!tools.isEmpty()) {
            showPacks(tools.stream()
                    .filter(t -> t.getId().equals(shownToolId))
                    .findFirst().orElse(tools.get(0)));
        }
    }

    private static Map<String, Boolean> selectionOf(Map<String, CheckBox> checks) {
        Map<String, Boolean> result = new HashMap<>();
        checks.forEach((id, cb) -> result.put(id, cb.isSelected()));
        return result;
    }

    /**
     * DB 라이브러리 변경분(로드 탭에서 저장한 파일)을 반영해 도구 목록을 다시 그린다.
     * 사용자가 고른 체크 상태는 유지된다.
     */
    public void reloadTools() {
        AppContext.getInstance().getLlmSkillInstaller().refreshLibrary();
        loadTools();
        updatePreview();
    }

    private void showPacks(LlmTool tool) {
        shownToolId = tool.getId();
        packsContainer.getChildren().clear();

        Label header = new Label(tool.getDisplayName() + " 스킬 팩");
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        packsContainer.getChildren().add(header);

        // GridPane: 체크박스(가변) | 버튼(고정 100px) → 버튼이 항상 같은 열에 정렬
        GridPane grid = buildGrid(new Insets(4, 4, 4, 12));

        int row = 0;
        for (SkillPack pack : tool.getPacks()) {
            CheckBox cb = packChecks.get(pack.getId());
            if (cb == null) continue;

            String tagStr = pack.getTags() != null
                    ? "  [" + String.join(", ", pack.getTags()) + "]" : "";
            cb.setText(pack.getName() + tagStr);
            cb.setMaxWidth(Double.MAX_VALUE);

            Button previewBtn = new Button("내용 보기");
            previewBtn.setPrefWidth(100);
            LlmTool capturedTool = tool;
            SkillPack capturedPack = pack;
            previewBtn.setOnAction(e -> showPackContent(capturedTool, capturedPack));

            grid.add(cb, 0, row);
            grid.add(previewBtn, 1, row);
            row++;
        }

        packsContainer.getChildren().add(grid);
        VBox.setVgrow(grid, Priority.ALWAYS);
    }

    /**
     * 체크박스(가변 너비) + 버튼(고정 100px) 2열 GridPane을 생성하는 공통 헬퍼.
     * loadTools()와 showPacks()가 동일한 열 구성을 공유해 일관된 정렬을 유지한다.
     *
     * @param padding 그리드에 적용할 패딩
     * @return 열 제약이 설정된 GridPane
     */
    private GridPane buildGrid(Insets padding) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(padding);

        // 체크박스 열: 남은 너비를 모두 차지
        ColumnConstraints cbCol = new ColumnConstraints();
        cbCol.setHgrow(Priority.ALWAYS);
        cbCol.setFillWidth(true);

        // 버튼 열: 100px 고정, 오른쪽 정렬
        ColumnConstraints btnCol = new ColumnConstraints(100, 100, 100);
        btnCol.setHalignment(HPos.RIGHT);

        grid.getColumnConstraints().addAll(cbCol, btnCol);
        return grid;
    }

    private void showPackContent(LlmTool tool, SkillPack pack) {
        LlmSkillInstaller installer = AppContext.getInstance().getLlmSkillInstaller();
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(tool.getDisplayName()).append(" - ").append(pack.getName()).append(" ===\n\n");
        for (SkillFile sf : pack.getFiles()) {
            sb.append("--- ").append(sf.getTargetPath()).append(" ---\n");
            sb.append(installer.readSkillContent(sf, buildVariables())).append("\n\n");
        }
        previewArea.setText(sb.toString());
    }

    private void updatePreview() {
        LlmSkillInstaller installer = AppContext.getInstance().getLlmSkillInstaller();
        ProjectConfig config = buildProjectConfig();
        List<Map<String, String>> items = installer.preview(config);
        if (items.isEmpty()) {
            previewArea.setText("설치할 파일이 없습니다. 도구와 팩을 선택하세요.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> conflicts = installer.findConflicts(config);
        if (!conflicts.isEmpty()) {
            sb.append(formatConflicts(conflicts)).append("\n");
        }
        sb.append("설치 예정 파일:\n\n");
        for (Map<String, String> item : items) {
            sb.append(String.format("  %-40s [%s] — %s / %s%n",
                    item.get("target"), item.get("status"),
                    item.get("tool"), item.get("pack")));
        }
        previewArea.setText(sb.toString());
    }

    @FXML
    private void onBrowseProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("프로젝트 디렉토리 선택");
        File dir = chooser.showDialog(projectPathField.getScene().getWindow());
        if (dir != null) {
            projectPathField.setText(dir.getAbsolutePath());
            if (projectNameField.getText().isBlank()) {
                projectNameField.setText(dir.getName());
            }
            updatePreview();
        }
    }

    @FXML
    private void onInstall() {
        doInstall(false);
    }

    @FXML
    private void onBackupInstall() {
        doInstall(true);
    }

    /**
     * 입력·충돌을 검증하고, 기존 파일을 덮어쓰게 되면 사용자 확인을 받은 뒤 설치한다.
     *
     * @param backup true면 확인 없이 기존 파일을 백업하고 덮어쓴다 ('백업 후 설치' 버튼)
     */
    private void doInstall(boolean backup) {
        String path = projectPathField.getText().trim();
        if (path.isBlank()) {
            alert("프로젝트 경로를 선택하세요.");
            return;
        }
        // 오타 난 경로가 createDirectories로 조용히 생성되는 것을 막는다
        if (!Files.isDirectory(Path.of(path))) {
            alert("프로젝트 경로가 존재하지 않거나 디렉토리가 아닙니다:\n" + path);
            return;
        }

        ProjectConfig config = buildProjectConfig();
        config.setLastInstalled(LocalDateTime.now());

        LlmSkillInstaller installer = AppContext.getInstance().getLlmSkillInstaller();
        List<Map<String, String>> items = installer.preview(config);
        if (items.isEmpty()) {
            alert("설치할 파일이 없습니다. 도구와 팩을 선택하세요.");
            return;
        }

        Map<String, List<String>> conflicts = installer.findConflicts(config);
        if (!conflicts.isEmpty()) {
            alert(formatConflicts(conflicts));
            return;
        }

        boolean doBackup = backup;
        boolean overwrite = true;
        if (!backup) {
            List<String> existing = items.stream()
                    .filter(i -> "덮어쓰기".equals(i.get("status")))
                    .map(i -> i.get("target"))
                    .toList();
            if (!existing.isEmpty()) {
                InstallMode mode = confirmOverwrite(existing);
                if (mode == InstallMode.CANCEL) return;
                doBackup = mode == InstallMode.BACKUP_AND_OVERWRITE;
                overwrite = mode != InstallMode.NEW_ONLY;
            }
        }

        try {
            if (doBackup) installer.backup(config);

            LlmSkillInstaller.InstallResult result = installer.install(config, overwrite);

            StringBuilder msg = new StringBuilder();
            if (!result.installed().isEmpty())
                msg.append("✓ 설치됨 (").append(result.installed().size()).append("개):\n")
                   .append(result.installed().stream().map(s -> "  " + s).collect(Collectors.joining("\n")))
                   .append("\n\n");
            if (!result.skipped().isEmpty())
                msg.append("⏭ 건너뜀 (").append(result.skipped().size()).append("개):\n")
                   .append(result.skipped().stream().map(s -> "  " + s).collect(Collectors.joining("\n")))
                   .append("\n\n");
            if (!result.errors().isEmpty())
                msg.append("✗ 오류 (").append(result.errors().size()).append("개):\n")
                   .append(result.errors().stream().map(s -> "  " + s).collect(Collectors.joining("\n")));

            previewArea.setText(msg.toString());

            // 히스토리 저장
            AppContext.getInstance().getProjectRegistry().save(config);
            loadProjectHistory();

        } catch (Exception e) {
            alert("설치 오류: " + e.getMessage());
        }
    }

    /** 기존 파일이 있을 때 사용자가 고른 설치 방식. */
    private enum InstallMode { OVERWRITE, BACKUP_AND_OVERWRITE, NEW_ONLY, CANCEL }

    /**
     * 덮어쓸 기존 파일 목록을 보여주고 설치 방식을 묻는다.
     *
     * @param existing 이미 존재하는 대상 파일 경로 목록
     * @return 선택한 설치 방식. 창을 닫으면 CANCEL
     */
    private InstallMode confirmOverwrite(List<String> existing) {
        ButtonType overwriteBtn = new ButtonType("덮어쓰기", ButtonBar.ButtonData.OTHER);
        ButtonType backupBtn = new ButtonType("백업 후 덮어쓰기", ButtonBar.ButtonData.OK_DONE);
        ButtonType newOnlyBtn = new ButtonType("신규 파일만 설치", ButtonBar.ButtonData.OTHER);
        ButtonType cancelBtn = new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE);

        TextArea list = new TextArea(existing.stream()
                .map(s -> "  " + s).collect(Collectors.joining("\n")));
        list.setEditable(false);
        list.setPrefRowCount(Math.min(existing.size(), 10));

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, null,
                overwriteBtn, backupBtn, newOnlyBtn, cancelBtn);
        dialog.initOwner(installBtn.getScene().getWindow());
        dialog.setTitle("기존 파일 덮어쓰기");
        dialog.setHeaderText("프로젝트에 이미 있는 파일 " + existing.size() + "개를 덮어씁니다.");
        dialog.getDialogPane().setContent(new VBox(8,
                new Label("'신규 파일만 설치'는 기존 파일을 그대로 두고 없는 파일만 만듭니다."), list));

        ButtonType choice = dialog.showAndWait().orElse(cancelBtn);
        if (choice == overwriteBtn) return InstallMode.OVERWRITE;
        if (choice == backupBtn) return InstallMode.BACKUP_AND_OVERWRITE;
        if (choice == newOnlyBtn) return InstallMode.NEW_ONLY;
        return InstallMode.CANCEL;
    }

    private static String formatConflicts(Map<String, List<String>> conflicts) {
        StringBuilder sb = new StringBuilder(
                "⚠ 같은 경로에 설치되는 팩이 있습니다. 경로마다 하나의 팩만 선택하세요.\n");
        conflicts.forEach((target, owners) -> {
            sb.append("\n  ").append(target).append('\n');
            owners.forEach(o -> sb.append("    - ").append(o).append('\n'));
        });
        return sb.toString();
    }

    @FXML
    private void onRefreshPreview() {
        updatePreview();
    }

    private ProjectConfig buildProjectConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setPath(projectPathField.getText().trim());
        config.setName(projectNameField.getText().isBlank()
                ? (config.getPath().isBlank() ? "MyProject"
                    : Path.of(config.getPath()).getFileName() != null
                        ? Path.of(config.getPath()).getFileName().toString()
                        : "MyProject")
                : projectNameField.getText().trim());
        config.setVariables(buildVariables());

        List<String> enabledTools = toolChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey)
                .collect(Collectors.toList());
        config.setEnabledToolIds(enabledTools);

        List<String> enabledPacks = packChecks.entrySet().stream()
                .filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey)
                .collect(Collectors.toList());
        config.setEnabledPackIds(enabledPacks);

        return config;
    }

    private Map<String, String> buildVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        String name = projectNameField.getText().trim();
        if (name.isBlank() && !projectPathField.getText().isBlank()) {
            Path p = Path.of(projectPathField.getText());
            if (p.getFileName() != null) {
                name = p.getFileName().toString();
            }
        }
        vars.put("projectName", name.isBlank() ? "MyProject" : name);
        vars.put("language", languageCombo.getValue() != null ? languageCombo.getValue() : "Java");
        vars.put("author", authorField.getText().trim());
        return vars;
    }

    private void loadProjectHistory() {
        projectHistoryList.setItems(FXCollections.observableArrayList(
                AppContext.getInstance().getProjectRegistry().getAll()));
    }

    private void loadProjectToForm(ProjectConfig config) {
        projectPathField.setText(config.getPath());
        projectNameField.setText(config.getName());
        if (config.getVariables() != null) {
            String lang = config.getVariables().get("language");
            if (lang != null) languageCombo.setValue(lang);
            String author = config.getVariables().get("author");
            if (author != null) authorField.setText(author);
        }
        // 도구 체크박스 복원
        toolChecks.forEach((id, cb) ->
                cb.setSelected(config.getEnabledToolIds().contains(id)));
        // 팩 체크박스 복원
        packChecks.forEach((id, cb) ->
                cb.setSelected(config.getEnabledPackIds().contains(id)));
        updatePreview();
    }

    private void alert(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        alert.initOwner(installBtn.getScene().getWindow());
        alert.showAndWait();
    }
}
