/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.model.plugin.PluginCommand;
import org.kyj.llmmanager.model.plugin.PluginSettingsField;
import org.kyj.llmmanager.model.plugin.PluginSettingsSection;
import org.kyj.llmmanager.model.plugin.PluginSettingsTab;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.application.Platform;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 로컬 플러그인 manifest 로드 상태와 권한, 환경변수 상태를 표시한다.
 */
public class PluginManagerDialog {
    private final Stage owner;

    public PluginManagerDialog(Stage owner) {
        this.owner = owner;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("플러그인 관리");

        var pluginManager = AppContext.getInstance().getPluginManager();
        List<LoadedPlugin> plugins = pluginManager != null
                ? pluginManager.getPlugins() : List.of();

        ListView<LoadedPlugin> pluginList = new ListView<>(
                FXCollections.observableArrayList(plugins));
        pluginList.setPrefWidth(260);
        pluginList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(LoadedPlugin item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                VBox box = new VBox(3);
                box.setPadding(new Insets(4, 0, 4, 0));
                Label name = new Label(pluginName(item));
                name.setStyle("-fx-font-weight: bold;");
                Label status = new Label(pluginStatus(item));
                applyStatusStyle(status, item);
                box.getChildren().addAll(name, status);
                setGraphic(box);
                setText(null);
            }
        });

        VBox detailBox = new VBox(12);
        detailBox.setPadding(new Insets(16));
        ScrollPane detailScroll = new ScrollPane(detailBox);
        detailScroll.setFitToWidth(true);

        TextArea installLogArea = new TextArea();
        installLogArea.setEditable(false);
        installLogArea.setWrapText(true);
        installLogArea.setPrefHeight(120);

        Button reloadBtn = new Button("새로고침");
        reloadBtn.setOnAction(e -> {
            if (pluginManager != null) {
                pluginManager.load();
                pluginList.setItems(FXCollections.observableArrayList(pluginManager.getPlugins()));
                if (!pluginList.getItems().isEmpty()) {
                    pluginList.getSelectionModel().selectFirst();
                }
            }
        });
        Button installBtn = new Button("의존성 설치");
        Button settingsBtn = new Button("환경 설정 열기");
        settingsBtn.setOnAction(e -> new SettingsDialog(stage).showAndWait());
        Button closeBtn = new Button("닫기");
        closeBtn.setPrefWidth(80);
        closeBtn.setDefaultButton(true);
        closeBtn.setOnAction(e -> stage.close());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, reloadBtn, installBtn, spacer, settingsBtn, closeBtn);
        toolbar.setPadding(new Insets(8, 14, 8, 14));
        toolbar.setAlignment(Pos.CENTER_RIGHT);

        VBox right = new VBox(detailScroll, new Separator(), installLogArea, new Separator(), toolbar);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        pluginList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> {
                    renderDetail(detailBox, selected);
                    installLogArea.clear();
                    installBtn.setDisable(!canInstallDependencies(selected));
                });
        if (!plugins.isEmpty()) {
            pluginList.getSelectionModel().selectFirst();
        } else {
            detailBox.getChildren().setAll(new Label("로드된 플러그인이 없습니다."));
            installBtn.setDisable(true);
        }
        installBtn.setOnAction(e -> installDependencies(
                pluginList.getSelectionModel().getSelectedItem(), installBtn, installLogArea));

        SplitPane split = new SplitPane(pluginList, right);
        split.setDividerPositions(0.32);
        VBox root = new VBox(split);
        VBox.setVgrow(split, Priority.ALWAYS);
        stage.setScene(SceneFactory.create(root, 820, 560));
        stage.setResizable(true);
        stage.show();
    }

    private boolean canInstallDependencies(LoadedPlugin plugin) {
        var installer = AppContext.getInstance().getPluginDependencyInstaller();
        return plugin != null && plugin.isValid() && installer != null && installer.canInstall(plugin);
    }

    private void installDependencies(LoadedPlugin plugin, Button installBtn, TextArea logArea) {
        var installer = AppContext.getInstance().getPluginDependencyInstaller();
        if (installer == null || !canInstallDependencies(plugin)) return;
        installBtn.setDisable(true);
        logArea.clear();
        installer.install(plugin, new org.kyj.llmmanager.service.PluginDependencyInstaller.ProgressCallback() {
            @Override
            public void onLog(String message) {
                Platform.runLater(() -> logArea.appendText(message + "\n"));
            }

            @Override
            public void onDone(boolean success) {
                Platform.runLater(() -> {
                    logArea.appendText(success ? "설치 완료\n" : "설치 실패\n");
                    installBtn.setDisable(false);
                });
            }
        });
    }

    private void renderDetail(VBox root, LoadedPlugin plugin) {
        root.getChildren().clear();
        if (plugin == null) return;

        Label title = new Label(pluginName(plugin));
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label state = new Label(pluginStatus(plugin));
        applyStatusStyle(state, plugin);
        root.getChildren().addAll(title, state, new Separator());

        GridPane info = grid();
        addRow(info, 0, "ID", plugin.getManifest() != null ? plugin.getManifest().getId() : "-");
        addRow(info, 1, "Version", plugin.getManifest() != null ? plugin.getManifest().getVersion() : "-");
        addRow(info, 2, "Type", plugin.getManifest() != null ? plugin.getManifest().getType() : "-");
        addRow(info, 3, "Path", plugin.getDirectory() != null ? plugin.getDirectory().toString() : "-");
        root.getChildren().add(info);

        if (!plugin.isValid()) {
            root.getChildren().add(section("오류"));
            VBox errors = new VBox(4);
            plugin.getErrors().forEach(error -> errors.getChildren().add(label(error, "text-danger")));
            root.getChildren().add(errors);
            return;
        }

        root.getChildren().add(section("권한"));
        String permissions = plugin.getManifest().getPermissions().isEmpty()
                ? "없음" : String.join(", ", plugin.getManifest().getPermissions());
        root.getChildren().add(label(permissions, "text-muted"));

        var installer = AppContext.getInstance().getPluginDependencyInstaller();
        if (installer != null && installer.canInstall(plugin)) {
            root.getChildren().add(section("의존성"));
            String scope = plugin.getManifest().getInstall().getScope();
            root.getChildren().add(label(installer.isInstalled(plugin)
                    ? "설치됨 (" + scope + ")" : "설치 필요 (" + scope + ")", "text-muted"));
        }

        List<PluginCommand> commands = plugin.getManifest().getContributes().getCommands();
        if (!commands.isEmpty()) {
            root.getChildren().add(section("명령"));
            GridPane commandGrid = grid();
            int commandRow = 0;
            for (PluginCommand command : commands) {
                addRow(commandGrid, commandRow++, command.getId(), commandSummary(command));
            }
            root.getChildren().add(commandGrid);
        }

        List<PluginSettingsField> envFields = envFields(plugin);
        if (!envFields.isEmpty()) {
            root.getChildren().add(section("환경변수"));
            GridPane envGrid = grid();
            int row = 0;
            for (PluginSettingsField field : envFields) {
                String detected = isEnvPresent(field.getKey()) ? "감지됨" : "미설정";
                addRow(envGrid, row++, field.getKey() + (field.isRequired() ? " *" : ""), detected);
            }
            root.getChildren().add(envGrid);
        }

        List<PluginSettingsTab> tabs = plugin.getManifest().getContributes().getSettingsTabs();
        if (!tabs.isEmpty()) {
            root.getChildren().add(section("설정 탭"));
            String tabNames = tabs.stream()
                    .map(t -> t.getTitle() + " (" + t.getId() + ")")
                    .collect(Collectors.joining(", "));
            root.getChildren().add(label(tabNames, "text-muted"));
        }
    }

    private String pluginName(LoadedPlugin plugin) {
        if (plugin.getManifest() == null || plugin.getManifest().getName().isBlank()) {
            return plugin.getDirectory() != null ? plugin.getDirectory().getFileName().toString() : "(unknown)";
        }
        return plugin.getManifest().getName();
    }

    private String pluginStatus(LoadedPlugin plugin) {
        if (!plugin.isValid()) return "오류";
        List<String> missing = missingRequiredEnv(plugin);
        return missing.isEmpty() ? "활성" : "설정 필요: " + String.join(", ", missing);
    }

    private void applyStatusStyle(Label label, LoadedPlugin plugin) {
        label.setStyle("-fx-font-size: 11px;");
        if (!plugin.isValid()) {
            label.getStyleClass().add("text-danger");
        } else if (missingRequiredEnv(plugin).isEmpty()) {
            label.getStyleClass().add("text-success");
        } else {
            label.setStyle("-fx-text-fill: #f9e2af; -fx-font-size: 11px;");
        }
    }

    private List<String> missingRequiredEnv(LoadedPlugin plugin) {
        return envFields(plugin).stream()
                .filter(PluginSettingsField::isRequired)
                .map(PluginSettingsField::getKey)
                .filter(key -> !isEnvPresent(key))
                .toList();
    }

    private List<PluginSettingsField> envFields(LoadedPlugin plugin) {
        List<PluginSettingsField> result = new ArrayList<>();
        if (plugin.getManifest() == null) return result;
        for (PluginSettingsTab tab : plugin.getManifest().getContributes().getSettingsTabs()) {
            for (PluginSettingsSection section : tab.getSections()) {
                for (PluginSettingsField field : section.getFields()) {
                    if ("env".equalsIgnoreCase(field.getKind())) {
                        result.add(field);
                    }
                }
            }
        }
        return result;
    }

    private boolean isEnvPresent(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }

    private String commandSummary(PluginCommand command) {
        List<String> parts = new ArrayList<>();
        if (command.getTitle() != null && !command.getTitle().isBlank()) {
            parts.add(command.getTitle());
        }
        if (command.getRequires().isCwd()) {
            parts.add("cwd 필요");
        }
        if (!command.getRequires().getEnv().isEmpty()) {
            parts.add("env: " + String.join(", ", command.getRequires().getEnv()));
        }
        return parts.isEmpty() ? "-" : String.join(" / ", parts);
    }

    private GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        return grid;
    }

    private void addRow(GridPane grid, int row, String key, String value) {
        Label k = label(key, "text-muted");
        Label v = new Label(value);
        v.setWrapText(true);
        grid.add(k, 0, row);
        grid.add(v, 1, row);
    }

    private Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
