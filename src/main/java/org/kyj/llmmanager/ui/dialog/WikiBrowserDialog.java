/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.ui.dialog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.util.SceneFactory;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 위키 워크스페이스를 탐색하는 브라우저 창 (통합계획 Phase 3).
 *
 * 좌측 트리(sources/entities/concepts/syntheses + 리포트)에서 페이지를 고르면
 * 우측 WebView에 마크다운을 렌더링한다. [[WikiLink]]는 앱 내 페이지 이동 링크로
 * 변환되고(없는 페이지는 회색), http 링크는 OS 기본 브라우저로 연다.
 */
public class WikiBrowserDialog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** [[Name]] 또는 [[Name|Label]] 형식의 위키링크 */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]");

    /**
     * wiki: 링크 클릭을 document.title 변경으로 변환하는 인터셉터 스크립트.
     * WebKit은 알 수 없는 스킴으로의 내비게이션을 무시해 locationProperty가
     * 발화하지 않으므로, title 변경을 신호로 써서 Java 쪽에서 페이지를 전환한다.
     * seq는 같은 링크 연속 클릭에도 title이 매번 달라지게 하는 카운터.
     */
    private static final String NAV_SCRIPT = """
            <script>
            (function () {
              var seq = 0;
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest ? e.target.closest('a') : null;
                if (!a) return;
                var href = a.getAttribute('href') || '';
                // wiki: 스킴([[위키링크]] 변환 결과)과 상대 .md 링크 모두 앱 내 이동으로 처리
                var isWiki = href.indexOf('wiki:') === 0;
                var isMdLink = /\\.md(#|$)/.test(href) && !/^[a-z]+:/i.test(href);
                if (isWiki || isMdLink) {
                  e.preventDefault();
                  document.title = 'wiki-nav:' + (++seq) + ':' + encodeURIComponent(href);
                }
              }, true);
            })();
            </script>
            """;
    private static final List<String> CATEGORIES =
            List.of("sources", "entities", "concepts", "syntheses");

    private final Stage owner;

    /** 페이지 키(소문자 정규화된 이름) → 파일 경로. 워크스페이스 스캔 시 재구축. */
    private final Map<String, Path> pageIndex = new LinkedHashMap<>();
    /** 뒤로 가기 스택 — 현재 표시 중인 페이지 경로를 push한다. */
    private final Deque<Path> backStack = new ArrayDeque<>();

    private Path workspace;
    private Path currentPage;
    private WebView webView;
    private TreeView<PageNode> treeView;
    /** locationProperty 리스너의 재진입 방지 플래그 */
    private boolean navigating;

    /** 트리 노드 한 항목 — 카테고리 노드는 path=null. */
    private record PageNode(String label, Path path) {
        @Override public String toString() { return label; }
    }

    public WikiBrowserDialog(Stage owner) {
        this.owner = owner;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("위키 브라우저");

        var settings = AppContext.getInstance().getAppSettingsRepository().get();
        TextField workspaceField = new TextField(
                settings.getPluginSetting("wiki-agent", "wiki.defaultCwd", ""));
        workspaceField.setPromptText("위키 워크스페이스 디렉토리");
        Button browseBtn = new Button("찾기");
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("위키 워크스페이스 선택");
            String current = workspaceField.getText().trim();
            if (!current.isBlank()) {
                File dir = new File(current);
                if (dir.isDirectory()) chooser.setInitialDirectory(dir);
            }
            File selected = chooser.showDialog(stage);
            if (selected != null) workspaceField.setText(selected.getAbsolutePath());
        });

        Button backBtn = new Button("◀ 뒤로");
        backBtn.setOnAction(e -> goBack());
        Button refreshBtn = new Button("새로고침");
        refreshBtn.setOnAction(e -> openWorkspace(workspaceField.getText()));
        Button graphBtn = new Button("그래프");
        graphBtn.setOnAction(e -> openGraphInBrowser());

        HBox topBar = new HBox(8, new Label("워크스페이스:"), workspaceField, browseBtn,
                backBtn, refreshBtn, graphBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10, 12, 10, 12));
        HBox.setHgrow(workspaceField, Priority.ALWAYS);

        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(PageNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null && item.getValue() != null && item.getValue().path() != null) {
                navigateTo(item.getValue().path(), true);
            }
        });

        webView = new WebView();
        webView.getEngine().setJavaScriptEnabled(true);
        // 링크 클릭 신호 — NAV_SCRIPT가 title을 "wiki-nav:<seq>:<href>"로 바꾼다
        webView.getEngine().titleProperty().addListener((obs, old, title) -> {
            if (title == null || !title.startsWith("wiki-nav:")) return;
            int hrefStart = title.indexOf(':', "wiki-nav:".length());
            if (hrefStart < 0) return;
            String href = URLDecoder.decode(
                    title.substring(hrefStart + 1), StandardCharsets.UTF_8);
            if (href.startsWith("wiki:")) {
                navigateToName(URLDecoder.decode(
                        href.substring("wiki:".length()), StandardCharsets.UTF_8));
            } else {
                navigateToRelative(href);
            }
        });
        // http 링크 클릭 가로채기 — 외부 브라우저로 위임
        webView.getEngine().locationProperty().addListener((obs, old, location) -> {
            if (navigating || location == null || location.isBlank()) return;
            if (location.startsWith("http://") || location.startsWith("https://")) {
                Platform.runLater(() -> {
                    openExternal(location);
                    // WebView가 외부 페이지로 떠나지 않도록 현재 페이지를 다시 그린다
                    if (currentPage != null) renderPage(currentPage);
                });
            }
        });

        SplitPane split = new SplitPane(treeView, webView);
        split.setDividerPositions(0.26);

        VBox root = new VBox(topBar, split);
        VBox.setVgrow(split, Priority.ALWAYS);

        stage.setScene(SceneFactory.create(root, 1100, 740));
        stage.setResizable(true);
        stage.show();

        workspaceField.textProperty().addListener((obs, old, value) -> openWorkspace(value));
        openWorkspace(workspaceField.getText());
    }

    // ─────────────────────────────────────────────
    // 워크스페이스 스캔·트리 구성
    // ─────────────────────────────────────────────

    private void openWorkspace(String dir) {
        backStack.clear();
        currentPage = null;
        pageIndex.clear();

        TreeItem<PageNode> root = new TreeItem<>(new PageNode("wiki", null));
        root.setExpanded(true);

        if (dir == null || dir.isBlank()) {
            treeView.setRoot(root);
            renderMessage("워크스페이스를 선택해 주세요.");
            return;
        }
        workspace = Path.of(dir.trim()).toAbsolutePath().normalize();
        Path wikiDir = workspace.resolve("wiki");
        if (!Files.isRegularFile(wikiDir.resolve("index.md"))) {
            treeView.setRoot(root);
            renderMessage("위키 워크스페이스가 아닙니다 (wiki/index.md 없음): " + workspace);
            return;
        }
        // 유효한 워크스페이스는 기억해 다음에 모든 위키 다이얼로그의 기본값으로 복원
        org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                AppContext.getInstance().getAppSettingsRepository(), workspace.toString());

        // 루트 문서
        TreeItem<PageNode> docs = new TreeItem<>(new PageNode("문서", null));
        docs.setExpanded(true);
        for (String name : List.of("overview.md", "index.md", "log.md")) {
            Path file = wikiDir.resolve(name);
            if (Files.isRegularFile(file)) {
                docs.getChildren().add(new TreeItem<>(new PageNode(stem(name), file)));
                indexPage(file);
            }
        }
        root.getChildren().add(docs);

        // 카테고리별 페이지
        for (String category : CATEGORIES) {
            Path catDir = wikiDir.resolve(category);
            List<Path> pages = listMarkdown(catDir);
            TreeItem<PageNode> catItem = new TreeItem<>(
                    new PageNode(category + " (" + pages.size() + ")", null));
            for (Path page : pages) {
                catItem.getChildren().add(new TreeItem<>(new PageNode(stem(page), page)));
                indexPage(page);
            }
            root.getChildren().add(catItem);
        }

        // 리포트 (health/lint 실행 결과)
        TreeItem<PageNode> reports = new TreeItem<>(new PageNode("리포트", null));
        for (String name : List.of("health-report.md", "lint-report.md")) {
            Path file = wikiDir.resolve(name);
            if (Files.isRegularFile(file)) {
                reports.getChildren().add(new TreeItem<>(new PageNode(stem(name), file)));
            }
        }
        if (!reports.getChildren().isEmpty()) root.getChildren().add(reports);

        treeView.setRoot(root);

        Path overview = wikiDir.resolve("overview.md");
        navigateTo(Files.isRegularFile(overview) ? overview : wikiDir.resolve("index.md"), true);
    }

    private List<Path> listMarkdown(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return new ArrayList<>(stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 파일명 스템과 frontmatter title 양쪽 키로 페이지를 색인한다 — 위키링크는 제목 기준이 많다. */
    private void indexPage(Path file) {
        pageIndex.put(normalizeKey(stem(file)), file);
        String title = readFrontmatterValue(file, "title");
        if (title != null && !title.isBlank()) {
            pageIndex.put(normalizeKey(title), file);
        }
    }

    private String stem(Path file) {
        return stem(file.getFileName().toString());
    }

    private String stem(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    /** 대소문자·공백·하이픈 차이를 무시하는 링크 매칭 키 */
    private String normalizeKey(String name) {
        return name.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }

    private String readFrontmatterValue(Path file, String key) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).strip().equals("---")) return null;
            for (int i = 1; i < Math.min(lines.size(), 20); i++) {
                String line = lines.get(i).strip();
                if (line.equals("---")) break;
                if (line.startsWith(key + ":")) {
                    return line.substring(key.length() + 1).trim().replaceAll("^\"|\"$", "");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ─────────────────────────────────────────────
    // 페이지 이동·렌더링
    // ─────────────────────────────────────────────

    private void navigateToName(String name) {
        Path target = pageIndex.get(normalizeKey(name));
        if (target != null) navigateTo(target, true);
    }

    /**
     * 상대 .md 링크(예: overview.md, ../entities/BAS.md)를 현재 페이지 기준으로
     * 해석해 이동한다. 파일이 없으면 파일명 스템으로 색인을 재시도한다.
     */
    private void navigateToRelative(String href) {
        // #fragment 제거 — 페이지 단위로만 이동
        int hash = href.indexOf('#');
        String relative = hash >= 0 ? href.substring(0, hash) : href;
        if (relative.isBlank()) return;

        Path base = currentPage != null && currentPage.getParent() != null
                ? currentPage.getParent()
                : (workspace != null ? workspace.resolve("wiki") : null);
        if (base != null) {
            Path resolved = base.resolve(relative).normalize();
            if (Files.isRegularFile(resolved)) {
                navigateTo(resolved, true);
                return;
            }
        }
        // 경로 해석 실패 시 파일명 스템으로 색인 폴백 (디렉토리 구조가 다른 링크 대비)
        String fileName = relative.replace('\\', '/');
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) fileName = fileName.substring(slash + 1);
        navigateToName(stem(fileName));
    }

    private void navigateTo(Path page, boolean pushHistory) {
        if (page == null || !Files.isRegularFile(page)) return;
        if (pushHistory && currentPage != null && !currentPage.equals(page)) {
            backStack.push(currentPage);
        }
        currentPage = page;
        renderPage(page);
    }

    private void goBack() {
        if (backStack.isEmpty()) return;
        navigateTo(backStack.pop(), false);
    }

    private void renderPage(Path page) {
        try {
            String md = Files.readString(page, StandardCharsets.UTF_8);
            md = stripFrontmatterToMeta(md);
            md = convertWikiLinks(md);
            loadMarkdown(md);
        } catch (Exception e) {
            renderMessage("페이지를 읽을 수 없습니다: " + e.getMessage());
        }
    }

    /** frontmatter를 제거하고 type·last_updated만 인용구 메타 라인으로 남긴다. */
    private String stripFrontmatterToMeta(String md) {
        if (!md.startsWith("---")) return md;
        int end = md.indexOf("\n---", 3);
        if (end < 0) return md;
        String frontmatter = md.substring(3, end);
        String body = md.substring(md.indexOf('\n', end + 1) + 1);

        List<String> meta = new ArrayList<>();
        for (String line : frontmatter.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("type:") || stripped.startsWith("last_updated:")) {
                meta.add(stripped);
            }
        }
        return meta.isEmpty() ? body : "> " + String.join(" · ", meta) + "\n\n" + body;
    }

    /**
     * [[WikiLink]]를 wiki: 스킴 마크다운 링크로 변환한다.
     * 색인에 없는 페이지는 링크 대신 회색 텍스트로 표시한다.
     */
    private String convertWikiLinks(String md) {
        Matcher matcher = WIKILINK.matcher(md);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String label = matcher.group(2) != null ? matcher.group(2).trim() : name;
            String replacement;
            if (pageIndex.containsKey(normalizeKey(name))) {
                replacement = "[" + label + "](wiki:"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8) + ")";
            } else {
                replacement = "<span style=\"color:#777\" title=\"아직 없는 페이지\">" + label + "</span>";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void loadMarkdown(String md) {
        try {
            String template = readResource("docs/help-template.html");
            String html = template.replace("__MARKDOWN_JSON__", MAPPER.writeValueAsString(md));
            // 위키링크 클릭 인터셉터 주입 — 템플릿 파일은 도움말과 공유하므로 여기서만 추가
            html = html.replace("</body>", NAV_SCRIPT + "</body>");
            navigating = true;
            try {
                webView.getEngine().loadContent(html, "text/html");
            } finally {
                // loadContent는 location을 비동기로 바꾸므로 다음 펄스에서 해제
                Platform.runLater(() -> navigating = false);
            }
        } catch (Exception e) {
            renderMessage("렌더링 실패: " + e.getMessage());
        }
    }

    private void renderMessage(String message) {
        navigating = true;
        webView.getEngine().loadContent(
                "<body style='color:#cccccc;background:#1a1a2e;padding:24px;"
                + "font-family:Segoe UI,Malgun Gothic,sans-serif'>" + message + "</body>",
                "text/html");
        Platform.runLater(() -> navigating = false);
    }

    private void openGraphInBrowser() {
        if (workspace == null) return;
        Path graphHtml = workspace.resolve("graph").resolve("graph.html");
        if (!Files.isRegularFile(graphHtml)) {
            renderMessage("그래프 파일이 없습니다. 먼저 플러그인 메뉴에서 '그래프 빌드 (Graph)'를 실행해 주세요.");
            return;
        }
        openExternal(graphHtml.toUri().toString());
    }

    private void openExternal(String uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(uri));
            }
        } catch (Exception ignored) {
        }
    }

    private String readResource(String path) throws Exception {
        String fullPath = "/org/kyj/llmmanager/" + path;
        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is == null) throw new Exception("리소스 없음: " + fullPath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
