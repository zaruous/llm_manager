/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.LoadFileEntry;
import org.kyj.llmmanager.service.LlmSkillInstaller;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 스킬 & 룰 로드 탭 컨트롤러.
 * 지정한 소스 디렉토리를 스캔해 파일 목록을 나열하고,
 * 선택한 파일 구조와 내용을 프로젝트 lib/cursor SQLite 저장소에 적재한다.
 */
public class LlmSkillsLoadController implements Initializable {

    @FXML private TextField sourceDirField;
    @FXML private TextField targetDirField;
    @FXML private TreeView<LoadFileEntry> fileTreeView;
    @FXML private TextArea previewArea;
    @FXML private Label fileCountLabel;
    @FXML private Button loadBtn;

    /** 스캔 결과 파일 목록. 실제 복사 대상 파일만 담는다. */
    private final List<LoadFileEntry> fileEntries = new ArrayList<>();

    /** TreeView 루트. 화면에는 표시하지 않는다. */
    private CheckBoxTreeItem<LoadFileEntry> treeRoot;

    /** 디렉토리 체크 상태 변경 시 하위 노드를 일괄 갱신하는 중인지 여부. */
    private boolean updatingTreeSelection;

    /** 현재 스캔된 소스 루트 경로. null이면 미스캔 상태. */
    private Path sourceRoot;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        targetDirField.setText(AppContext.getInstance().getLlmSkillInstaller()
                .getLibraryRepository().getDatabaseLocation());

        treeRoot = createTreeItem(new LoadFileEntry("", "root", true));
        treeRoot.setExpanded(true);
        fileTreeView.setRoot(treeRoot);
        fileTreeView.setShowRoot(false);
        fileTreeView.setCellFactory(CheckBoxTreeCell.forTreeView());

        fileTreeView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> {
                    if (sel == null || sel.getValue() == null) return;
                    LoadFileEntry entry = sel.getValue();
                    if (!entry.isDirectory()) showPreview(entry);
                });
    }

    @FXML
    private void onBrowseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("소스 디렉토리 선택");
        File dir = chooser.showDialog(sourceDirField.getScene().getWindow());
        if (dir != null) {
            sourceDirField.setText(dir.getAbsolutePath());
            onScan();
        }
    }

    @FXML
    private void onBrowseTarget() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("대상 프로젝트 디렉토리 선택");
        File dir = chooser.showDialog(targetDirField.getScene().getWindow());
        if (dir != null) {
            targetDirField.setText(dir.getAbsolutePath());
        }
    }

    /**
     * 소스 디렉토리를 재귀 탐색해 파일 목록을 갱신한다.
     * .git, node_modules 등 노이즈 디렉토리는 제외한다.
     */
    @FXML
    private void onScan() {
        String srcPath = sourceDirField.getText().trim();
        if (srcPath.isBlank()) {
            alert("소스 디렉토리를 선택하세요.");
            return;
        }

        sourceRoot = Path.of(srcPath);
        if (!Files.isDirectory(sourceRoot)) {
            alert("유효한 디렉토리가 아닙니다: " + srcPath);
            return;
        }

        fileEntries.clear();
        treeRoot.getChildren().clear();
        previewArea.clear();

        try {
            List<LoadFileEntry> found = Files.walk(sourceRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(p))
                    .filter(LlmSkillsLoadController::isSkillRuleFile)
                    .sorted()
                    .map(p -> new LoadFileEntry(
                            sourceRoot.relativize(p).toString().replace('\\', '/')))
                    .collect(Collectors.toList());

            fileEntries.addAll(found);
            buildFileTree(found);
            fileCountLabel.setText(found.size() + "개 파일 발견");

            if (found.isEmpty()) {
                previewArea.setText("스캔 결과가 없습니다.");
            }
        } catch (IOException e) {
            alert("스캔 오류: " + e.getMessage());
        }
    }

    /**
     * 재귀 탐색 시 제외할 경로 판별.
     * 경로 구성 요소 중 하나라도 노이즈 디렉토리명과 일치하면 true를 반환한다.
     *
     * @param path 판별 대상 파일 경로
     * @return 제외 대상이면 true
     */
    private boolean isExcluded(Path path) {
        for (Path part : path) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("node_modules") ||
                name.equals("target") || name.equals("build") ||
                name.equals(".gradle") || name.equals(".idea") ||
                name.startsWith(".llm-backup")) {
                return true;
            }
        }
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.equals(".env") || fileName.endsWith(".key") ||
            fileName.endsWith(".pem") || fileName.contains("token") ||
            fileName.contains("pat")) {
            return true;
        }
        return false;
    }

    /**
     * LLM 스킬·룰 파일로 인식할 확장자 판별.
     * 텍스트 기반 설정 파일만 포함하며 바이너리·소스 코드는 제외한다.
     *
     * @param path 판별 대상 파일 경로
     * @return 스킬·룰 파일이면 true
     */
    private static boolean isSkillRuleFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md")   || name.endsWith(".mdc")  ||
               name.endsWith(".json") || name.endsWith(".yaml") ||
               name.endsWith(".yml")  || name.endsWith(".txt")  ||
               name.endsWith(".toml") || name.endsWith(".xml");
    }

    @FXML
    private void onSelectAll() {
        setTreeSelected(treeRoot, true);
    }

    @FXML
    private void onDeselectAll() {
        setTreeSelected(treeRoot, false);
    }

    /**
     * 선택한 파일의 내용을 미리보기 영역에 표시한다.
     * 바이너리 파일이거나 읽기 오류 시 안내 메시지를 출력한다.
     *
     * @param entry 미리볼 파일 항목
     */
    private void showPreview(LoadFileEntry entry) {
        if (sourceRoot == null) return;
        Path filePath = sourceRoot.resolve(entry.getRelativePath());
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            previewArea.setText(content);
        } catch (IOException e) {
            // UTF-8로 읽지 못하면 바이너리 파일로 간주해 크기만 표시
            try {
                long size = Files.size(filePath);
                previewArea.setText("[바이너리 파일 — " + size + " bytes]");
            } catch (IOException ex) {
                previewArea.setText("파일을 읽을 수 없습니다: " + e.getMessage());
            }
        }
    }

    /**
     * 선택된 파일들을 소스 디렉토리에서 SQLite 라이브러리로 저장한다.
     */
    @FXML
    private void onLoad() {
        if (sourceRoot == null || fileEntries.isEmpty()) {
            alert("소스 디렉토리를 먼저 스캔하세요.");
            return;
        }

        List<LoadFileEntry> selected = getSelectedFiles();

        if (selected.isEmpty()) {
            alert("로드할 파일을 선택하세요.");
            return;
        }

        try {
            LlmSkillInstaller installer = AppContext.getInstance().getLlmSkillInstaller();
            List<String> relativePaths = selected.stream()
                    .map(LoadFileEntry::getRelativePath)
                    .collect(Collectors.toList());
            int saved = installer.getLibraryRepository().saveFiles(sourceRoot, relativePaths);
            installer.refreshLibrary();

            StringBuilder msg = new StringBuilder();
            msg.append("DB Provider: ")
               .append(installer.getLibraryRepository().getProvider())
               .append("\n")
               .append("DB 위치: ")
               .append(installer.getLibraryRepository().getDatabaseLocation())
               .append("\n\n")
               .append("✓ 로드 완료 (").append(saved).append("개):\n")
               .append(relativePaths.stream().map(s -> "  " + s).collect(Collectors.joining("\n")))
               .append("\n\n설치 탭에서 '로드된 Cursor 라이브러리'를 선택하면 DB에서 파일을 씁니다.");

            previewArea.setText(msg.toString());
        } catch (Exception e) {
            alert("SQLite 저장 오류: " + e.getMessage());
        }
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait();
    }

    /**
     * 상대 경로 목록을 디렉토리 트리로 변환한다.
     *
     * @param entries 스캔된 파일 항목
     */
    private void buildFileTree(List<LoadFileEntry> entries) {
        treeRoot.getChildren().clear();
        Map<String, CheckBoxTreeItem<LoadFileEntry>> dirs = new LinkedHashMap<>();
        dirs.put("", treeRoot);

        for (LoadFileEntry entry : entries) {
            String relativePath = entry.getRelativePath();
            String[] parts = relativePath.split("/");
            CheckBoxTreeItem<LoadFileEntry> parent = treeRoot;
            String currentPath = "";

            for (int i = 0; i < parts.length - 1; i++) {
                currentPath = currentPath.isBlank() ? parts[i] : currentPath + "/" + parts[i];
                CheckBoxTreeItem<LoadFileEntry> existing = dirs.get(currentPath);
                if (existing == null) {
                    existing = createTreeItem(new LoadFileEntry(currentPath, parts[i], true));
                    existing.setExpanded(true);
                    parent.getChildren().add(existing);
                    dirs.put(currentPath, existing);
                }
                parent = existing;
            }

            String fileName = parts.length == 0 ? relativePath : parts[parts.length - 1];
            CheckBoxTreeItem<LoadFileEntry> fileItem =
                    createTreeItem(new LoadFileEntry(relativePath, fileName, false));
            parent.getChildren().add(fileItem);
        }
    }

    private CheckBoxTreeItem<LoadFileEntry> createTreeItem(LoadFileEntry entry) {
        CheckBoxTreeItem<LoadFileEntry> item = new CheckBoxTreeItem<>(entry);
        item.setSelected(entry.isSelected());
        item.selectedProperty().addListener((obs, old, selected) -> {
            entry.setSelected(selected);
            if (updatingTreeSelection || !entry.isDirectory()) return;
            try {
                updatingTreeSelection = true;
                item.getChildren().forEach(child -> setTreeSelected(child, selected));
            } finally {
                updatingTreeSelection = false;
            }
        });
        return item;
    }

    private void setTreeSelected(TreeItem<LoadFileEntry> item, boolean selected) {
        if (item instanceof CheckBoxTreeItem<LoadFileEntry> cb) {
            cb.setSelected(selected);
            if (cb.getValue() != null) cb.getValue().setSelected(selected);
        }
        item.getChildren().forEach(child -> setTreeSelected(child, selected));
    }

    private List<LoadFileEntry> getSelectedFiles() {
        List<LoadFileEntry> selected = new ArrayList<>();
        collectSelectedFiles(treeRoot, selected);
        return selected;
    }

    private void collectSelectedFiles(TreeItem<LoadFileEntry> item, List<LoadFileEntry> selected) {
        if (item instanceof CheckBoxTreeItem<LoadFileEntry> cb) {
            LoadFileEntry entry = cb.getValue();
            if (entry != null && !entry.isDirectory() && cb.isSelected()) {
                selected.add(entry);
            }
        }
        item.getChildren().forEach(child -> collectSelectedFiles(child, selected));
    }
}
