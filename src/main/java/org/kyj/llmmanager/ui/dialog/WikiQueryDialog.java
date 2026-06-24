/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.ui.dialog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.service.PluginCommandExecutor.PluginCommandRequest;
import org.kyj.llmmanager.service.PluginManager.PluginCommandContribution;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 위키 질의(wiki.query)를 CLI 세션처럼 실행하는 다이얼로그.
 *
 * 과거 질문·응답이 터미널 스크롤백처럼 위로 쌓이고, 하단 입력창으로 이어서
 * 질의한다. 이력은 워크스페이스의 .llm-manager/query-history.json에 영속화되어
 * 창을 다시 열어도 복원된다. query.py는 호출마다 독립 실행(stateless)이므로
 * 이력은 UI 차원의 기록일 뿐 다음 질의의 컨텍스트로 전달되지 않는다.
 */
public class WikiQueryDialog {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String HISTORY_DIR = ".llm-manager";
    private static final String HISTORY_FILE = "query-history.json";

    private final Stage owner;
    private final PluginCommandContribution contribution;

    public WikiQueryDialog(Stage owner, PluginCommandContribution contribution) {
        this.owner = owner;
        this.contribution = contribution;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("위키 질의 (Query)");

        var ctx = AppContext.getInstance();
        var settings = ctx.getAppSettingsRepository().get();

        String defaultCwd = settings.getPluginSetting(contribution.pluginId(), "wiki.defaultCwd", "");
        TextField workspaceField = new TextField(defaultCwd);
        workspaceField.setEditable(false);
        workspaceField.setPromptText("설정 > wiki-agent > 기본 워크스페이스에서 지정");
        HBox workspaceRow = new HBox(8, new Label("워크스페이스:"), workspaceField);
        workspaceRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(workspaceField, Priority.ALWAYS);

        TextArea historyArea = new TextArea();
        historyArea.setEditable(false);
        historyArea.setWrapText(true);
        historyArea.setStyle("-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:12px;");

        TextField inputField = new TextField();
        inputField.setPromptText("질문 입력... (Enter로 전송 — 질의는 매번 독립 실행되며 이전 문답은 컨텍스트로 전달되지 않음)");
        Button runBtn = new Button("질의");
        runBtn.setPrefWidth(80);
        Button stopBtn = new Button("중지");
        stopBtn.setPrefWidth(70);
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> {
            if (ctx.getPluginCommandExecutor().cancel("wiki.query")) {
                appendLine(historyArea, "중지 요청됨 — 프로세스를 종료합니다.");
            }
        });
        CheckBox saveCheck = new CheckBox("응답을 wiki/syntheses/ 에 저장 (--save)");

        // 워크스페이스가 바뀌면 해당 워크스페이스의 이력을 다시 로드
        workspaceField.textProperty().addListener((obs, old, value) ->
                historyArea.setText(renderHistory(loadHistory(value))));
        historyArea.setText(renderHistory(loadHistory(workspaceField.getText())));

        Stage[] stageRef = {stage};
        Runnable submit = () -> {
            String question = inputField.getText().trim();
            String workspace = workspaceField.getText().trim();
            if (question.isBlank() || workspace.isBlank()) return;

            Path wsPath = Path.of(workspace).toAbsolutePath().normalize();
            if (!org.kyj.llmmanager.service.WikiWorkspaceInitializer.isInitialized(wsPath)) {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.CONFIRMATION);
                alert.initOwner(stageRef[0]);
                alert.setTitle("위키 골격 초기화");
                alert.setHeaderText("이 디렉토리에 위키 골격(wiki/index.md)이 없습니다.");
                alert.setContentText("raw/, wiki/, graph/ 골격을 지금 생성할까요?\n" + wsPath);
                var answer = alert.showAndWait();
                if (answer.isEmpty() || answer.get() != javafx.scene.control.ButtonType.OK) {
                    appendLine(historyArea, "[취소] 위키 골격이 필요합니다.");
                    return;
                }
                try {
                    org.kyj.llmmanager.service.WikiWorkspaceInitializer.initialize(wsPath);
                    appendLine(historyArea, "위키 골격을 초기화했습니다: " + wsPath);
                } catch (Exception ex) {
                    appendLine(historyArea, "[오류] 골격 초기화 실패: " + ex.getMessage());
                    return;
                }
            }

            org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                    ctx.getAppSettingsRepository(), workspace);
            inputField.clear();
            runBtn.setDisable(true);
            stopBtn.setDisable(false);
            inputField.setDisable(true);

            appendLine(historyArea, "[" + LocalDateTime.now().format(TS) + "] > " + question);
            appendLine(historyArea, "");

            Map<String, String> options = new LinkedHashMap<>();
            options.put("save", String.valueOf(saveCheck.isSelected()));

            new Thread(() -> {
                var result = ctx.getPluginCommandExecutor().executeStreaming(
                        contribution.pluginId(),
                        contribution.command(),
                        new PluginCommandRequest(workspace, question, null, null, options),
                        line -> Platform.runLater(() -> appendLine(historyArea, line)));
                Platform.runLater(() -> {
                    if (!result.success()) {
                        appendLine(historyArea, "[오류] " + result.message());
                    }
                    appendLine(historyArea, "─".repeat(60));
                    if (result.success()) {
                        saveHistoryEntry(workspace, question, result.message());
                    }
                    runBtn.setDisable(false);
                    stopBtn.setDisable(true);
                    inputField.setDisable(false);
                    inputField.requestFocus();
                });
            }, "wiki-query").start();
        };
        runBtn.setOnAction(e -> submit.run());
        inputField.setOnAction(e -> submit.run());

        HBox inputRow = new HBox(8, inputField, runBtn, stopBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeBtn = new Button("닫기");
        closeBtn.setOnAction(e -> stage.close());
        HBox bottomRow = new HBox(8, saveCheck, spacer, closeBtn);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, workspaceRow, historyArea, inputRow, bottomRow);
        root.setPadding(new Insets(14));
        VBox.setVgrow(historyArea, Priority.ALWAYS);

        stage.setScene(SceneFactory.create(root, 760, 620));
        stage.setResizable(true);
        stage.show();
        inputField.requestFocus();
    }

    private void appendLine(TextArea area, String line) {
        if (!area.getText().isEmpty()) area.appendText("\n");
        area.appendText(line != null ? line : "");
        area.setScrollTop(Double.MAX_VALUE);
    }

    /** 저장된 문답 한 건. answer는 query.py의 전체 출력. */
    public record QueryHistoryEntry(String timestamp, String question, String answer) {}

    private Path historyPath(String workspace) {
        return Path.of(workspace).resolve(HISTORY_DIR).resolve(HISTORY_FILE);
    }

    private List<QueryHistoryEntry> loadHistory(String workspace) {
        if (workspace == null || workspace.isBlank()) return List.of();
        Path file = historyPath(workspace.trim());
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<QueryHistoryEntry>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private void saveHistoryEntry(String workspace, String question, String answer) {
        try {
            List<QueryHistoryEntry> entries = new ArrayList<>(loadHistory(workspace));
            entries.add(new QueryHistoryEntry(
                    LocalDateTime.now().format(TS), question, answer != null ? answer : ""));
            Path file = historyPath(workspace.trim());
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 이력 저장 실패는 질의 자체의 성패와 무관 — 무시
        }
    }

    private String renderHistory(List<QueryHistoryEntry> entries) {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (QueryHistoryEntry entry : entries) {
            if (sb.length() > 0) sb.append('\n');
            sb.append('[').append(entry.timestamp()).append("] > ").append(entry.question()).append('\n');
            sb.append('\n').append(entry.answer()).append('\n');
            sb.append("─".repeat(60));
        }
        return sb.toString();
    }
}
