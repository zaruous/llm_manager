/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.service.PluginCommandExecutor.PluginCommandRequest;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * 플러그인 command 실행 입력을 받는 다이얼로그.
 */
public class PluginCommandRunDialog {
    private final Stage owner;

    public PluginCommandRunDialog(Stage owner) {
        this.owner = owner;
    }

    public void show() {
        show(null);
    }

    /**
     * 다이얼로그를 열고 지정한 command를 선택 상태로 표시한다.
     * 플러그인 메뉴의 command 항목에서 바로 진입할 때 사용한다.
     *
     * @param preselectCommandId 미리 선택할 command id. null이거나 목록에 없으면 첫 항목 선택
     */
    public void show(String preselectCommandId) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("플러그인 실행");

        var ctx = AppContext.getInstance();
        List<PluginCommandContribution> commands = ctx.getPluginManager() != null
                ? ctx.getPluginManager().getCommands() : List.of();

        ListView<PluginCommandContribution> commandList = new ListView<>(
                FXCollections.observableArrayList(commands));
        commandList.setPrefHeight(190);
        commandList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PluginCommandContribution item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                VBox box = new VBox(3);
                box.setPadding(new Insets(4, 0, 4, 0));
                Label title = new Label(item.command().getTitle());
                title.setStyle("-fx-font-weight: bold;");
                Label plugin = new Label(item.pluginName());
                plugin.getStyleClass().add("text-muted");
                box.getChildren().addAll(title, plugin);
                setGraphic(box);
                setText(null);
            }
        });

        VBox form = new VBox(12);
        form.setPadding(new Insets(16));

        TextArea terminalArea = new TextArea();
        terminalArea.setEditable(false);
        terminalArea.setWrapText(false);
        terminalArea.setStyle("-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:12px;");

        TextField cwdField = new TextField();
        TextArea promptArea = new TextArea();
        promptArea.setPromptText("실행할 작업을 입력하세요.");
        promptArea.setPrefRowCount(6);
        promptArea.setWrapText(true);
        ComboBox<String> modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll("auto", "composer-2");
        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("sdk", "cli");

        Button runBtn = new Button("실행");
        runBtn.setPrefWidth(90);
        Button clearBtn = new Button("지우기");
        clearBtn.setPrefWidth(80);
        clearBtn.setOnAction(e -> terminalArea.clear());
        Button closeBtn = new Button("닫기");
        closeBtn.setPrefWidth(80);
        closeBtn.setOnAction(e -> stage.close());

        TreeView<Path> treeView = new TreeView<>();
        treeView.setShowRoot(true);
        treeView.setPrefHeight(360);
        treeView.setCellFactory(tv -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                Path name = item.getFileName();
                setText(name != null ? name.toString() : item.toString());
            }
        });

        commandList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> renderCommandForm(
                        stage, form, selected, cwdField, promptArea, modelCombo, modeCombo, terminalArea, treeView));
        if (!commands.isEmpty()) {
            int preselectIndex = 0;
            if (preselectCommandId != null) {
                for (int i = 0; i < commands.size(); i++) {
                    if (preselectCommandId.equals(commands.get(i).command().getId())) {
                        preselectIndex = i;
                        break;
                    }
                }
            }
            commandList.getSelectionModel().select(preselectIndex);
        } else {
            form.getChildren().setAll(new Label("실행 가능한 플러그인 command가 없습니다."));
            runBtn.setDisable(true);
        }

        cwdField.textProperty().addListener((obs, old, value) -> refreshTree(treeView, value));

        runBtn.setOnAction(e -> {
            PluginCommandContribution selected = commandList.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            runBtn.setDisable(true);
            new Thread(() -> {
                var result = ctx.getPluginCommandExecutor().executeStreaming(
                        selected.pluginId(),
                        selected.command(),
                        new PluginCommandRequest(
                                cwdField.getText().trim(),
                                promptArea.getText(),
                                modelCombo.getValue(),
                                modeCombo.getValue(),
                                new LinkedHashMap<>()),
                        message -> Platform.runLater(() -> appendTerminal(terminalArea, message)));
                Platform.runLater(() -> {
                    if (!result.success() && (terminalArea.getText() == null
                            || !terminalArea.getText().contains(result.message()))) {
                        appendTerminal(terminalArea, result.message());
                    }
                    runBtn.setDisable(false);
                });
            }, "plugin-command-run").start();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, clearBtn, runBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(8, 14, 8, 14));

        VBox left = new VBox(10,
                section("명령"),
                commandList,
                new Separator(),
                section("작업 디렉토리"),
                treeView);
        left.setPadding(new Insets(12));
        left.setPrefWidth(320);
        VBox.setVgrow(treeView, Priority.ALWAYS);

        VBox right = new VBox(form, new Separator(), terminalArea, new Separator(), buttons);
        VBox.setVgrow(terminalArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.32);
        VBox root = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        stage.setScene(SceneFactory.create(root, 920, 680));
        stage.setResizable(true);
        stage.show();
    }

    private void renderCommandForm(Stage stage, VBox form, PluginCommandContribution contribution,
                                   TextField cwdField, TextArea promptArea,
                                   ComboBox<String> modelCombo, ComboBox<String> modeCombo,
                                   TextArea terminalArea, TreeView<Path> treeView) {
        form.getChildren().clear();
        if (contribution == null) return;

        var settings = AppContext.getInstance().getAppSettingsRepository().get();
        // 플러그인별 기본 작업 디렉토리 — cursor 키 우선, 없으면 wiki 키로 폴백
        String defaultCwd = settings.getPluginSetting(contribution.pluginId(), "cursor.defaultCwd", "");
        if (defaultCwd.isBlank()) {
            defaultCwd = settings.getPluginSetting(contribution.pluginId(), "wiki.defaultCwd", "");
        }
        cwdField.setText(defaultCwd);
        modelCombo.setValue(settings.getPluginSetting(contribution.pluginId(), "cursor.defaultModel", "auto"));
        modeCombo.setValue(settings.getPluginSetting(contribution.pluginId(), "cursor.mode", "sdk"));
        refreshTree(treeView, cwdField.getText());

        Label title = new Label(contribution.command().getTitle());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label plugin = new Label(contribution.pluginName() + " / " + contribution.command().getId());
        plugin.getStyleClass().add("text-muted");
        form.getChildren().addAll(title, plugin, new Separator());

        GridPane grid = formGrid();
        int row = 0;
        if (contribution.command().getRequires().isCwd()) {
            Button browseBtn = new Button("찾기");
            browseBtn.setPrefWidth(56);
            browseBtn.setOnAction(e -> chooseDirectory(stage, cwdField));
            addGridRow(grid, row++, "작업 디렉토리:", cwdField, browseBtn);
        }
        addGridRow(grid, row++, "모델:", modelCombo, null);
        addGridRow(grid, row, "모드:", modeCombo, null);
        form.getChildren().add(grid);

        if (!contribution.command().getRequires().getEnv().isEmpty()) {
            form.getChildren().add(section("필수 환경변수"));
            GridPane envGrid = formGrid();
            int envRow = 0;
            for (String env : contribution.command().getRequires().getEnv()) {
                Label state = new Label(isEnvPresent(env) ? "감지됨" : "미설정");
                state.getStyleClass().add(isEnvPresent(env) ? "text-success" : "text-danger");
                addGridRow(envGrid, envRow++, env + ":", state, null);
            }
            form.getChildren().add(envGrid);
        }

        form.getChildren().add(section("프롬프트"));
        form.getChildren().add(promptArea);
    }

    private GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints label = new ColumnConstraints(120);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        ColumnConstraints button = new ColumnConstraints(64);
        grid.getColumnConstraints().addAll(label, field, button);
        return grid;
    }

    private void addGridRow(GridPane grid, int row, String labelText,
                            javafx.scene.Node field, javafx.scene.Node button) {
        Label label = new Label(labelText);
        label.getStyleClass().add("text-muted");
        grid.add(label, 0, row);
        grid.add(field, 1, row);
        if (button != null) grid.add(button, 2, row);
    }

    private Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private void chooseDirectory(Stage stage, TextField target) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("작업 디렉토리 선택");
        String current = target.getText().trim();
        if (!current.isBlank()) {
            File dir = new File(current);
            if (dir.isDirectory()) chooser.setInitialDirectory(dir);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) target.setText(selected.getAbsolutePath());
    }

    private void refreshTree(TreeView<Path> treeView, String cwd) {
        if (treeView == null) return;
        if (cwd == null || cwd.isBlank()) {
            treeView.setRoot(null);
            return;
        }
        Path root = Path.of(cwd).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            treeView.setRoot(new TreeItem<>(root));
            return;
        }
        treeView.setRoot(buildTree(root, 0));
    }

    private TreeItem<Path> buildTree(Path dir, int depth) {
        TreeItem<Path> item = new TreeItem<>(dir);
        item.setExpanded(depth < 1);
        if (depth >= 2) return item;
        try (Stream<Path> paths = Files.list(dir)) {
            paths
                    .filter(this::isVisibleTreePath)
                    .sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .limit(80)
                    .forEach(path -> {
                        if (Files.isDirectory(path)) item.getChildren().add(buildTree(path, depth + 1));
                        else item.getChildren().add(new TreeItem<>(path));
                    });
        } catch (Exception ignored) {
        }
        return item;
    }

    private boolean isVisibleTreePath(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) return false;
        String name = fileNamePath.toString();
        return !name.equals(".git")
                && !name.equals("node_modules")
                && !name.equals("build")
                && !name.equals(".gradle")
                && !name.equals("target")
                && !name.equals("dist")
                && !name.equals("__pycache__");
    }

    private void appendTerminal(TextArea terminalArea, String message) {
        if (terminalArea.getText().length() > 0) {
            terminalArea.appendText("\n");
        }
        terminalArea.appendText(message != null ? message : "");
        terminalArea.setScrollTop(Double.MAX_VALUE);
    }

    private boolean isEnvPresent(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }
}
