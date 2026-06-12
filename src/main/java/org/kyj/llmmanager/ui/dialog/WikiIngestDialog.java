/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.service.PluginCommandExecutor.PluginCommandRequest;
import org.kyj.llmmanager.service.PluginManager.PluginCommandContribution;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * 문서 수집(wiki.ingest) 다이얼로그. 파일 멀티 선택과 폴더 추가를 지원한다.
 *
 * 선택 항목을 워크스페이스의 raw/<하위분류>/로 복사한 뒤 한 번의 ingest.py
 * 호출로 전체를 전달한다 (ingest.py는 다중 경로 인자를 지원).
 */
public class WikiIngestDialog {
    private final Stage owner;
    private final PluginCommandContribution contribution;

    public WikiIngestDialog(Stage owner, PluginCommandContribution contribution) {
        this.owner = owner;
        this.contribution = contribution;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("문서 수집 (Ingest)");

        var ctx = AppContext.getInstance();
        var settings = ctx.getAppSettingsRepository().get();

        TextField workspaceField = new TextField(
                settings.getPluginSetting(contribution.pluginId(), "wiki.defaultCwd", ""));
        workspaceField.setPromptText("위키 워크스페이스 디렉토리");
        Button browseBtn = new Button("찾기");
        browseBtn.setOnAction(e -> chooseDirectoryInto(stage, workspaceField, "위키 워크스페이스 선택"));
        HBox workspaceRow = new HBox(8, new Label("워크스페이스:"), workspaceField, browseBtn);
        workspaceRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(workspaceField, Priority.ALWAYS);

        ObservableList<Path> selections = FXCollections.observableArrayList();
        ListView<Path> selectionList = new ListView<>(selections);
        selectionList.setPrefHeight(170);

        Label countLabel = new Label("총 0개 파일 — 파일 수에 비례해 LLM 호출이 발생합니다");
        selections.addListener((javafx.collections.ListChangeListener<Path>) change ->
                countLabel.setText("총 " + countFiles(selections)
                        + "개 파일 — 파일 수에 비례해 LLM 호출이 발생합니다"));

        Button addFilesBtn = new Button("파일 추가");
        addFilesBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("수집할 파일 선택 (멀티 선택 가능)");
            List<File> files = chooser.showOpenMultipleDialog(stage);
            if (files != null) files.forEach(f -> addUnique(selections, f.toPath()));
        });
        Button addDirBtn = new Button("폴더 추가");
        addDirBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("수집할 폴더 선택");
            File dir = chooser.showDialog(stage);
            if (dir != null) addUnique(selections, dir.toPath());
        });
        Button removeBtn = new Button("선택 제거");
        removeBtn.setOnAction(e -> {
            Path selected = selectionList.getSelectionModel().getSelectedItem();
            if (selected != null) selections.remove(selected);
        });
        HBox selectionButtons = new HBox(8, addFilesBtn, addDirBtn, removeBtn);

        ComboBox<String> categoryCombo = new ComboBox<>(
                FXCollections.observableArrayList("papers", "articles", "notes", "meetings"));
        categoryCombo.setEditable(true);
        categoryCombo.setValue("articles");
        HBox categoryRow = new HBox(8, new Label("raw/ 하위 분류:"), categoryCombo);
        categoryRow.setAlignment(Pos.CENTER_LEFT);

        TextArea terminalArea = new TextArea();
        terminalArea.setEditable(false);
        terminalArea.setWrapText(false);
        terminalArea.setStyle("-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:12px;");

        Button runBtn = new Button("수집 시작");
        runBtn.setPrefWidth(100);
        Button stopBtn = new Button("중지");
        stopBtn.setPrefWidth(70);
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> {
            if (AppContext.getInstance().getPluginCommandExecutor().cancel("wiki.ingest")) {
                appendLine(terminalArea, "중지 요청됨 — 프로세스를 종료합니다.");
            }
        });
        Button closeBtn = new Button("닫기");
        closeBtn.setOnAction(e -> stage.close());

        runBtn.setOnAction(e -> {
            String workspace = workspaceField.getText().trim();
            String category = categoryCombo.getValue() != null
                    ? categoryCombo.getValue().trim() : "";
            if (workspace.isBlank() || selections.isEmpty() || category.isBlank()) {
                appendLine(terminalArea, "워크스페이스·분류·수집 대상이 모두 필요합니다.");
                return;
            }
            // 골격 없는 디렉토리는 스킬 설치 화면을 거치지 않고 즉석 초기화 제안
            if (!ensureWorkspaceInitialized(stage, workspace, terminalArea)) return;
            org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                    AppContext.getInstance().getAppSettingsRepository(), workspace);
            runBtn.setDisable(true);
            stopBtn.setDisable(false);

            new Thread(() -> {
                try {
                    // 선택 항목을 raw/<분류>/로 복사 — raw는 불변 원본 저장소이므로
                    // 외부 파일을 워크스페이스 안으로 들여온 뒤 ingest에 전달한다
                    List<Path> copied = copyIntoRaw(selections, Path.of(workspace), category,
                            line -> Platform.runLater(() -> appendLine(terminalArea, line)));
                    if (copied.isEmpty()) {
                        Platform.runLater(() -> {
                            appendLine(terminalArea, "복사된 파일이 없습니다.");
                            runBtn.setDisable(false);
                            stopBtn.setDisable(true);
                        });
                        return;
                    }
                    StringBuilder prompt = new StringBuilder();
                    for (Path path : copied) prompt.append(path).append('\n');

                    var result = AppContext.getInstance().getPluginCommandExecutor().executeStreaming(
                            contribution.pluginId(),
                            contribution.command(),
                            new PluginCommandRequest(workspace, prompt.toString(),
                                    null, null, new LinkedHashMap<>()),
                            line -> Platform.runLater(() -> appendLine(terminalArea, line)));
                    Platform.runLater(() -> {
                        if (!result.success()) appendLine(terminalArea, "[오류] " + result.message());
                        else appendLine(terminalArea, "수집 완료.");
                        runBtn.setDisable(false);
                        stopBtn.setDisable(true);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        appendLine(terminalArea, "[오류] " + ex.getMessage());
                        runBtn.setDisable(false);
                        stopBtn.setDisable(true);
                    });
                }
            }, "wiki-ingest").start();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, countLabel, spacer, runBtn, stopBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10,
                workspaceRow,
                new Label("수집할 파일·폴더  (md/pdf/docx/html/txt 등 — 비마크다운은 자동 변환)"),
                selectionButtons,
                selectionList,
                categoryRow,
                terminalArea,
                buttons);
        root.setPadding(new Insets(14));
        VBox.setVgrow(terminalArea, Priority.ALWAYS);

        stage.setScene(SceneFactory.create(root, 720, 640));
        stage.setResizable(true);
        stage.show();
    }

    /**
     * 워크스페이스에 위키 골격이 없으면 초기화를 제안하고 생성한다.
     *
     * @return 골격이 준비되어 수집을 진행해도 되면 true
     */
    private boolean ensureWorkspaceInitialized(Stage stage, String workspace, TextArea terminalArea) {
        Path root = Path.of(workspace).toAbsolutePath().normalize();
        if (org.kyj.llmmanager.service.WikiWorkspaceInitializer.isInitialized(root)) return true;

        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("위키 골격 초기화");
        alert.setHeaderText("이 디렉토리에 위키 골격(wiki/index.md)이 없습니다.");
        alert.setContentText("raw/, wiki/, graph/ 골격을 지금 생성할까요?\n" + root);
        var result = alert.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            appendLine(terminalArea, "수집 취소 — 위키 골격이 필요합니다.");
            return false;
        }
        try {
            org.kyj.llmmanager.service.WikiWorkspaceInitializer.initialize(root);
            appendLine(terminalArea, "위키 골격을 초기화했습니다: " + root);
            return true;
        } catch (Exception ex) {
            appendLine(terminalArea, "[오류] 골격 초기화 실패: " + ex.getMessage());
            return false;
        }
    }

    private void addUnique(ObservableList<Path> selections, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!selections.contains(normalized)) selections.add(normalized);
    }

    /** 선택 목록의 총 파일 수 (폴더는 하위 일반 파일을 재귀 집계). */
    private long countFiles(List<Path> selections) {
        long count = 0;
        for (Path path : selections) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    count += walk.filter(Files::isRegularFile).count();
                } catch (IOException ignored) {
                }
            } else {
                count++;
            }
        }
        return count;
    }

    /**
     * 선택 항목을 raw/<category>/로 복사하고 복사된 파일 경로 목록을 반환한다.
     * 이미 워크스페이스 내부에 있는 파일은 복사하지 않고 그대로 전달한다.
     */
    private List<Path> copyIntoRaw(List<Path> selections, Path workspace, String category,
                                   java.util.function.Consumer<String> onOutput) throws IOException {
        Path rawDir = workspace.resolve("raw").resolve(category);
        Files.createDirectories(rawDir);
        List<Path> result = new ArrayList<>();

        for (Path source : selections) {
            if (source.toAbsolutePath().normalize().startsWith(workspace.toAbsolutePath().normalize())) {
                result.add(source);
                continue;
            }
            if (Files.isDirectory(source)) {
                try (Stream<Path> walk = Files.walk(source)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        Path target = rawDir.resolve(source.getFileName())
                                .resolve(source.relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        result.add(target);
                    }
                }
                onOutput.accept("복사: " + source + " → " + rawDir.resolve(source.getFileName()));
            } else {
                Path target = rawDir.resolve(source.getFileName());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                result.add(target);
                onOutput.accept("복사: " + source + " → " + target);
            }
        }
        return result;
    }

    private void chooseDirectoryInto(Stage stage, TextField target, String title) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        String current = target.getText().trim();
        if (!current.isBlank()) {
            File dir = new File(current);
            if (dir.isDirectory()) chooser.setInitialDirectory(dir);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) target.setText(selected.getAbsolutePath());
    }

    private void appendLine(TextArea area, String line) {
        if (!area.getText().isEmpty()) area.appendText("\n");
        area.appendText(line != null ? line : "");
        area.setScrollTop(Double.MAX_VALUE);
    }
}
