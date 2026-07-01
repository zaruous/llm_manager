/*
 * 작성자 : kyj
 * 작성일 : 2026-06-25
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.service.WikiVectorRepository;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 워크스페이스의 위키 벡터 색인 상태를 조회하는 다이얼로그.
 *
 * 선택 디렉토리의 {@code .llm-manager/wiki-vector.sqlite}를 읽어
 * 색인된 페이지 목록과 페이지별 청크 수, 전체 요약을 표시한다.
 * 조회 전용이며 색인을 변경하지 않는다.
 */
public class WikiIndexStatusDialog {

    /** 색인 DB 파일의 워크스페이스 상대 경로. WikiVectorRepository와 동일해야 한다. */
    private static final String DB_RELATIVE_PATH = ".llm-manager/wiki-vector.sqlite";

    private final Stage owner;

    /** 페이지 한 건의 색인 상태 행. */
    public record PageRow(String pagePath, int chunkCount) {}

    /**
     * @param owner 오너 Stage
     */
    public WikiIndexStatusDialog(Stage owner) {
        this.owner = owner;
    }

    /** 다이얼로그를 표시하고 닫힐 때까지 대기한다. */
    public void showAndWait() {
        Stage stage = new Stage();
        stage.setTitle("위키 색인 상태");

        // ── 워크스페이스 선택 행 ─────────────────────────────────
        TextField dirField = new TextField(defaultWorkspace());
        dirField.setPromptText("위키 워크스페이스 디렉토리");
        HBox.setHgrow(dirField, Priority.ALWAYS);

        Button browseBtn = new Button("찾아보기...");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("위키 워크스페이스 선택");
            File current = new File(dirField.getText().trim());
            if (current.isDirectory()) chooser.setInitialDirectory(current);
            File dir = chooser.showDialog(stage);
            if (dir != null) dirField.setText(dir.getAbsolutePath());
        });

        Button loadBtn = new Button("조회");
        HBox dirRow = new HBox(8, dirField, browseBtn, loadBtn);
        dirRow.setAlignment(Pos.CENTER_LEFT);

        // ── 결과 테이블 ─────────────────────────────────────────
        TableView<PageRow> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("조회 버튼을 눌러 색인 상태를 확인하세요."));

        TableColumn<PageRow, String> pathCol = new TableColumn<>("페이지 경로");
        pathCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().pagePath()));
        pathCol.setPrefWidth(420);

        TableColumn<PageRow, Integer> countCol = new TableColumn<>("청크 수");
        countCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().chunkCount()));
        countCol.setPrefWidth(90);
        countCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        table.getColumns().add(pathCol);
        table.getColumns().add(countCol);
        VBox.setVgrow(table, Priority.ALWAYS);

        Label summary = new Label("");
        summary.getStyleClass().add("text-muted");

        loadBtn.setOnAction(e -> loadStatus(dirField.getText().trim(), table, summary, stage));

        Button closeBtn = new Button("닫기");
        closeBtn.setOnAction(e -> stage.close());
        HBox bottom = new HBox(summary, spacer(), closeBtn);
        bottom.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, dirRow, table, bottom);
        root.setPadding(new Insets(14));

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setScene(SceneFactory.create(root, 620, 440));
        stage.showAndWait();
    }

    /**
     * 선택 디렉토리의 색인 DB를 읽어 테이블과 요약 레이블을 갱신한다.
     * DB 파일이 없으면 색인 없음을 안내하고 DB를 생성하지 않는다.
     */
    private void loadStatus(String workspaceText, TableView<PageRow> table,
                            Label summary, Stage stage) {
        table.getItems().clear();
        summary.setText("");

        if (workspaceText.isBlank()) {
            alert(stage, "워크스페이스 디렉토리를 선택하세요.");
            return;
        }
        Path workspace = Path.of(workspaceText);
        if (!Files.isDirectory(workspace)) {
            alert(stage, "디렉토리가 존재하지 않습니다:\n" + workspace);
            return;
        }

        Path dbFile = workspace.resolve(DB_RELATIVE_PATH);
        // 존재 확인을 먼저 해 조회만으로 빈 DB가 생성되는 부작용을 막는다
        if (!Files.isRegularFile(dbFile)) {
            table.setPlaceholder(new Label("색인 DB가 없습니다. 아직 색인이 실행되지 않은 워크스페이스입니다."));
            summary.setText("색인 없음 — " + dbFile);
            return;
        }

        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, vec0Path())) {
            Set<String> pages = repo.getIndexedPagePaths();
            long totalChunks = repo.countChunks();
            for (String page : pages) {
                table.getItems().add(new PageRow(page, repo.getChunkHashes(page).size()));
            }
            summary.setText("페이지 " + pages.size() + "개 · 청크 " + totalChunks + "개 색인됨");
            if (pages.isEmpty()) {
                table.setPlaceholder(new Label("색인 DB는 있으나 색인된 페이지가 없습니다."));
            }
        } catch (Exception ex) {
            alert(stage, "색인 상태 조회 실패:\n" + ex.getMessage());
        }
    }

    /** 설정에 저장된 기본 워크스페이스(wiki.defaultCwd)를 반환한다. 없으면 빈 문자열. */
    private String defaultWorkspace() {
        AppSettings settings = AppContext.getInstance().getAppSettingsRepository().get();
        return settings.getPluginSetting("wiki-agent", "wiki.defaultCwd", "");
    }

    /** 설정에 저장된 vec0 라이브러리 경로(wiki.vec0Path)를 반환한다. 없으면 빈 문자열. */
    private String vec0Path() {
        AppSettings settings = AppContext.getInstance().getAppSettingsRepository().get();
        return settings.getPluginSetting("wiki-agent", "wiki.vec0Path", "");
    }

    private static javafx.scene.layout.Region spacer() {
        javafx.scene.layout.Region region = new javafx.scene.layout.Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void alert(Stage stage, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.showAndWait();
    }
}
