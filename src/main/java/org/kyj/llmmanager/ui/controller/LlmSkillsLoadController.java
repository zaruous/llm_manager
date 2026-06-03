/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.model.LoadFileEntry;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.VBox;
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
 * 선택한 파일을 대상 프로젝트 경로로 복사한다.
 */
public class LlmSkillsLoadController implements Initializable {

    @FXML private TextField sourceDirField;
    @FXML private TextField targetDirField;
    @FXML private ListView<LoadFileEntry> fileListView;
    @FXML private TextArea previewArea;
    @FXML private Label fileCountLabel;
    @FXML private Button loadBtn;

    /** 스캔 결과 파일 목록. CheckBoxListCell에 바인딩된다. */
    private final ObservableList<LoadFileEntry> fileEntries = FXCollections.observableArrayList();

    /** 현재 스캔된 소스 루트 경로. null이면 미스캔 상태. */
    private Path sourceRoot;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        fileListView.setItems(fileEntries);
        // CheckBoxListCell: LoadFileEntry.selectedProperty()를 체크박스에 자동 바인딩
        fileListView.setCellFactory(CheckBoxListCell.forListView(LoadFileEntry::selectedProperty));

        fileListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, sel) -> { if (sel != null) showPreview(sel); });
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
        fileEntries.forEach(e -> e.setSelected(true));
    }

    @FXML
    private void onDeselectAll() {
        fileEntries.forEach(e -> e.setSelected(false));
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
     * 선택된 파일들을 소스 디렉토리에서 대상 프로젝트로 복사한다.
     * 상대 경로를 유지하므로 디렉토리 구조가 그대로 재현된다.
     */
    @FXML
    private void onLoad() {
        String targetPath = targetDirField.getText().trim();
        if (targetPath.isBlank()) {
            alert("대상 프로젝트 디렉토리를 선택하세요.");
            return;
        }
        if (sourceRoot == null || fileEntries.isEmpty()) {
            alert("소스 디렉토리를 먼저 스캔하세요.");
            return;
        }

        List<LoadFileEntry> selected = fileEntries.stream()
                .filter(LoadFileEntry::isSelected)
                .collect(Collectors.toList());

        if (selected.isEmpty()) {
            alert("로드할 파일을 선택하세요.");
            return;
        }

        Path targetRoot = Path.of(targetPath);
        List<String> copied = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (LoadFileEntry entry : selected) {
            Path src = sourceRoot.resolve(entry.getRelativePath());
            Path dest = targetRoot.resolve(entry.getRelativePath());
            try {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                copied.add(entry.getRelativePath());
            } catch (IOException e) {
                errors.add(entry.getRelativePath() + ": " + e.getMessage());
            }
        }

        StringBuilder msg = new StringBuilder();
        if (!copied.isEmpty())
            msg.append("✓ 완료 (").append(copied.size()).append("개):\n")
               .append(copied.stream().map(s -> "  " + s).collect(Collectors.joining("\n")))
               .append("\n\n");
        if (!errors.isEmpty())
            msg.append("✗ 오류 (").append(errors.size()).append("개):\n")
               .append(errors.stream().map(s -> "  " + s).collect(Collectors.joining("\n")));

        previewArea.setText(msg.toString());
    }

    private void alert(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait();
    }
}
