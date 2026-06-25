/*
 * 작성자 : kyj
 * 작성일 : 2026-06-24
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.service.WikiIndexService;
import org.kyj.llmmanager.service.WikiIndexService.PageIndexState;
import org.kyj.llmmanager.service.WikiIndexService.WorkspaceIndexMetadata;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * 위키 워크스페이스의 벡터 색인 상태를 페이지별로 조회하는 다이얼로그.
 *
 * 설정 탭 하단 "선택 디렉토리 색인 상태 확인" 버튼에서 열린다.
 * WikiIndexService.inspectWorkspace()를 호출해 CURRENT/STALE/NOT_INDEXED/ORPHANED
 * 상태를 테이블로 표시한다.
 */
public class WikiIndexStatusDialog {

    private final Stage owner;

    /**
     * @param owner 부모 Stage (모달 기준)
     */
    public WikiIndexStatusDialog(Stage owner) {
        this.owner = owner;
    }

    /**
     * 색인 상태 다이얼로그를 열고 닫힐 때까지 대기한다.
     */
    public void showAndWait() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Wiki 색인 상태 확인");

        // 워크스페이스 경로 필드
        AppSettings settings = AppContext.getInstance().getAppSettingsRepository().get();
        String defaultCwd = settings.getPluginSetting("wiki-agent", "wiki.defaultCwd", "");

        TextField pathField = new TextField(defaultCwd);
        pathField.setPromptText("위키 워크스페이스 경로");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button browseBtn = new Button("찾기");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("워크스페이스 선택");
            String cur = pathField.getText().trim();
            if (!cur.isBlank()) {
                File f = new File(cur);
                if (f.isDirectory()) dc.setInitialDirectory(f);
            }
            File sel = dc.showDialog(stage);
            if (sel != null) pathField.setText(sel.getAbsolutePath());
        });

        Button inspectBtn = new Button("색인 상태 조회");
        inspectBtn.setDefaultButton(true);
        HBox topRow = new HBox(6, pathField, browseBtn, inspectBtn);
        topRow.setAlignment(Pos.CENTER_LEFT);

        // 요약 레이블
        Label summaryLabel = new Label("워크스페이스를 선택한 뒤 '색인 상태 조회'를 누르세요.");
        summaryLabel.getStyleClass().add("text-muted");

        // 결과 테이블
        TableView<RowItem> table = buildTable();
        table.setPrefHeight(340);
        VBox.setVgrow(table, Priority.ALWAYS);

        // 진행 표시
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(32, 32);
        spinner.setVisible(false);

        VBox root = new VBox(10, topRow, summaryLabel, spinner, table);
        root.setPadding(new Insets(14));
        VBox.setVgrow(table, Priority.ALWAYS);

        inspectBtn.setOnAction(e -> {
            String dir = pathField.getText().trim();
            if (dir.isBlank()) {
                summaryLabel.setText("워크스페이스 경로를 입력하세요.");
                return;
            }
            Path workspace = Path.of(dir);
            if (!Files.isDirectory(workspace)) {
                summaryLabel.setText("유효하지 않은 디렉토리: " + dir);
                return;
            }
            inspectBtn.setDisable(true);
            spinner.setVisible(true);
            summaryLabel.setText("조회 중...");
            table.getItems().clear();

            Thread worker = new Thread(() -> {
                try {
                    WikiIndexService svc = AppContext.getInstance().getWikiIndexService();
                    WorkspaceIndexMetadata meta = svc.inspectWorkspace(workspace);
                    List<RowItem> rows = meta.pages().stream()
                            .map(RowItem::from)
                            .toList();
                    String summary = buildSummary(meta);
                    Platform.runLater(() -> {
                        table.getItems().setAll(rows);
                        summaryLabel.setText(summary);
                        spinner.setVisible(false);
                        inspectBtn.setDisable(false);
                    });
                } catch (SQLException ex) {
                    Platform.runLater(() -> {
                        summaryLabel.setText("조회 실패: " + ex.getMessage());
                        spinner.setVisible(false);
                        inspectBtn.setDisable(false);
                    });
                }
            }, "wiki-inspect");
            worker.setDaemon(true);
            worker.start();
        });

        stage.setScene(SceneFactory.create(root, 700, 480));
        stage.showAndWait();
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────

    private static TableView<RowItem> buildTable() {
        TableView<RowItem> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<RowItem, String> pathCol = new TableColumn<>("페이지 경로");
        pathCol.setCellValueFactory(new PropertyValueFactory<>("pagePath"));
        pathCol.setPrefWidth(280);

        TableColumn<RowItem, String> catCol = new TableColumn<>("분류");
        catCol.setCellValueFactory(new PropertyValueFactory<>("category"));
        catCol.setPrefWidth(80);

        TableColumn<RowItem, String> stateCol = new TableColumn<>("상태");
        stateCol.setCellValueFactory(new PropertyValueFactory<>("stateLabel"));
        stateCol.setPrefWidth(100);

        TableColumn<RowItem, String> chunkCol = new TableColumn<>("청크(예상/색인)");
        chunkCol.setCellValueFactory(new PropertyValueFactory<>("chunkInfo"));
        chunkCol.setPrefWidth(110);

        TableColumn<RowItem, String> sizeCol = new TableColumn<>("파일 크기");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("fileSize"));
        sizeCol.setPrefWidth(80);

        table.getColumns().addAll(pathCol, catCol, stateCol, chunkCol, sizeCol);
        return table;
    }

    private static String buildSummary(WorkspaceIndexMetadata meta) {
        long current    = meta.count(PageIndexState.CURRENT);
        long stale      = meta.count(PageIndexState.STALE);
        long notIndexed = meta.count(PageIndexState.NOT_INDEXED);
        long orphaned   = meta.count(PageIndexState.ORPHANED);
        long empty      = meta.count(PageIndexState.EMPTY);
        return String.format("총 %d 페이지 — 최신: %d, 갱신 필요: %d, 미색인: %d, 빈 파일: %d, 고아: %d",
                meta.pages().size(), current, stale, notIndexed, empty, orphaned);
    }

    /**
     * 테이블 행 데이터 모델.
     * JavaFX PropertyValueFactory가 getter 이름으로 바인딩하므로 public getter 필수.
     */
    public static final class RowItem {

        /** 워크스페이스 기준 페이지 상대 경로 */
        private final String pagePath;
        /** 페이지 분류 (sources/entities 등) */
        private final String category;
        /** 상태 한글 표시 */
        private final String stateLabel;
        /** 예상 청크 수 / 색인된 청크 수 */
        private final String chunkInfo;
        /** 파일 크기 (사람이 읽기 쉬운 형식) */
        private final String fileSize;

        private RowItem(String pagePath, String category, String stateLabel,
                        String chunkInfo, String fileSize) {
            this.pagePath   = pagePath;
            this.category   = category;
            this.stateLabel = stateLabel;
            this.chunkInfo  = chunkInfo;
            this.fileSize   = fileSize;
        }

        /** WikiIndexService.PageIndexMetadata에서 RowItem을 생성한다. */
        public static RowItem from(WikiIndexService.PageIndexMetadata m) {
            String label = switch (m.state()) {
                case CURRENT     -> "최신";
                case STALE       -> "갱신 필요";
                case NOT_INDEXED -> "미색인";
                case EMPTY       -> "빈 파일";
                case ORPHANED    -> "고아(삭제됨)";
            };
            String info = m.expectedChunks() + " / " + m.indexedChunks();
            String size = m.fileBytes() == 0 ? "-" : formatBytes(m.fileBytes());
            return new RowItem(m.pagePath(), m.category(), label, info, size);
        }

        public String getPagePath()   { return pagePath; }
        public String getCategory()   { return category; }
        public String getStateLabel() { return stateLabel; }
        public String getChunkInfo()  { return chunkInfo; }
        public String getFileSize()   { return fileSize; }

        private static String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
