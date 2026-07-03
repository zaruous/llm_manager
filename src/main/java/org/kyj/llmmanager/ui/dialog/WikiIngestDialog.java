/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.service.AppSettingsRepository;
import org.kyj.llmmanager.service.PluginCommandExecutor.PluginCommandRequest;
import org.kyj.llmmanager.service.PluginManager.PluginCommandContribution;
import org.kyj.llmmanager.service.WikiIngestPlanner;
import org.kyj.llmmanager.service.WikiIngestPlanner.IngestPlan;
import org.kyj.llmmanager.service.WikiIngestPlanner.IngestTask;
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
import javafx.scene.control.ProgressBar;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 문서 수집(wiki.ingest) 다이얼로그. 파일 멀티 선택과 폴더 추가를 지원한다.
 *
 * 선택 항목을 워크스페이스의 raw/<하위분류>/로 복사한 뒤 (raw는 불변 원본
 * 저장소 — 기존 파일은 절대 덮어쓰지 않음) WikiIngestPlanner가 세운 작업
 * 계획(작은 파일 배치 / 큰 텍스트 섹션 노트+병합 / 큰 바이너리 단독 작업)을
 * 순차 실행하고 프로그레스바로 진행 상태를 보여준다.
 */
public class WikiIngestDialog {

    /** 기본 제공 raw/ 하위 분류 목록 — 커스텀 분류 영속화 시 제외 기준. */
    private static final List<String> DEFAULT_CATEGORIES =
            List.of("papers", "articles", "notes", "meetings");

    private final Stage owner;
    private final PluginCommandContribution contribution;
    /** plugin.json의 ingest 설정에서 로드한 수집 규칙. 설정 없으면 기본값. */
    private final IngestRules ingestRules;

    public WikiIngestDialog(Stage owner, PluginCommandContribution contribution) {
        this.owner = owner;
        this.contribution = contribution;
        this.ingestRules = loadIngestRules();
    }

    /**
     * plugin.json의 ingest 섹션을 읽어 IngestRules를 만든다.
     * 플러그인을 찾지 못하거나 ingest 설정이 비어 있으면 하드코딩 기본값을 사용한다.
     */
    private IngestRules loadIngestRules() {
        var pm = AppContext.getInstance().getPluginManager();
        if (pm != null) {
            var plugin = pm.findPlugin(contribution.pluginId());
            if (plugin != null && plugin.isValid() && plugin.getManifest() != null) {
                var cfg = plugin.getManifest().getIngest();
                if (!cfg.getInclude().isEmpty() || !cfg.getExclude().isEmpty()) {
                    return new IngestRules(cfg.getInclude(), cfg.getExclude());
                }
            }
        }
        return IngestRules.defaults();
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("문서 수집 (Ingest)");

        var ctx = AppContext.getInstance();

        String defaultCwd = resolveWorkspace();
        TextField workspaceField = new TextField(defaultCwd);
        workspaceField.setEditable(false);
        workspaceField.setPromptText(contribution.linkedServiceId() != null
                ? "서비스 설정에서 workspace 값을 변경하세요"
                : "설정 > wiki-agent > 기본 워크스페이스에서 지정");
        HBox workspaceRow = new HBox(8, new Label("워크스페이스:"), workspaceField);
        workspaceRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(workspaceField, Priority.ALWAYS);

        ObservableList<Path> selections = FXCollections.observableArrayList();
        ListView<Path> selectionList = new ListView<>(selections);
        selectionList.setPrefHeight(170);

        Label countLabel = new Label("총 0개 파일 — 크기·개수에 따라 나눠 수집합니다");
        selections.addListener((javafx.collections.ListChangeListener<Path>) change ->
                countLabel.setText("총 " + countFiles(selections)
                        + "개 파일 — 크기·개수에 따라 나눠 수집합니다"));

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

        // 기본 분류 + 이전 세션에서 저장한 커스텀 분류를 병합해 콤보 초기화
        String savedCats = ctx.getAppSettingsRepository().get()
                .getPluginSetting(contribution.pluginId(), "wiki.customCategories", "");
        List<String> allCategories = new ArrayList<>(DEFAULT_CATEGORIES);
        if (!savedCats.isBlank()) {
            for (String c : savedCats.split(",")) {
                String t = c.trim();
                if (!t.isBlank() && !allCategories.contains(t)) allCategories.add(t);
            }
        }
        ComboBox<String> categoryCombo = new ComboBox<>(FXCollections.observableArrayList(allCategories));
        categoryCombo.setEditable(true);
        categoryCombo.setValue("articles");
        HBox categoryRow = new HBox(8, new Label("raw/ 하위 분류:"), categoryCombo);
        categoryRow.setAlignment(Pos.CENTER_LEFT);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        Label progressLabel = new Label("대기 중");
        HBox progressRow = new HBox(8, progressBar, progressLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBar, Priority.ALWAYS);

        TextArea terminalArea = new TextArea();
        terminalArea.setEditable(false);
        terminalArea.setWrapText(false);
        terminalArea.setStyle("-fx-font-family:'Consolas','Courier New',monospace; -fx-font-size:12px;");

        Button runBtn = new Button("수집 시작");
        runBtn.setPrefWidth(100);
        Button stopBtn = new Button("중지");
        stopBtn.setPrefWidth(70);
        stopBtn.setDisable(true);

        /** 중지 요청 플래그 — 작업 사이에서 루프를 멈추기 위해 프로세스 종료와 별도로 둔다. */
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        stopBtn.setOnAction(e -> {
            stopRequested.set(true);
            if (AppContext.getInstance().getPluginCommandExecutor().cancel("wiki.ingest")) {
                appendLine(terminalArea, "중지 요청됨 — 현재 실행을 종료하고 남은 작업을 취소합니다.");
            } else {
                appendLine(terminalArea, "중지 요청됨 — 다음 작업부터 실행하지 않습니다.");
            }
        });
        Button closeBtn = new Button("닫기");
        closeBtn.setOnAction(e -> stage.close());

        // 실행 중 선택 목록 편집을 막는다 — 백그라운드 스레드가 순회하는 동안
        // UI에서 목록이 바뀌면 ConcurrentModificationException이 날 수 있음
        List<Button> editButtons = List.of(addFilesBtn, addDirBtn, removeBtn);
        Runnable enterRunning = () -> {
            runBtn.setDisable(true);
            stopBtn.setDisable(false);
            editButtons.forEach(b -> b.setDisable(true));
        };
        Runnable exitRunning = () -> {
            runBtn.setDisable(false);
            stopBtn.setDisable(true);
            editButtons.forEach(b -> b.setDisable(false));
        };

        runBtn.setOnAction(e -> {
            String workspace = workspaceField.getText().trim();
            String category = categoryCombo.getValue() != null
                    ? categoryCombo.getValue().trim() : "";
            if (workspace.isBlank() || selections.isEmpty() || category.isBlank()) {
                appendLine(terminalArea, "워크스페이스·분류·수집 대상이 모두 필요합니다.");
                return;
            }
            // 목록에 없는 새 분류면 콤보에 추가하고 설정에 영속화
            if (!categoryCombo.getItems().contains(category)) {
                categoryCombo.getItems().add(category);
                persistCustomCategory(ctx.getAppSettingsRepository(), category);
            }
            // 골격 없는 디렉토리는 스킬 설치 화면을 거치지 않고 즉석 초기화 제안
            if (!ensureWorkspaceInitialized(stage, workspace, terminalArea)) return;
            // 서비스 연동 시 전역 설정 대신 서비스 argValues를 갱신
            if (contribution.linkedServiceId() != null) {
                org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                        ctx.getServiceRegistry(), contribution.linkedServiceId(), workspace);
            } else {
                org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                        ctx.getAppSettingsRepository(), workspace);
            }
            stopRequested.set(false);
            enterRunning.run();

            // 실행 중 UI 편집과 분리하기 위해 선택 목록은 스냅샷으로 전달
            List<Path> snapshot = List.copyOf(selections);
            Path workspaceRoot = Path.of(workspace).toAbsolutePath().normalize();
            Consumer<String> log = line -> Platform.runLater(() -> appendLine(terminalArea, line));

            new Thread(() -> {
                try {
                    // 선택 항목을 raw/<분류>/로 복사 — raw는 불변 원본 저장소이므로
                    // 외부 파일을 워크스페이스 안으로 들여온 뒤 ingest에 전달한다
                    List<Path> copied = copyIntoRaw(snapshot, workspaceRoot, category, log);
                    if (copied.isEmpty()) {
                        log.accept("수집할 파일이 없습니다. include 패턴: "
                                + String.join(", ", ingestRules.includeGlobs));
                        return;
                    }

                    IngestPlan plan = WikiIngestPlanner.plan(copied, workspaceRoot,
                            createPageExtractor(log), log);
                    log.accept("실행 계획: 파일 " + plan.fileCount() + "개 (총 "
                            + WikiIngestPlanner.formatBytes(plan.totalBytes())
                            + ") → 에이전트 실행 " + plan.tasks().size() + "회");

                    runPlan(plan, workspaceRoot, stopRequested, log, progressBar, progressLabel);
                } catch (Exception ex) {
                    log.accept("[오류] " + ex.getMessage());
                } finally {
                    Platform.runLater(exitRunning);
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
                progressRow,
                terminalArea,
                buttons);
        root.setPadding(new Insets(14));
        VBox.setVgrow(terminalArea, Priority.ALWAYS);

        stage.setScene(SceneFactory.create(root, 720, 660));
        stage.setResizable(true);
        stage.show();
    }

    /**
     * 작업 계획을 순차 실행하며 프로그레스바를 갱신한다.
     *
     * 에이전트 단위로 연속 파이프라인을 구성한다 — 개별 작업이 실패해도 자동으로
     * 다음 작업을 계속 실행하고(섹션 노트 실패 시 병합 패스도 유지), 전체 종료 후
     * 실패 목록을 요약해 재시도를 안내한다. 사용자 중지는 작업 경계에서 즉시 반영된다.
     *
     * @param stage 소유 Stage — confirmPlan 등 FX 다이얼로그에 사용
     * @param plan 실행할 작업 계획
     * @param workspaceRoot 정규화된 워크스페이스 루트
     * @param stopRequested 중지 플래그 — 작업 경계에서 확인
     * @param onOutput 라인 단위 출력 콜백 (FX 스레드로 래핑됨)
     * @param progressBar 진행률 표시 바
     * @param progressLabel 현재 작업 라벨
     */
    private void runPlan(IngestPlan plan, Path workspaceRoot,
                         AtomicBoolean stopRequested, Consumer<String> onOutput,
                         ProgressBar progressBar, Label progressLabel) {
        List<IngestTask> tasks = plan.tasks();
        List<String> failedLabels = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            if (stopRequested.get()) {
                onOutput.accept("중지됨 — 남은 작업 " + (tasks.size() - i) + "개를 취소했습니다.");
                updateProgress(progressBar, progressLabel, i, tasks.size(), "중지됨");
                return;
            }
            IngestTask task = tasks.get(i);
            updateProgress(progressBar, progressLabel, i, tasks.size(), task.label());
            onOutput.accept("=== [" + (i + 1) + "/" + tasks.size() + "] " + task.label() + " ===");

            var result = AppContext.getInstance().getPluginCommandExecutor().executeStreaming(
                    contribution.pluginId(),
                    contribution.command(),
                    new PluginCommandRequest(workspaceRoot.toString(), task.payload(),
                            null, null, new LinkedHashMap<>(task.options())),
                    onOutput);

            if (!result.success()) {
                if (stopRequested.get()) {
                    onOutput.accept("중지됨 — 남은 작업 " + (tasks.size() - i - 1) + "개를 취소했습니다.");
                    updateProgress(progressBar, progressLabel, i, tasks.size(), "중지됨");
                    return;
                }
                // 에이전트 오류 — 이 작업을 실패로 기록하고 자동으로 다음 작업을 진행
                failedLabels.add(task.label());
                onOutput.accept("[오류] 작업 실패 — 다음 작업으로 계속합니다: " + task.label());
                updateProgress(progressBar, progressLabel, i + 1, tasks.size(), "계속 중");
                continue;
            }
            // 병합·대용량 작업 성공 시 섹션·노트 스테이징 정리
            if (task.stagingDir() != null) deleteRecursively(task.stagingDir(), onOutput);
        }
        if (failedLabels.isEmpty()) {
            updateProgress(progressBar, progressLabel, tasks.size(), tasks.size(), "완료");
            onOutput.accept("수집 완료 (파일 " + plan.fileCount() + "개, 작업 "
                    + tasks.size() + "개).");
        } else {
            updateProgress(progressBar, progressLabel, tasks.size(), tasks.size(),
                    "완료 (실패 " + failedLabels.size() + "개)");
            onOutput.accept("수집 완료 — 실패한 작업 " + failedLabels.size()
                    + "개가 있습니다. 동일 파일로 재수집하면 성공한 항목은 스킵됩니다:");
            failedLabels.forEach(l -> onOutput.accept("  - " + l));
        }
    }

    /**
     * 대용량 PDF 페이지 전처리 훅을 만든다. 플러그인의 extract_pages.py를
     * 앱 설정의 Python으로 실행한다 — 플러그인 디렉토리를 못 찾으면 null을
     * 반환해 플래너가 단독 작업 모드로 처리하게 한다.
     */
    private WikiIngestPlanner.PageExtractor createPageExtractor(Consumer<String> log) {
        var pluginManager = AppContext.getInstance().getPluginManager();
        var plugin = pluginManager != null
                ? pluginManager.findPlugin(contribution.pluginId()) : null;
        if (plugin == null) return null;

        var extractor = new org.kyj.llmmanager.service.WikiPageExtractor(
                plugin.getDirectory().resolve("tools"),
                AppContext.getInstance().getAppSettingsRepository().get().getPythonCommand());
        return (source, outputDir) -> extractor.extract(source, outputDir, log);
    }

    /** 프로그레스바·라벨을 FX 스레드에서 갱신한다. */
    private void updateProgress(ProgressBar bar, Label label, int done, int total, String text) {
        Platform.runLater(() -> {
            bar.setProgress(total == 0 ? 0 : (double) done / total);
            label.setText("(" + done + "/" + total + ") " + text);
        });
    }

    /** 스테이징 디렉토리를 하위부터 삭제한다. 실패해도 수집 결과에는 영향 없음. */
    private void deleteRecursively(Path dir, Consumer<String> onOutput) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
            onOutput.accept("스테이징 정리: " + dir.getFileName());
        } catch (IOException ignored) {
        }
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

    /** 선택 목록의 총 파일 수 (폴더는 include 패턴 통과·exclude 패턴 미해당 파일만 재귀 집계). */
    private long countFiles(List<Path> selections) {
        long count = 0;
        for (Path path : selections) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    count += walk.filter(Files::isRegularFile)
                            .filter(f -> ingestRules.isIncluded(f.getFileName())
                                    && !ingestRules.isExcluded(path.relativize(f)))
                            .count();
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
     * 폴더는 지원 확장자·비숨김 파일만 재귀 복사한다.
     */
    private List<Path> copyIntoRaw(List<Path> selections, Path workspace, String category,
                                   Consumer<String> onOutput) throws IOException {
        Path rawDir = workspace.resolve("raw").resolve(category);
        Files.createDirectories(rawDir);
        List<Path> result = new ArrayList<>();

        Path ws = workspace.toAbsolutePath().normalize();
        for (Path source : selections) {
            Path normalized = source.toAbsolutePath().normalize();
            if (normalized.startsWith(ws)) {
                // exclude 패턴(wiki/**, graph/**, tools/**)에 걸리는 워크스페이스 내부 경로 제외 —
                // 위키 페이지를 다시 수집하면 자기참조로 위키가 오염된다
                Path rel = ws.relativize(normalized);
                if (ingestRules.isExcluded(rel)) {
                    onOutput.accept("[경고] exclude 패턴에 해당해 제외: " + rel);
                    continue;
                }
                result.add(source);
                continue;
            }
            if (Files.isDirectory(source)) {
                long skipped = 0;
                try (Stream<Path> walk = Files.walk(source)) {
                    for (Path file : walk.filter(Files::isRegularFile).toList()) {
                        Path relative = source.relativize(file);
                        if (!ingestRules.isIncluded(file.getFileName())
                                || ingestRules.isExcluded(relative)) {
                            skipped++;
                            continue;
                        }
                        Path target = rawDir.resolve(source.getFileName()).resolve(relative);
                        result.add(copyImmutable(file, target, onOutput));
                    }
                }
                onOutput.accept("복사: " + source + " → " + rawDir.resolve(source.getFileName())
                        + (skipped > 0 ? " (제외 " + skipped + "개)" : ""));
            } else {
                Path target = rawDir.resolve(source.getFileName());
                result.add(copyImmutable(source, target, onOutput));
                onOutput.accept("복사: " + source + " → " + target);
            }
        }
        return result;
    }

    /**
     * raw 불변성을 지키며 복사한다 — 기존 파일을 절대 덮어쓰지 않는다.
     * 같은 이름이 이미 있으면 내용을 비교해 동일하면 기존 파일을 재사용하고,
     * 다르면 name-1.ext 식으로 유니크한 이름을 찾아 저장한다.
     *
     * @param source 복사할 원본 파일
     * @param target 희망 대상 경로 (raw/ 내부)
     * @return 실제로 수집 대상이 된 경로 (재사용·개명된 경로일 수 있음)
     */
    private Path copyImmutable(Path source, Path target, Consumer<String> onOutput) throws IOException {
        Files.createDirectories(target.getParent());
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";

        Path candidate = target;
        for (int i = 1; Files.exists(candidate); i++) {
            // 동일 내용이면 재복사·중복 생성 없이 기존 raw 파일을 그대로 사용
            if (Files.mismatch(source, candidate) < 0) {
                onOutput.accept("재사용 (동일 내용): " + candidate.getFileName());
                return candidate;
            }
            candidate = target.resolveSibling(base + "-" + i + ext);
        }
        Files.copy(source, candidate);
        if (!candidate.equals(target)) {
            onOutput.accept("이름 충돌 — " + name + " → " + candidate.getFileName() + "로 저장");
        }
        return candidate;
    }

    /**
     * 커스텀 분류를 wiki.customCategories 플러그인 설정에 추가 저장한다.
     * 기본 분류(DEFAULT_CATEGORIES)는 저장 목록에서 제외해 중복을 피한다.
     *
     * @param repo 앱 설정 저장소
     * @param newCategory 추가할 새 분류 이름
     */
    private void persistCustomCategory(AppSettingsRepository repo, String newCategory) {
        var s = repo.get();
        String saved = s.getPluginSetting(contribution.pluginId(), "wiki.customCategories", "");
        List<String> custom = new ArrayList<>();
        if (!saved.isBlank()) {
            for (String c : saved.split(",")) {
                String t = c.trim();
                if (!t.isBlank()) custom.add(t);
            }
        }
        if (!DEFAULT_CATEGORIES.contains(newCategory) && !custom.contains(newCategory)) {
            custom.add(newCategory);
            s.setPluginSetting(contribution.pluginId(), "wiki.customCategories",
                    String.join(",", custom));
            repo.save(s);
        }
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

    /**
     * 워크스페이스 초기값을 결정한다.
     * linkedServiceId가 있으면 해당 서비스의 argValues["workspace"]를 사용하고,
     * 없으면 전역 plugin 설정의 wiki.defaultCwd로 폴백한다.
     *
     * @return 결정된 워크스페이스 경로 (없으면 빈 문자열)
     */
    private String resolveWorkspace() {
        if (contribution == null) return "";
        var ctx = AppContext.getInstance();
        if (contribution.linkedServiceId() != null) {
            return ctx.getServiceRegistry()
                    .findById(contribution.linkedServiceId())
                    .map(def -> def.getArgValues().getOrDefault("workspace", ""))
                    .orElse("");
        }
        return ctx.getAppSettingsRepository()
                .get().getPluginSetting(contribution.pluginId(), "wiki.defaultCwd", "");
    }

    private void appendLine(TextArea area, String line) {
        if (!area.getText().isEmpty()) area.appendText("\n");
        String ts = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        area.appendText("[" + ts + "] " + (line != null ? line : ""));
        area.setScrollTop(Double.MAX_VALUE);
    }

    /**
     * plugin.json의 ingest.include / ingest.exclude 패턴을 컴파일해 두는 규칙 집합.
     *
     * include 패턴은 파일명에만 매칭 (확장자 화이트리스트 역할).
     * exclude 패턴은 상대 경로 전체에 매칭 (경로 블랙리스트 역할).
     * 두 패턴 모두 gitignore 스타일 glob을 사용한다 —
     * {@code *} 는 경로 구분자 제외 임의 문자열, {@code **} 는 구분자 포함 임의 문자열.
     */
    private static class IngestRules {

        private static final List<String> DEFAULT_INCLUDES = List.of(
                "*.md", "*.pdf", "*.docx", "*.pptx", "*.xlsx",
                "*.html", "*.htm", "*.txt", "*.csv", "*.json", "*.xml",
                "*.rst", "*.rtf", "*.epub", "*.ipynb",
                "*.yaml", "*.yml", "*.tsv", "*.wav", "*.mp3");

        /** 제외 기본값: 에이전트 관리 레이어 + 숨김 파일·디렉토리. */
        private static final List<String> DEFAULT_EXCLUDES = List.of(
                "wiki/**", "graph/**", "tools/**", "**/.*", "**/.*/**");

        private final List<Pattern> includePatterns;
        private final List<Pattern> excludePatterns;
        /** 로그·안내 문구용으로 노출하는 include glob 목록. */
        final List<String> includeGlobs;

        IngestRules(List<String> includes, List<String> excludes) {
            this.includeGlobs = List.copyOf(includes);
            this.includePatterns = includes.stream().map(IngestRules::compile).collect(Collectors.toList());
            this.excludePatterns = excludes.stream().map(IngestRules::compile).collect(Collectors.toList());
        }

        static IngestRules defaults() {
            return new IngestRules(DEFAULT_INCLUDES, DEFAULT_EXCLUDES);
        }

        /**
         * 폴더 재귀 수집 시 파일명이 include 패턴에 해당하는지 확인한다.
         * include 목록이 비어 있으면 모든 파일을 허용한다.
         *
         * @param filename 파일명만 포함한 Path (getFileName() 결과)
         */
        boolean isIncluded(Path filename) {
            String name = filename.toString();
            return includePatterns.isEmpty()
                    || includePatterns.stream().anyMatch(p -> p.matcher(name).matches());
        }

        /**
         * 워크스페이스 또는 소스 폴더 기준 상대 경로가 exclude 패턴에 해당하는지 확인한다.
         * 경로 구분자는 플랫폼 무관하게 /로 정규화한다.
         *
         * @param relative 소스 루트 기준 상대 경로
         */
        boolean isExcluded(Path relative) {
            String rel = relative.toString().replace('\\', '/');
            return excludePatterns.stream().anyMatch(p -> p.matcher(rel).matches());
        }

        /**
         * gitignore 스타일 glob 패턴을 정규식으로 컴파일한다.
         * 선행 "**&#x2F;" — 임의 깊이 앞 경로, 중간 "&#x2F;**&#x2F;" — 임의 중간 경로,
         * 후행 "**" — 나머지 전체, "*" — 구분자 제외 임의 문자열, "?" — 구분자 제외 단일 문자.
         */
        private static Pattern compile(String glob) {
            String g = glob.replace('\\', '/');
            StringBuilder sb = new StringBuilder("^");
            // 선행 **/ — 임의 깊이의 앞 경로와 매칭 (없어도 됨)
            if (g.startsWith("**/")) {
                sb.append("(.*\\/)?");
                g = g.substring(3);
            }
            int i = 0;
            while (i < g.length()) {
                char c = g.charAt(i);
                if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    i += 2;
                    if (i < g.length() && g.charAt(i) == '/') {
                        // 중간 또는 후행 /**/ — 임의 중간 경로와 매칭
                        sb.append("(.*\\/)?");
                        i++;
                    } else {
                        // 후행 ** — 나머지 전체와 매칭
                        sb.append(".*");
                    }
                } else if (c == '*') {
                    sb.append("[^\\/]*");
                    i++;
                } else if (c == '?') {
                    sb.append("[^\\/]");
                    i++;
                } else if (c == '.') {
                    sb.append("\\.");
                    i++;
                } else if ("[]()^$|+\\{}".indexOf(c) >= 0) {
                    sb.append('\\').append(c);
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            sb.append("$");
            return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
        }
    }
}
