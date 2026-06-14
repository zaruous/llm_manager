/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.service.AppSettingsRepository;
import org.kyj.llmmanager.service.WikiSiteExporter;
import org.kyj.llmmanager.service.WikiSiteExporter.ExportOptions;
import org.kyj.llmmanager.service.WikiSiteExporter.ExportResult;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 위키 워크스페이스를 정적 HTML 사이트로 내보내는 다이얼로그.
 *
 * 출력 경로·옵션 선택 후 비동기로 WikiSiteExporter를 실행하며,
 * 진행바와 현재 파일명으로 진행 상황을 표시한다.
 */
public class WikiExportDialog {

    private final Stage owner;
    private final Path  workspace;

    public WikiExportDialog(Stage owner, Path workspace) {
        this.owner     = owner;
        this.workspace = workspace;
    }

    /** 내보내기 다이얼로그를 표시한다. */
    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("위키 내보내기");
        stage.setResizable(false);

        AppSettingsRepository repo = AppContext.getInstance().getAppSettingsRepository();
        AppSettings settings = repo.get();
        String lastDir = settings.getPluginSetting("wiki-agent", "wiki.exportDir", "");

        // 출력 경로
        TextField outputField = new TextField(lastDir);
        outputField.setPromptText("HTML 파일을 저장할 디렉토리");
        outputField.setPrefWidth(300);
        Button browseBtn = new Button("찾기");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("출력 디렉토리 선택");
            String cur = outputField.getText().trim();
            if (!cur.isBlank()) {
                File f = new File(cur);
                if (f.isDirectory()) dc.setInitialDirectory(f);
            }
            File sel = dc.showDialog(stage);
            if (sel != null) outputField.setText(sel.getAbsolutePath());
        });

        // 옵션
        CheckBox navCb      = new CheckBox("사이드바 네비게이션 포함");      navCb.setSelected(true);
        CheckBox searchCb   = new CheckBox("검색 기능 포함 (search-index.json)");
        CheckBox graphCb    = new CheckBox("그래프 페이지 포함 (graph/)");   graphCb.setSelected(true);
        CheckBox reportsCb  = new CheckBox("리포트 페이지 포함 (health/lint)"); reportsCb.setSelected(true);
        CheckBox cleanCb    = new CheckBox("기존 출력 초기화 후 생성");

        // 테마
        ToggleGroup themeGroup = new ToggleGroup();
        RadioButton darkRb  = new RadioButton("다크");  darkRb.setToggleGroup(themeGroup);  darkRb.setSelected(true);
        RadioButton lightRb = new RadioButton("라이트"); lightRb.setToggleGroup(themeGroup);

        // 예상 페이지 수
        int pageCount = countPages();
        Label pageCountLabel = new Label("예상 페이지: " + pageCount + "개");
        pageCountLabel.setStyle("-fx-text-fill: #888;");

        // 진행바
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(380);
        progressBar.setVisible(false);
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 12px;");

        // 버튼
        Button exportBtn = new Button("내보내기");
        Button cancelBtn = new Button("취소");
        cancelBtn.setOnAction(e -> stage.close());
        exportBtn.setDefaultButton(true);

        // 경고 레이블 (워크스페이스 하위 경로 선택 시)
        Label warnLabel = new Label();
        warnLabel.setStyle("-fx-text-fill: #ffaa44; -fx-font-size: 12px;");
        warnLabel.setWrapText(true);
        outputField.textProperty().addListener((obs, old, val) -> {
            if (val != null && !val.isBlank() && workspace != null) {
                try {
                    Path out = Path.of(val.trim()).toAbsolutePath().normalize();
                    if (out.startsWith(workspace.toAbsolutePath().normalize())) {
                        warnLabel.setText("⚠ 출력 경로가 워크스페이스 안입니다. 위키 수집 대상에 포함될 수 있습니다.");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            warnLabel.setText("");
        });

        exportBtn.setOnAction(e -> {
            String outText = outputField.getText().trim();
            if (outText.isBlank()) {
                new Alert(Alert.AlertType.WARNING, "출력 디렉토리를 선택해 주세요.").showAndWait();
                return;
            }
            Path outputDir = Path.of(outText).toAbsolutePath().normalize();

            // cleanOutput=true 이고 기존 파일 있을 때 확인
            if (cleanCb.isSelected() && Files.exists(outputDir) && hasFiles(outputDir)) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "기존 파일이 모두 삭제됩니다. 계속하시겠습니까?",
                        ButtonType.OK, ButtonType.CANCEL);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            }

            ExportOptions opts = new ExportOptions(
                    darkRb.isSelected() ? "dark" : "light",
                    navCb.isSelected(),
                    searchCb.isSelected(),
                    graphCb.isSelected(),
                    reportsCb.isSelected(),
                    cleanCb.isSelected()
            );

            startExport(stage, outputDir, opts, progressBar, statusLabel, exportBtn, cancelBtn, repo);
        });

        // 레이아웃
        HBox pathRow = new HBox(8, outputField, browseBtn);
        pathRow.setAlignment(Pos.CENTER_LEFT);

        VBox optBox = new VBox(6, navCb, searchCb, graphCb, reportsCb, cleanCb);
        HBox themeRow = new HBox(12, new Label("테마:"), darkRb, lightRb);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        HBox btnRow = new HBox(10, exportBtn, cancelBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        Separator sep1 = new Separator(), sep2 = new Separator(), sep3 = new Separator();

        VBox root = new VBox(10,
                new Label("출력 위치:"), pathRow, warnLabel,
                sep1, optBox, sep2, themeRow, sep3,
                pageCountLabel, progressBar, statusLabel, btnRow);
        root.setPadding(new Insets(20));
        root.setPrefWidth(440);

        stage.setScene(SceneFactory.create(root, 460, 600));
        stage.show();
    }

    private int countPages() {
        if (workspace == null) return 0;
        try {
            return org.kyj.llmmanager.service.WikiMarkdownUtils.collectPages(workspace.resolve("wiki")).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findFirst().isPresent();
        } catch (Exception e) { return false; }
    }

    private void startExport(Stage stage, Path outputDir, ExportOptions opts,
                             ProgressBar progressBar, Label statusLabel,
                             Button exportBtn, Button cancelBtn,
                             AppSettingsRepository repo) {
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        exportBtn.setDisable(true);
        cancelBtn.setText("중단");

        WikiSiteExporter exporter = new WikiSiteExporter();

        Task<ExportResult> task = new Task<>() {
            @Override
            protected ExportResult call() throws Exception {
                return exporter.export(workspace, outputDir, opts, (file, done, tot) -> {
                    updateProgress(done, tot);
                    updateMessage(file);
                });
            }
        };

        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setProgress(1);
            // wiki.exportDir 저장
            AppSettings current = repo.get();
            current.setPluginSetting("wiki-agent", "wiki.exportDir", outputDir.toString());
            repo.save(current);
            ExportResult result = task.getValue();
            showComplete(stage, result, outputDir);
        });

        task.setOnFailed(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            exportBtn.setDisable(false);
            cancelBtn.setText("닫기");
            Throwable err = task.getException();
            new Alert(Alert.AlertType.ERROR, "내보내기 실패: " + (err != null ? err.getMessage() : "알 수 없는 오류")).showAndWait();
        });

        task.setOnCancelled(e -> {
            progressBar.progressProperty().unbind();
            statusLabel.textProperty().unbind();
            progressBar.setProgress(0);
            statusLabel.setText("중단됨");
            exportBtn.setDisable(false);
            cancelBtn.setText("닫기");
        });

        cancelBtn.setOnAction(e -> {
            if (task.isRunning()) task.cancel();
            else stage.close();
        });

        Thread thread = new Thread(task, "wiki-export");
        thread.setDaemon(true);
        thread.start();
    }

    private void showComplete(Stage parentStage, ExportResult result, Path outputDir) {
        Stage done = new Stage();
        done.initOwner(parentStage);
        done.initModality(Modality.APPLICATION_MODAL);
        done.setTitle("내보내기 완료");

        StringBuilder msg = new StringBuilder();
        msg.append("완료: ").append(result.pageCount()).append("페이지 성공");
        if (result.failCount() > 0)
            msg.append(", ").append(result.failCount()).append("페이지 실패");
        msg.append("\n").append(outputDir);

        Label msgLabel = new Label(msg.toString());
        msgLabel.setWrapText(true);

        VBox errBox = new VBox(4);
        if (!result.failedPages().isEmpty()) {
            for (String fp : result.failedPages()) {
                Label l = new Label("⚠ " + fp);
                l.setStyle("-fx-text-fill: #ffaa44; -fx-font-size: 12px;");
                l.setWrapText(true);
                errBox.getChildren().add(l);
            }
        }

        Button openBrowserBtn = new Button("브라우저에서 열기");
        openBrowserBtn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(outputDir.resolve("index.html").toUri());
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "브라우저를 열 수 없습니다: " + ex.getMessage()).showAndWait();
            }
        });

        Button openFolderBtn = new Button("폴더 열기");
        openFolderBtn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().open(outputDir.toFile());
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "폴더를 열 수 없습니다: " + ex.getMessage()).showAndWait();
            }
        });

        Button closeBtn = new Button("닫기");
        closeBtn.setOnAction(e -> { done.close(); parentStage.close(); });

        HBox btnRow = new HBox(10, openBrowserBtn, openFolderBtn, closeBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, msgLabel, errBox, new Separator(), btnRow);
        root.setPadding(new Insets(20));
        root.setPrefWidth(420);

        done.setScene(SceneFactory.create(root, 440, 300));
        done.show();
    }
}
