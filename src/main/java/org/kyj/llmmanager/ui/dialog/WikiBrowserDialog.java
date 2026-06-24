/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.ui.dialog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.service.PluginManager.PluginCommandContribution;
import org.kyj.llmmanager.service.WikiIndexService;
import org.kyj.llmmanager.service.WikiMarkdownUtils;
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
    /** 연결된 플러그인 커맨드 기여 정보. null이면 전역 plugin 설정에서 워크스페이스를 읽는다. */
    private final PluginCommandContribution contribution;

    /** 페이지 키(소문자 정규화된 이름) → 파일 경로. 워크스페이스 스캔 시 재구축. */
    private final Map<String, Path> pageIndex = new LinkedHashMap<>();
    /** 검색 대상 전체 페이지 목록 (트리 구성 순서 유지). 워크스페이스 스캔 시 재구축. */
    private final List<Path> allPages = new ArrayList<>();
    /** 뒤로 가기 스택 — 현재 표시 중인 페이지 경로를 push한다. */
    private final Deque<Path> backStack = new ArrayDeque<>();

    private Stage stage;
    private Path workspace;
    private Path currentPage;
    private WebView webView;
    private TreeView<PageNode> treeView;
    /** 검색 전의 일반 트리 — 검색어를 지우면 이걸로 복원한다. */
    private TreeItem<PageNode> normalTreeRoot;
    /** locationProperty 리스너의 재진입 방지 플래그 */
    private boolean navigating;

    /** 트리 노드 한 항목 — 카테고리 노드는 path=null. */
    private record PageNode(String label, Path path) {
        @Override public String toString() { return label; }
    }

    public WikiBrowserDialog(Stage owner, PluginCommandContribution contribution) {
        this.owner = owner;
        this.contribution = contribution;
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("위키 브라우저");

        TextField workspaceField = new TextField(resolveWorkspace());
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
        Button exportBtn = new Button("HTML 내보내기");
        exportBtn.setOnAction(e -> {
            if (workspace == null) {
                renderMessage("워크스페이스를 먼저 선택해 주세요.");
                return;
            }
            new WikiExportDialog(stage, workspace).show();
        });
        HBox topBar = new HBox(8, new Label("워크스페이스:"), workspaceField, browseBtn,
                backBtn, refreshBtn, graphBtn, exportBtn);
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

        // 키워드 검색 — Enter로 제목·본문 검색, 비우면 원래 트리 복원
        TextField searchField = new TextField();
        searchField.setPromptText("키워드 검색 (제목·본문, Enter)");
        searchField.setOnAction(e -> runSearch(searchField.getText()));
        searchField.textProperty().addListener((obs, old, value) -> {
            if (value == null || value.isBlank()) rebuildTree();
        });
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // 시맨틱 검색 버튼 — bgem3-embedding + 벡터 색인이 있을 때 유효
        Button semanticBtn = new Button("~");
        semanticBtn.setTooltip(new javafx.scene.control.Tooltip(
                "시맨틱 검색 (벡터 유사도 — bgem3-embedding 서비스와 벡터 색인 필요)"));
        semanticBtn.setOnAction(e -> runSemanticSearch(searchField.getText()));

        HBox searchBar = new HBox(4, searchField, semanticBtn);

        VBox left = new VBox(8, searchBar, treeView);
        left.setPadding(new Insets(0, 0, 0, 0));
        VBox.setVgrow(treeView, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, webView);
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
        allPages.clear();

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
            if (!offerInitialize(workspace)) {
                renderMessage("위키 워크스페이스가 아닙니다 (wiki/index.md 없음): " + workspace);
                return;
            }
        }
        // 유효한 워크스페이스를 기억 — 서비스 연동 시 서비스 argValues, 아니면 전역 plugin 설정 갱신
        var _ctx = AppContext.getInstance();
        if (contribution != null && contribution.linkedServiceId() != null) {
            org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                    _ctx.getServiceRegistry(), contribution.linkedServiceId(), workspace.toString());
        } else {
            org.kyj.llmmanager.service.WikiWorkspaceInitializer.rememberWorkspace(
                    _ctx.getAppSettingsRepository(), workspace.toString());
        }

        // 루트 문서
        TreeItem<PageNode> docs = new TreeItem<>(new PageNode("문서", null));
        docs.setExpanded(true);
        for (String name : List.of("overview.md", "index.md", "log.md")) {
            Path file = wikiDir.resolve(name);
            if (Files.isRegularFile(file)) {
                docs.getChildren().add(new TreeItem<>(new PageNode(WikiMarkdownUtils.stem(name), file)));
                indexPage(file);
                allPages.add(file);
            }
        }
        root.getChildren().add(docs);

        // 카테고리별 페이지
        for (String category : CATEGORIES) {
            Path catDir = wikiDir.resolve(category);
            List<Path> pages = WikiMarkdownUtils.listMarkdown(catDir);
            TreeItem<PageNode> catItem = new TreeItem<>(
                    new PageNode(category + " (" + pages.size() + ")", null));
            for (Path page : pages) {
                catItem.getChildren().add(new TreeItem<>(new PageNode(WikiMarkdownUtils.stem(page), page)));
                indexPage(page);
                allPages.add(page);
            }
            root.getChildren().add(catItem);
        }

        // 리포트 (health/lint 실행 결과)
        TreeItem<PageNode> reports = new TreeItem<>(new PageNode("리포트", null));
        for (String name : List.of("health-report.md", "lint-report.md")) {
            Path file = wikiDir.resolve(name);
            if (Files.isRegularFile(file)) {
                reports.getChildren().add(new TreeItem<>(new PageNode(WikiMarkdownUtils.stem(name), file)));
                allPages.add(file);
            }
        }
        if (!reports.getChildren().isEmpty()) root.getChildren().add(reports);

        normalTreeRoot = root;
        treeView.setRoot(root);

        Path overview = wikiDir.resolve("overview.md");
        navigateTo(Files.isRegularFile(overview) ? overview : wikiDir.resolve("index.md"), true);
    }

    /** 파일명 스템과 frontmatter title 양쪽 키로 페이지를 색인한다 — 위키링크는 제목 기준이 많다. */
    private void indexPage(Path file) {
        pageIndex.put(WikiMarkdownUtils.normalizeKey(WikiMarkdownUtils.stem(file)), file);
        String title = WikiMarkdownUtils.readFrontmatterValue(file, "title");
        if (title != null && !title.isBlank())
            pageIndex.put(WikiMarkdownUtils.normalizeKey(title), file);
    }

    // ─────────────────────────────────────────────
    // 키워드 검색
    // ─────────────────────────────────────────────

    /** 검색어를 지웠을 때 일반 트리로 복원한다. */
    private void rebuildTree() {
        if (normalTreeRoot != null) treeView.setRoot(normalTreeRoot);
    }

    /**
     * 제목·본문 키워드 검색을 백그라운드에서 실행하고 결과로 트리를 전환한다.
     * 제목 일치(★)가 본문 일치보다 위에 정렬된다.
     */
    private void runSearch(String query) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            rebuildTree();
            return;
        }
        List<Path> targets = new ArrayList<>(allPages);
        String lower = keyword.toLowerCase(Locale.ROOT);

        new Thread(() -> {
            List<PageNode> titleHits = new ArrayList<>();
            List<PageNode> contentHits = new ArrayList<>();
            for (Path page : targets) {
                try {
                    String name = WikiMarkdownUtils.stem(page);
                    String label = name + "  ·  " + categoryOf(page);
                    if (name.toLowerCase(Locale.ROOT).contains(lower)) {
                        titleHits.add(new PageNode("★ " + label, page));
                        continue;
                    }
                    String content = Files.readString(page, StandardCharsets.UTF_8);
                    if (content.toLowerCase(Locale.ROOT).contains(lower)) {
                        contentHits.add(new PageNode(label, page));
                    }
                } catch (Exception ignored) {
                    // 읽기 실패 페이지는 검색 결과에서 제외
                }
            }
            List<PageNode> results = new ArrayList<>(titleHits);
            results.addAll(contentHits);
            Platform.runLater(() -> showSearchResults(keyword, results));
        }, "wiki-search").start();
    }

    /**
     * 벡터 유사도 기반 시맨틱 검색을 백그라운드에서 실행한다.
     * bgem3-embedding 서비스 미기동 또는 색인 미구축 시 키워드 검색으로 폴백한다.
     *
     * @param query 자연어 검색어
     */
    private void runSemanticSearch(String query) {
        if (query == null || query.isBlank()) {
            rebuildTree();
            return;
        }
        if (workspace == null) {
            runSearch(query);
            return;
        }
        org.kyj.llmmanager.service.WikiIndexService svc =
                AppContext.getInstance().getWikiIndexService();
        if (svc == null || svc.countChunks(workspace) == 0) {
            // 색인 미구축 시 키워드 검색 폴백
            runSearch(query);
            return;
        }
        new Thread(() -> {
            var results = svc.search(workspace, query, 20);
            if (results.isEmpty()) {
                Platform.runLater(() -> runSearch(query));
                return;
            }
            List<PageNode> pageNodes = results.stream()
                    .map(r -> {
                        Path file = workspace.resolve(r.pagePath().replace('/', java.io.File.separatorChar));
                        String label = r.pagePath();
                        return new PageNode(label, Files.isRegularFile(file) ? file : null);
                    })
                    .distinct()
                    .toList();
            Platform.runLater(() -> showSearchResults("~" + query, pageNodes));
        }, "wiki-semantic-search").start();
    }

    private void showSearchResults(String keyword, List<PageNode> results) {
        TreeItem<PageNode> root = new TreeItem<>(new PageNode("root", null));
        TreeItem<PageNode> group = new TreeItem<>(
                new PageNode("검색: \"" + keyword + "\" (" + results.size() + "건)", null));
        group.setExpanded(true);
        for (PageNode result : results) {
            group.getChildren().add(new TreeItem<>(result));
        }
        root.getChildren().add(group);
        treeView.setRoot(root);
    }

    /** 페이지가 속한 카테고리(부모 디렉토리) 표시명 — wiki 루트 직속이면 "문서". */
    private String categoryOf(Path page) {
        Path parent = page.getParent();
        if (parent == null) return "";
        String name = parent.getFileName() != null ? parent.getFileName().toString() : "";
        return "wiki".equalsIgnoreCase(name) ? "문서" : name;
    }

    // ─────────────────────────────────────────────
    // 페이지 이동·렌더링
    // ─────────────────────────────────────────────

    private void navigateToName(String name) {
        Path target = pageIndex.get(WikiMarkdownUtils.normalizeKey(name));
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
        navigateToName(WikiMarkdownUtils.stem(fileName));
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
            md = WikiMarkdownUtils.stripFrontmatterToMeta(md);
            md = convertWikiLinks(md);
            loadMarkdown(md);
        } catch (Exception e) {
            renderMessage("페이지를 읽을 수 없습니다: " + e.getMessage());
        }
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
            if (pageIndex.containsKey(WikiMarkdownUtils.normalizeKey(name))) {
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

    /**
     * wiki/index.md가 없을 때 골격 초기화를 제안하고 수락 시 생성한다.
     *
     * @param workspace 초기화할 워크스페이스 경로
     * @return 초기화 성공 여부 (취소 또는 실패 시 false)
     */
    private boolean offerInitialize(Path workspace) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("위키 골격 초기화");
        alert.setHeaderText("이 디렉토리에 위키 골격(wiki/index.md)이 없습니다.");
        alert.setContentText("raw/, wiki/, graph/ 골격을 지금 생성할까요?\n" + workspace);
        var result = alert.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) return false;
        try {
            org.kyj.llmmanager.service.WikiWorkspaceInitializer.initialize(workspace);
            return true;
        } catch (Exception ex) {
            renderMessage("[오류] 골격 초기화 실패: " + ex.getMessage());
            return false;
        }
    }

    /**
     * 워크스페이스 초기값을 결정한다.
     * linkedServiceId가 있으면 해당 서비스의 argValues["workspace"]를 사용하고,
     * 없으면 전역 plugin 설정의 wiki.defaultCwd로 폴백한다.
     */
    private String resolveWorkspace() {
        var ctx = AppContext.getInstance();
        if (contribution != null && contribution.linkedServiceId() != null) {
            return ctx.getServiceRegistry()
                    .findById(contribution.linkedServiceId())
                    .map(def -> def.getArgValues().getOrDefault("workspace", ""))
                    .orElse("");
        }
        return ctx.getAppSettingsRepository()
                .get().getPluginSetting("wiki-agent", "wiki.defaultCwd", "");
    }

    private String readResource(String path) throws Exception {
        String fullPath = "/org/kyj/llmmanager/" + path;
        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is == null) throw new Exception("리소스 없음: " + fullPath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
