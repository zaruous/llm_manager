/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 위키 워크스페이스를 정적 HTML 사이트로 내보내는 서비스.
 *
 * collectPages → buildPageIndex → (병렬) 페이지 렌더링 → 자산 복사 순으로
 * 처리하며, 임시 디렉토리에 먼저 쓴 뒤 완료 시 목표 경로로 이동해 원자성을 보장한다.
 */
public class WikiSiteExporter {

    /** 내보내기 옵션. */
    public record ExportOptions(
            String theme,           // "dark" | "light"
            boolean includeNav,
            boolean includeSearch,
            boolean includeGraph,
            boolean includeReports,
            boolean cleanOutput
    ) {
        public static ExportOptions defaults() {
            return new ExportOptions("dark", true, false, true, true, false);
        }
    }

    /** 내보내기 결과. */
    public record ExportResult(
            int pageCount,
            int failCount,
            List<String> failedPages,
            Path outputDir,
            Duration elapsed
    ) {}

    /** 진행 상황 콜백 — (현재 파일명, 완료 수, 전체 수). */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String fileName, int done, int total);
    }

    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]");
    private static final Pattern IMAGE_MD  = Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)");

    private final Parser      mdParser;
    private final HtmlRenderer mdRenderer;

    public WikiSiteExporter() {
        var ext = List.of(TablesExtension.create(), AutolinkExtension.create());
        mdParser   = Parser.builder().extensions(ext).build();
        mdRenderer = HtmlRenderer.builder().extensions(ext).build();
    }

    /**
     * 워크스페이스의 위키 콘텐츠를 정적 HTML 사이트로 내보낸다.
     *
     * @param workspace  wiki/index.md가 있는 루트 디렉토리
     * @param outputDir  HTML 파일을 쓸 출력 디렉토리
     * @param options    테마·포함 여부 등 옵션
     * @param progress   진행 콜백 (null 허용)
     * @return           내보내기 결과 (페이지 수, 실패 목록, 소요 시간)
     * @throws IOException 워크스페이스 읽기 또는 출력 쓰기 실패 시
     */
    public ExportResult export(Path workspace, Path outputDir,
                               ExportOptions options, ProgressCallback progress)
            throws IOException {
        Instant start = Instant.now();

        Path wikiDir = workspace.resolve("wiki");
        if (!Files.isRegularFile(wikiDir.resolve("index.md")))
            throw new IOException("위키 워크스페이스가 아닙니다 (wiki/index.md 없음): " + workspace);

        List<WikiMarkdownUtils.PageEntry> allCollected = WikiMarkdownUtils.collectPages(wikiDir);
        final List<WikiMarkdownUtils.PageEntry> pages = options.includeReports()
                ? allCollected
                : allCollected.stream().filter(p -> !"reports".equals(p.category())).toList();
        Map<String, Path> pageIndex = WikiMarkdownUtils.buildPageIndex(pages);

        // 임시 디렉토리에 먼저 쓴다
        Path tmpDir = outputDir.getParent() != null
                ? outputDir.getParent().resolve(".export-tmp-" + start.toEpochMilli())
                : Path.of(".export-tmp-" + start.toEpochMilli());
        Files.createDirectories(tmpDir);

        int total = pages.size();
        AtomicInteger done = new AtomicInteger(0);
        List<String> failedPages = new CopyOnWriteArrayList<>();

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (WikiMarkdownUtils.PageEntry page : pages) {
                futures.add(pool.submit(() -> {
                    try {
                        exportPage(page, pages, pageIndex, tmpDir, options);
                    } catch (Exception e) {
                        failedPages.add(page.category() + "/" + page.stem() + ".html — " + e.getMessage());
                    }
                    int n = done.incrementAndGet();
                    if (progress != null)
                        progress.onProgress(page.category() + "/" + page.stem() + ".html", n, total);
                }));
            }

            for (Future<?> f : futures) {
                try { f.get(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("내보내기가 중단되었습니다", e);
                } catch (ExecutionException e) {
                    // 개별 페이지 실패는 failedPages에 기록 후 계속
                }
            }

            // 자산 복사
            copyAssets(tmpDir);
            if (options.includeGraph()) copyGraph(workspace, tmpDir);
            if (options.includeSearch()) buildSearchIndex(pages, tmpDir);

            // 완료: 임시 → 목표 디렉토리
            if (options.cleanOutput() && Files.exists(outputDir))
                deleteTree(outputDir);
            else if (Files.exists(outputDir))
                removeOrphanHtmlFiles(outputDir, pages);

            Files.createDirectories(outputDir.getParent() != null ? outputDir.getParent() : Path.of("."));
            if (Files.exists(outputDir)) {
                // 목표가 이미 있으면 파일 단위로 이동
                mergeTree(tmpDir, outputDir);
                deleteTree(tmpDir);
            } else {
                Files.move(tmpDir, outputDir, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (IOException e) {
            deleteQuietly(tmpDir);
            throw e;
        } finally {
            pool.shutdownNow();
        }

        return new ExportResult(
                total - failedPages.size(),
                failedPages.size(),
                List.copyOf(failedPages),
                outputDir,
                Duration.between(start, Instant.now()));
    }

    // ─────────────────────────────────────────────
    // 페이지 렌더링
    // ─────────────────────────────────────────────

    private void exportPage(WikiMarkdownUtils.PageEntry page,
                            List<WikiMarkdownUtils.PageEntry> allPages,
                            Map<String, Path> pageIndex,
                            Path tmpDir,
                            ExportOptions options) throws IOException {
        String md = Files.readString(page.file(), StandardCharsets.UTF_8);
        md = WikiMarkdownUtils.stripFrontmatterToMeta(md);

        // 이미지 경로 처리: 상대 경로 → output/images/<원본상대경로>/ 복사 후 재매핑
        md = processImages(md, page.file(), tmpDir);

        // [[WikiLink]] → 상대 HTML 경로 변환
        md = convertWikiLinks(md, page, allPages, pageIndex);

        // 마크다운 → HTML
        String contentHtml = mdRenderer.render(mdParser.parse(md));

        // 페이지 제목 결정
        String title = resolveTitle(page.file(), md);

        // 출력 경로 및 asset 상대 경로
        Path outFile = resolveOutputHtmlPath(tmpDir, page);
        String assetBase = assetBase(tmpDir, outFile);

        // 네비게이션: 현재 페이지 기준 올바른 상대경로로 per-page 생성
        String navHtml = options.includeNav() ? buildNavHtml(allPages, page) : "";

        String html = loadTemplate()
                .replace("__PAGE_TITLE__",   escapeHtml(title))
                .replace("__ASSET_BASE__",   assetBase)
                .replace("__NAV_HTML__",     navHtml)
                .replace("__CONTENT_HTML__", contentHtml)
                .replace("__THEME__",        options.theme());

        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, html, StandardCharsets.UTF_8);
    }

    private String resolveTitle(Path file, String markdownBody) {
        String fm = WikiMarkdownUtils.readFrontmatterValue(file, "title");
        if (fm != null && !fm.isBlank()) return fm;
        var m = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(markdownBody);
        if (m.find()) return m.group(1).trim();
        return WikiMarkdownUtils.stem(file);
    }

    /**
     * outputRoot 기준 pageFile의 depth에 따른 assets/ 상대 경로를 반환한다.
     * 예: index.html(depth 0) → "assets/",  entities/Foo.html(depth 1) → "../assets/"
     */
    private String assetBase(Path outputRoot, Path pageFile) {
        Path rel = outputRoot.relativize(pageFile.getParent());
        String relStr = rel.toString();
        if (relStr.isEmpty() || relStr.equals(".")) return "assets/";
        int depth = rel.getNameCount();
        return "../".repeat(depth) + "assets/";
    }

    private Path resolveOutputHtmlPath(Path outputRoot, WikiMarkdownUtils.PageEntry page) {
        if ("docs".equals(page.category()))
            return outputRoot.resolve(page.stem() + ".html");
        return outputRoot.resolve(page.category()).resolve(page.stem() + ".html");
    }

    // ─────────────────────────────────────────────
    // 위키링크 변환
    // ─────────────────────────────────────────────

    private String convertWikiLinks(String md, WikiMarkdownUtils.PageEntry currentPage,
                                    List<WikiMarkdownUtils.PageEntry> allPages,
                                    Map<String, Path> pageIndex) {
        Matcher matcher = WIKILINK.matcher(md);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name  = matcher.group(1).trim();
            String label = matcher.group(2) != null ? matcher.group(2).trim() : name;
            String replacement;
            Path targetFile = pageIndex.get(WikiMarkdownUtils.normalizeKey(name));
            if (targetFile != null) {
                // 대상 페이지를 PageEntry 목록에서 찾아 HTML 경로 계산
                String href = allPages.stream()
                        .filter(p -> p.file().equals(targetFile))
                        .findFirst()
                        .map(p -> relativeHtmlHref(currentPage, p))
                        .orElse("#");
                replacement = "[" + label + "](" + href + ")";
            } else {
                replacement = "<span style=\"color:#777\" title=\"아직 없는 페이지\">" + label + "</span>";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 현재 페이지에서 대상 페이지까지의 상대 HTML href를 반환한다.
     */
    private String relativeHtmlHref(WikiMarkdownUtils.PageEntry from, WikiMarkdownUtils.PageEntry to) {
        // from이 docs 카테고리면 root, 아니면 from.category() 하위
        String fromDir = "docs".equals(from.category()) ? "" : from.category() + "/";
        String toPath  = "docs".equals(to.category())
                ? to.stem() + ".html"
                : to.category() + "/" + to.stem() + ".html";

        if (fromDir.isEmpty()) return toPath;
        // from이 카테고리 하위이면 ../toPath
        return "../" + toPath;
    }

    // ─────────────────────────────────────────────
    // 이미지 처리
    // ─────────────────────────────────────────────

    /**
     * 마크다운 내 이미지를 output/images/&lt;원본상대경로&gt; 로 복사하고 경로를 재매핑한다.
     */
    private String processImages(String md, Path sourceFile, Path tmpDir) {
        Path sourceDir = sourceFile.getParent();
        Matcher m = IMAGE_MD.matcher(md);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String alt  = m.group(1);
            String dest = m.group(2);
            // http/data URI는 건너뜀
            if (dest.startsWith("http") || dest.startsWith("data:")) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            try {
                Path imgSrc = sourceDir.resolve(dest).normalize();
                if (Files.isRegularFile(imgSrc)) {
                    // 워크스페이스 루트 기준 상대경로를 images/ 하위로 복사
                    String relName = imgSrc.getFileName().toString();
                    Path imgDst = tmpDir.resolve("images").resolve(relName);
                    // 충돌 방지: 부모 디렉토리명 접두어 추가
                    if (imgSrc.getParent() != null) {
                        String prefix = imgSrc.getParent().getFileName() != null
                                ? imgSrc.getParent().getFileName().toString() + "__"
                                : "";
                        imgDst = tmpDir.resolve("images").resolve(prefix + relName);
                    }
                    Files.createDirectories(imgDst.getParent());
                    Files.copy(imgSrc, imgDst, StandardCopyOption.REPLACE_EXISTING);
                    String newDest = "images/" + imgDst.getFileName().toString();
                    m.appendReplacement(sb, Matcher.quoteReplacement("![" + alt + "](" + newDest + ")"));
                    continue;
                }
            } catch (Exception ignored) {}
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // 네비게이션 HTML 생성
    // ─────────────────────────────────────────────

    /**
     * 현재 페이지 기준의 상대경로로 사이드바 네비게이션 HTML을 생성한다.
     *
     * docs 카테고리(루트 레벨)에서 보면 prefix="", 그 외 카테고리에서 보면 prefix="../"
     * 을 적용해 file:// 에서도 링크가 올바르게 동작하도록 한다.
     * 현재 페이지 링크에는 class="active"를 서버 사이드에서 직접 부여한다.
     *
     * @param pages       전체 페이지 목록
     * @param currentPage 현재 렌더링 중인 페이지
     * @return 사이드바 &lt;details&gt; 블록 HTML 조각
     */
    private String buildNavHtml(List<WikiMarkdownUtils.PageEntry> pages,
                                WikiMarkdownUtils.PageEntry currentPage) {
        // docs 카테고리는 출력 루트에 위치 → 하위 디렉토리로 가려면 카테고리명 그대로
        // 그 외 카테고리는 카테고리 폴더 안에 위치 → 루트로 올라가려면 ../
        boolean currentIsRoot = "docs".equals(currentPage.category());
        String prefix = currentIsRoot ? "" : "../";

        Map<String, List<WikiMarkdownUtils.PageEntry>> grouped = new LinkedHashMap<>();
        for (var page : pages) {
            grouped.computeIfAbsent(page.category(), k -> new ArrayList<>()).add(page);
        }

        StringBuilder sb = new StringBuilder();
        Map<String, String> displayNames = Map.of(
                "docs",      "문서",
                "sources",   "sources",
                "entities",  "entities",
                "concepts",  "concepts",
                "syntheses", "syntheses",
                "reports",   "리포트"
        );

        for (var entry : grouped.entrySet()) {
            String cat   = entry.getKey();
            var catPages = entry.getValue();
            String name  = displayNames.getOrDefault(cat, cat)
                    + " (" + catPages.size() + ")";
            sb.append("<details open>\n<summary>").append(escapeHtml(name)).append("</summary>\n<ul>\n");
            for (var page : catPages) {
                // docs 페이지는 루트, 그 외는 카테고리 하위 — prefix로 깊이 보정
                String href = "docs".equals(page.category())
                        ? prefix + page.stem() + ".html"
                        : prefix + page.category() + "/" + page.stem() + ".html";
                boolean active = page.category().equals(currentPage.category())
                              && page.stem().equals(currentPage.stem());
                sb.append("  <li><a href=\"").append(escapeHtml(href)).append("\"")
                  .append(active ? " class=\"active\"" : "")
                  .append(">").append(escapeHtml(page.stem())).append("</a></li>\n");
            }
            sb.append("</ul>\n</details>\n");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // 자산 복사
    // ─────────────────────────────────────────────

    private void copyAssets(Path tmpDir) throws IOException {
        Path assetsDir = tmpDir.resolve("assets");
        Files.createDirectories(assetsDir);
        for (String name : List.of("style.css", "nav.js")) {
            String res = "/org/kyj/llmmanager/docs/assets/" + name;
            try (InputStream is = getClass().getResourceAsStream(res)) {
                if (is != null)
                    Files.write(assetsDir.resolve(name), is.readAllBytes());
            }
        }
    }

    private void copyGraph(Path workspace, Path tmpDir) throws IOException {
        Path src = workspace.resolve("graph");
        if (!Files.isDirectory(src)) return;
        Path dst = tmpDir.resolve("graph");
        Files.createDirectories(dst);
        try (Stream<Path> walk = Files.walk(src)) {
            walk.forEach(s -> {
                try {
                    Path d = dst.resolve(src.relativize(s));
                    if (Files.isDirectory(s)) Files.createDirectories(d);
                    else Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
            });
        }
    }

    private void buildSearchIndex(List<WikiMarkdownUtils.PageEntry> pages, Path tmpDir) throws IOException {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (var page : pages) {
            try {
                String md = Files.readString(page.file(), StandardCharsets.UTF_8);
                md = WikiMarkdownUtils.stripFrontmatterToMeta(md);
                String title = resolveTitle(page.file(), md);
                // 본문 앞 500자만 인덱스에 포함 (파일 크기 절감)
                String snippet = md.replaceAll("#+\\s+", "").replaceAll("[\\[\\]()]", " ").strip();
                if (snippet.length() > 500) snippet = snippet.substring(0, 500);
                String href = "docs".equals(page.category())
                        ? page.stem() + ".html"
                        : page.category() + "/" + page.stem() + ".html";
                if (!first) json.append(",");
                json.append("\n{\"title\":").append(jsonString(title))
                    .append(",\"href\":").append(jsonString(href))
                    .append(",\"body\":").append(jsonString(snippet))
                    .append("}");
                first = false;
            } catch (Exception ignored) {}
        }
        json.append("\n]");
        Path assetsDir = tmpDir.resolve("assets");
        Files.createDirectories(assetsDir);
        Files.writeString(assetsDir.resolve("search-index.json"), json.toString(), StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────
    // orphan HTML 제거
    // ─────────────────────────────────────────────

    private void removeOrphanHtmlFiles(Path outputDir, List<WikiMarkdownUtils.PageEntry> pages) {
        Set<String> expected = new HashSet<>();
        for (var page : pages) {
            expected.add("docs".equals(page.category())
                    ? page.stem() + ".html"
                    : page.category() + "/" + page.stem() + ".html");
        }
        try (Stream<Path> walk = Files.walk(outputDir)) {
            walk.filter(f -> f.toString().endsWith(".html"))
                .filter(f -> !Files.isDirectory(f))
                .forEach(f -> {
                    String rel = outputDir.relativize(f).toString().replace('\\', '/');
                    if (!expected.contains(rel)) {
                        try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                    }
                });
        } catch (IOException ignored) {}
    }

    // ─────────────────────────────────────────────
    // 파일 시스템 유틸
    // ─────────────────────────────────────────────

    private void mergeTree(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteQuietly(Path path) {
        try { if (path != null) deleteTree(path); } catch (IOException ignored) {}
    }

    // ─────────────────────────────────────────────
    // 템플릿·유틸
    // ─────────────────────────────────────────────

    private String loadTemplate() throws IOException {
        String res = "/org/kyj/llmmanager/docs/site-template.html";
        try (InputStream is = getClass().getResourceAsStream(res)) {
            if (is == null) throw new IOException("site-template.html 리소스 없음");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\"";
    }
}
