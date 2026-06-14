/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.service.WikiSiteExporter.ExportOptions;
import org.kyj.llmmanager.service.WikiSiteExporter.ExportResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiSiteExporter의 HTML 생성 로직을 검증한다.
 *
 * JavaFX 환경(스테이지·씬) 없이 순수 파일I/O로 동작하도록 @TempDir로 격리된
 * 가짜 위키 워크스페이스를 구성하여 테스트한다.
 */
class WikiSiteExporterTest {

    // ─────────────────────────────────────────────
    // 헬퍼 — 가짜 워크스페이스 구성
    // ─────────────────────────────────────────────

    /** wiki/index.md 만 있는 최소 워크스페이스를 만든다. */
    private Path minimalWorkspace(Path root) throws IOException {
        Path wiki = root.resolve("wiki");
        Files.createDirectories(wiki);
        Files.writeString(wiki.resolve("index.md"), "# Index\n\n환영합니다.");
        return root;
    }

    /**
     * 다양한 카테고리 페이지가 있는 표준 워크스페이스를 만든다.
     * 구조: wiki/index.md, wiki/overview.md,
     *        wiki/entities/Alpha.md, wiki/entities/Beta.md,
     *        wiki/concepts/Core.md,
     *        wiki/sources/Ref.md
     */
    private Path fullWorkspace(Path root) throws IOException {
        Path wiki = root.resolve("wiki");
        Files.createDirectories(wiki.resolve("entities"));
        Files.createDirectories(wiki.resolve("concepts"));
        Files.createDirectories(wiki.resolve("sources"));

        Files.writeString(wiki.resolve("index.md"),
                "---\ntitle: 위키 홈\n---\n# Index\n\n전체 목록.");
        Files.writeString(wiki.resolve("overview.md"),
                "# Overview\n\n프로젝트 개요 문서.");
        Files.writeString(wiki.resolve("entities/Alpha.md"),
                "---\ntitle: 알파 엔티티\ntype: entity\nlast_updated: 2026-06\n---\n"
                + "# Alpha\n\nAlpha는 [[Beta]]를 참조합니다.");
        Files.writeString(wiki.resolve("entities/Beta.md"),
                "# Beta\n\nBeta 설명.");
        Files.writeString(wiki.resolve("concepts/Core.md"),
                "# Core Concept\n\n핵심 개념 설명.\n"
                + "## 표 예시\n\n| 키 | 값 |\n|---|---|\n| a | 1 |");
        Files.writeString(wiki.resolve("sources/Ref.md"),
                "# 참고 자료\n\n```java\nSystem.out.println(\"Hello\");\n```");
        return root;
    }

    // ─────────────────────────────────────────────
    // 기본 내보내기
    // ─────────────────────────────────────────────

    @Test
    void exportMinimalWorkspace_createsIndexHtml(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        ExportResult result = new WikiSiteExporter().export(
                workspace, output, ExportOptions.defaults(), null);

        assertEquals(0, result.failCount(), "실패 페이지 없어야 함: " + result.failedPages());
        assertTrue(Files.isRegularFile(output.resolve("index.html")),
                "index.html이 생성되어야 함");
    }

    @Test
    void exportFullWorkspace_createsAllCategoryPages(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        ExportResult result = new WikiSiteExporter().export(
                workspace, output, ExportOptions.defaults(), null);

        assertEquals(0, result.failCount(), "실패 페이지 없어야 함: " + result.failedPages());
        // 문서 페이지
        assertTrue(Files.isRegularFile(output.resolve("index.html")));
        assertTrue(Files.isRegularFile(output.resolve("overview.html")));
        // 카테고리 페이지
        assertTrue(Files.isRegularFile(output.resolve("entities/Alpha.html")));
        assertTrue(Files.isRegularFile(output.resolve("entities/Beta.html")));
        assertTrue(Files.isRegularFile(output.resolve("concepts/Core.html")));
        assertTrue(Files.isRegularFile(output.resolve("sources/Ref.html")));
    }

    // ─────────────────────────────────────────────
    // 페이지 수 카운트
    // ─────────────────────────────────────────────

    @Test
    void exportResult_pageCountMatchesExpected(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        ExportResult result = new WikiSiteExporter().export(
                workspace, output, ExportOptions.defaults(), null);

        // index + overview + Alpha + Beta + Core + Ref = 6
        assertEquals(6, result.pageCount());
    }

    // ─────────────────────────────────────────────
    // HTML 내용 검증
    // ─────────────────────────────────────────────

    @Test
    void htmlContainsRenderedMarkdown_headingAndParagraph(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        // 마크다운 # Index → <h1>Index</h1>
        assertTrue(html.contains("<h1>Index</h1>") || html.contains("<h1>"), "h1 태그 없음");
        assertTrue(html.contains("환영합니다"), "본문 텍스트 없음");
    }

    @Test
    void htmlContainsTableRendering(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("concepts/Core.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<table>") || html.contains("<TABLE>"), "테이블 미렌더링");
        assertTrue(html.contains("<th>") || html.contains("<TD>"), "th 태그 없음");
    }

    @Test
    void htmlContainsCodeBlockRendering(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("sources/Ref.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<code>") || html.contains("<pre>"), "코드블록 미렌더링");
        assertTrue(html.contains("System.out.println"), "코드 내용 없음");
    }

    // ─────────────────────────────────────────────
    // 위키링크 변환
    // ─────────────────────────────────────────────

    @Test
    void wikiLinkToExistingPage_rendersAsHtmlAnchor(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // entities/Alpha.md 에 [[Beta]] 링크가 있다 → <a href="../entities/Beta.html">
        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("Beta.html"), "Beta.html 링크 없음: " + html);
        assertTrue(html.contains("<a "), "<a> 태그 없음");
    }

    @Test
    void wikiLinkToMissingPage_rendersAsGraySpan(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        Files.writeString(wiki.resolve("index.md"),
                "# Index\n\n[[존재하지않는페이지]]");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<span"), "없는 링크는 span으로 처리되어야 함");
        assertTrue(html.contains("존재하지않는페이지"), "링크 텍스트 없음");
    }

    // ─────────────────────────────────────────────
    // Frontmatter 처리
    // ─────────────────────────────────────────────

    @Test
    void frontmatterTitle_usedAsPageTitle(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        Files.writeString(wiki.resolve("index.md"),
                "---\ntitle: 커스텀 제목\n---\n# Ignored Heading\n\nbody");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("커스텀 제목"), "<title>에 frontmatter title 반영 안됨");
    }

    @Test
    void frontmatterTypeAndDate_renderedAsBlockquote(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        // stripFrontmatterToMeta → > type: entity · last_updated: 2026-06 → <blockquote>
        assertTrue(html.contains("type: entity"), "type 메타 없음");
        assertTrue(html.contains("last_updated"), "last_updated 메타 없음");
    }

    // ─────────────────────────────────────────────
    // 자산 복사
    // ─────────────────────────────────────────────

    @Test
    void assetsDir_containsStyleCssAndNavJs(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        assertTrue(Files.isRegularFile(output.resolve("assets/style.css")),
                "style.css 없음");
        assertTrue(Files.isRegularFile(output.resolve("assets/nav.js")),
                "nav.js 없음");
    }

    @Test
    void styleCss_containsPrintMediaQuery(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String css = Files.readString(output.resolve("assets/style.css"), StandardCharsets.UTF_8);
        assertTrue(css.contains("@media print"), "@media print 없음");
    }

    // ─────────────────────────────────────────────
    // asset 상대 경로 (assetBase)
    // ─────────────────────────────────────────────

    @Test
    void rootPageHtml_referencesAssetsWithNoPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        // 루트 페이지는 "assets/style.css" (../ 없음)
        assertTrue(html.contains("\"assets/style.css\"") || html.contains("href=\"assets/"),
                "루트 페이지 asset 경로가 assets/로 시작해야 함");
    }

    @Test
    void categoryPageHtml_referencesAssetsWithDotDotPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        // entities/ 하위 페이지는 "../assets/style.css"
        assertTrue(html.contains("../assets/style.css") || html.contains("href=\"../assets/"),
                "카테고리 페이지 asset 경로가 ../assets/로 시작해야 함");
    }

    // ─────────────────────────────────────────────
    // 테마 옵션
    // ─────────────────────────────────────────────

    @Test
    void darkThemeOption_setsDataThemeDark(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions darkOpts = new ExportOptions("dark", true, false, false, false, false);

        new WikiSiteExporter().export(workspace, output, darkOpts, null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("data-theme=\"dark\""), "다크 테마 속성 없음");
    }

    @Test
    void lightThemeOption_setsDataThemeLight(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions lightOpts = new ExportOptions("light", true, false, false, false, false);

        new WikiSiteExporter().export(workspace, output, lightOpts, null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("data-theme=\"light\""), "라이트 테마 속성 없음");
    }

    // ─────────────────────────────────────────────
    // 네비게이션 상대경로 — 회귀 테스트
    // ─────────────────────────────────────────────

    /**
     * 루트 페이지(index.html)의 nav 링크는 "sources/page.html" 형태여야 한다.
     * 카테고리 접두어 없이 상대경로만 사용해야 브라우저가 올바르게 해석한다.
     */
    @Test
    void rootPageNav_linksHaveNoDotDotPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String indexHtml = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        // 루트 페이지에서 sources 링크는 "sources/Alpha.html" 형태여야 함
        assertTrue(indexHtml.contains("href=\"sources/") || indexHtml.contains("href=\"entities/"),
                "루트 페이지 nav 링크가 카테고리 경로로 시작해야 함");
        // "../sources/" 형태(카테고리 페이지용)가 없어야 함
        assertFalse(indexHtml.contains("href=\"../sources/"),
                "루트 페이지에 ../sources/ 링크가 있으면 안됨");
    }

    /**
     * 카테고리 페이지(sources/page.html)의 nav 링크는 "../sources/page.html",
     * "../entities/page.html" 형태여야 한다.
     *
     * 이 회귀 테스트는 이전 버그 — sources/page.html에서 "sources/other.html" 링크를
     * 클릭하면 "sources/sources/other.html"로 잘못 해석되던 문제 — 를 방지한다.
     */
    @Test
    void categoryPageNav_linksHaveDotDotPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // entities/Alpha.html의 nav에서 sources 링크는 ../sources/Ref.html이어야 함
        String alphaHtml = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        assertTrue(alphaHtml.contains("href=\"../sources/"),
                "카테고리 페이지 nav에서 타 카테고리 링크는 ../카테고리/ 형태여야 함");
        // "sources/sources/" 같은 이중 경로가 없어야 함
        assertFalse(alphaHtml.contains("href=\"sources/sources/"),
                "이중 카테고리 경로 발생 — sources/sources/ 버그");

        // docs(루트) 링크도 ../index.html 형태여야 함
        assertTrue(alphaHtml.contains("href=\"../index.html") || alphaHtml.contains("href=\"../overview.html"),
                "카테고리 페이지에서 루트 docs 링크는 ../ 접두어가 있어야 함");
    }

    /**
     * 현재 페이지 링크에 class="active"가 설정되어 있는지 검증한다.
     * 서버 사이드 마킹이므로 JS 없이도 정적 HTML에 반영되어야 한다.
     */
    @Test
    void currentPageNav_hasActiveClass(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // entities/Alpha.html을 보면 Alpha 링크에 active class가 있어야 함
        String alphaHtml = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        assertTrue(alphaHtml.contains("class=\"active\""), "현재 페이지 링크에 active 클래스 없음");
        // 단 다른 페이지(Beta, Ref 등) 링크에는 active가 없어야 함
        // active가 딱 한 번만 등장하는지 확인 (active 링크 1개)
        long activeCount = java.util.regex.Pattern.compile("class=\"active\"")
                .matcher(alphaHtml).results().count();
        assertEquals(1, activeCount, "active 클래스가 정확히 1개여야 함, 실제: " + activeCount);
    }

    // ─────────────────────────────────────────────
    // 네비게이션 포함 여부
    // ─────────────────────────────────────────────

    @Test
    void includeNav_true_sidebarContainsCategoryLinks(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions opts = new ExportOptions("dark", true, false, false, false, false);

        new WikiSiteExporter().export(workspace, output, opts, null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("<details"), "네비게이션 <details> 없음");
        assertTrue(html.contains("Alpha.html"), "Alpha 링크 없음");
    }

    @Test
    void includeNav_false_noSidebarNav(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions opts = new ExportOptions("dark", false, false, false, false, false);

        new WikiSiteExporter().export(workspace, output, opts, null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertFalse(html.contains("<details"), "네비게이션 없어야 하는데 <details> 발견");
    }

    // ─────────────────────────────────────────────
    // 검색 인덱스
    // ─────────────────────────────────────────────

    @Test
    void includeSearch_true_createsSearchIndexJson(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions opts = new ExportOptions("dark", true, true, false, false, false);

        new WikiSiteExporter().export(workspace, output, opts, null);

        Path searchIndex = output.resolve("assets/search-index.json");
        assertTrue(Files.isRegularFile(searchIndex), "search-index.json 없음");

        String json = Files.readString(searchIndex, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("["), "JSON 배열이어야 함");
        assertTrue(json.contains("\"title\""), "title 필드 없음");
        assertTrue(json.contains("\"href\""),  "href 필드 없음");
        assertTrue(json.contains("\"body\""),  "body 필드 없음");
    }

    @Test
    void includeSearch_false_noSearchIndexJson(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions opts = new ExportOptions("dark", true, false, false, false, false);

        new WikiSiteExporter().export(workspace, output, opts, null);

        assertFalse(Files.exists(output.resolve("assets/search-index.json")),
                "search-index.json이 생성되지 않아야 함");
    }

    // ─────────────────────────────────────────────
    // cleanOutput 옵션
    // ─────────────────────────────────────────────

    @Test
    void cleanOutput_true_removesStaleFiles(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        Files.createDirectories(output);
        Path stale = output.resolve("stale-old-file.html");
        Files.writeString(stale, "<html>old</html>");

        ExportOptions opts = new ExportOptions("dark", true, false, false, false, true);
        new WikiSiteExporter().export(workspace, output, opts, null);

        assertFalse(Files.exists(stale), "cleanOutput=true이면 기존 파일이 제거되어야 함");
    }

    @Test
    void cleanOutput_false_keepsUnrelatedFiles(@TempDir Path tmp) throws IOException {
        Path workspace = minimalWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        Files.createDirectories(output);
        Path extra = output.resolve("README.txt");
        Files.writeString(extra, "readme");

        ExportOptions opts = new ExportOptions("dark", true, false, false, false, false);
        new WikiSiteExporter().export(workspace, output, opts, null);

        // README.txt는 HTML이 아니므로 orphan 제거 대상이 아님
        assertTrue(Files.exists(extra), "cleanOutput=false이면 비HTML 파일은 유지되어야 함");
    }

    // ─────────────────────────────────────────────
    // orphan HTML 제거
    // ─────────────────────────────────────────────

    @Test
    void reExport_removesOrphanHtmlFromDeletedPage(@TempDir Path tmp) throws IOException {
        // 1차 내보내기: Alpha, Beta 포함
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions opts = new ExportOptions("dark", true, false, false, false, false);
        new WikiSiteExporter().export(workspace, output, opts, null);
        assertTrue(Files.isRegularFile(output.resolve("entities/Beta.html")));

        // 2차: Beta.md 삭제 후 재내보내기 (cleanOutput=false)
        Files.delete(workspace.resolve("wiki/entities/Beta.md"));
        new WikiSiteExporter().export(workspace, output, opts, null);

        assertFalse(Files.exists(output.resolve("entities/Beta.html")),
                "삭제된 페이지의 HTML은 재내보내기 후 제거되어야 함");
        // Alpha는 여전히 있어야 함
        assertTrue(Files.isRegularFile(output.resolve("entities/Alpha.html")));
    }

    // ─────────────────────────────────────────────
    // 진행 콜백
    // ─────────────────────────────────────────────

    @Test
    void progressCallback_invokedForEachPage(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        AtomicInteger callCount = new AtomicInteger(0);
        List<String> files = new ArrayList<>();

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), (file, done, total) -> {
            callCount.incrementAndGet();
            files.add(file);
        });

        // 6개 페이지이므로 콜백도 6번
        assertEquals(6, callCount.get(), "페이지 수만큼 콜백 호출되어야 함");
        assertTrue(files.stream().anyMatch(f -> f.contains("index")), "index 진행 없음");
    }

    @Test
    void progressCallback_doneCountIncreasesMonotonically(@TempDir Path tmp) throws IOException {
        Path workspace = fullWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");

        List<Integer> doneValues = new ArrayList<>();
        List<Integer> totalValues = new ArrayList<>();

        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(),
                (file, done, total) -> {
                    synchronized (doneValues) {
                        doneValues.add(done);
                        totalValues.add(total);
                    }
                });

        // total은 모두 동일
        assertTrue(totalValues.stream().allMatch(t -> t == 6), "total이 일정해야 함");
        // done: 1~6 이 모두 등장해야 함
        for (int i = 1; i <= 6; i++) {
            final int expected = i;
            assertTrue(doneValues.stream().anyMatch(d -> d == expected),
                    "done=" + expected + " 콜백 없음");
        }
    }

    // ─────────────────────────────────────────────
    // 오류 처리 — 유효하지 않은 워크스페이스
    // ─────────────────────────────────────────────

    @Test
    void export_throwsWhenNoIndexMd(@TempDir Path tmp) throws IOException {
        // wiki/ 디렉토리만 있고 index.md 없음
        Files.createDirectories(tmp.resolve("ws/wiki"));
        Path output = tmp.resolve("out");

        assertThrows(IOException.class,
                () -> new WikiSiteExporter().export(tmp.resolve("ws"), output,
                        ExportOptions.defaults(), null),
                "index.md 없으면 IOException이어야 함");
    }

    @Test
    void export_throwsWhenWorkspaceNotExists(@TempDir Path tmp) {
        Path workspace = tmp.resolve("nonexistent");
        Path output    = tmp.resolve("out");

        assertThrows(IOException.class,
                () -> new WikiSiteExporter().export(workspace, output,
                        ExportOptions.defaults(), null),
                "존재하지 않는 워크스페이스는 IOException이어야 함");
    }

    // ─────────────────────────────────────────────
    // 이미지 처리
    // ─────────────────────────────────────────────

    @Test
    void localImage_isCopiedToImagesDir(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        // 더미 이미지 파일 생성
        Path imgSrc = wiki.resolve("screenshot.png");
        Files.write(imgSrc, new byte[]{(byte)0x89, 0x50, 0x4E, 0x47}); // PNG 헤더 일부
        Files.writeString(wiki.resolve("index.md"),
                "# Index\n\n![스크린샷](screenshot.png)");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        // images/ 디렉토리 안에 복사본이 있어야 함
        try (var stream = Files.list(output.resolve("images"))) {
            long count = stream.filter(p -> p.getFileName().toString().endsWith(".png")).count();
            assertTrue(count >= 1, "PNG 이미지가 images/에 복사되어야 함");
        }
    }

    @Test
    void externalImageUrl_remainsUnchanged(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        Files.writeString(wiki.resolve("index.md"),
                "# Index\n\n![외부](https://example.com/img.png)");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("https://example.com/img.png"), "외부 이미지 URL이 변경되면 안됨");
    }

    // ─────────────────────────────────────────────
    // 리포트 포함 여부
    // ─────────────────────────────────────────────

    @Test
    void includeReports_false_skipsReportPages(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        Files.writeString(wiki.resolve("index.md"), "# Index");
        Files.writeString(wiki.resolve("health-report.md"), "# Health Report");
        Files.writeString(wiki.resolve("lint-report.md"), "# Lint Report");
        Path output = tmp.resolve("out");

        ExportOptions opts = new ExportOptions("dark", true, false, false, false, false);
        new WikiSiteExporter().export(tmp.resolve("ws"), output, opts, null);

        assertFalse(Files.exists(output.resolve("health-report.html")),
                "includeReports=false이면 health-report.html 없어야 함");
        assertFalse(Files.exists(output.resolve("lint-report.html")),
                "includeReports=false이면 lint-report.html 없어야 함");
    }

    @Test
    void includeReports_true_includesReportPages(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        Files.writeString(wiki.resolve("index.md"), "# Index");
        Files.writeString(wiki.resolve("health-report.md"), "# Health Report\n\n정상.");
        Path output = tmp.resolve("out");

        ExportOptions opts = new ExportOptions("dark", true, false, false, true, false);
        new WikiSiteExporter().export(tmp.resolve("ws"), output, opts, null);

        // 리포트 페이지는 reports/ 하위 디렉토리에 생성됨
        assertTrue(Files.isRegularFile(output.resolve("reports/health-report.html")),
                "includeReports=true이면 reports/health-report.html 있어야 함");
    }

    // ─────────────────────────────────────────────
    // WikiMarkdownUtils 유닛 테스트
    // ─────────────────────────────────────────────

    @Test
    void normalizeKey_lowercasesAndReplacesSeparators() {
        assertEquals("hello world", WikiMarkdownUtils.normalizeKey("Hello-World"));
        assertEquals("foo bar",     WikiMarkdownUtils.normalizeKey("Foo_Bar"));
        assertEquals("abc def",     WikiMarkdownUtils.normalizeKey("ABC DEF"));
    }

    @Test
    void stem_removesMarkdownExtension() {
        assertEquals("index",   WikiMarkdownUtils.stem("index.md"));
        assertEquals("my-page", WikiMarkdownUtils.stem("my-page.md"));
        assertEquals("data.csv", WikiMarkdownUtils.stem("data.csv")); // .md 아닌 것은 그대로
    }

    @Test
    void stripFrontmatterToMeta_noFrontmatter_returnsUnchanged() {
        String md = "# Title\n\nbody text";
        assertEquals(md, WikiMarkdownUtils.stripFrontmatterToMeta(md));
    }

    @Test
    void stripFrontmatterToMeta_keepsTypeAndLastUpdated() {
        String md = "---\ntitle: Foo\ntype: entity\nlast_updated: 2026-06\nauthor: KYJ\n---\n# Body";
        String result = WikiMarkdownUtils.stripFrontmatterToMeta(md);

        assertTrue(result.contains("type: entity"), "type 보존되어야 함");
        assertTrue(result.contains("last_updated: 2026-06"), "last_updated 보존되어야 함");
        assertFalse(result.contains("author:"), "author는 제거되어야 함");
        assertFalse(result.contains("title:"), "title은 제거되어야 함");
        assertTrue(result.contains("# Body"), "본문 있어야 함");
    }

    @Test
    void readFrontmatterValue_returnsCorrectValue(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("page.md");
        Files.writeString(file, "---\ntitle: My Page\ntype: concept\n---\n# Body");

        assertEquals("My Page", WikiMarkdownUtils.readFrontmatterValue(file, "title"));
        assertEquals("concept",  WikiMarkdownUtils.readFrontmatterValue(file, "type"));
        assertNull(WikiMarkdownUtils.readFrontmatterValue(file, "missing"));
    }

    @Test
    void collectPages_returnsInExpectedOrder(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("wiki");
        Files.createDirectories(wiki.resolve("entities"));
        Files.createDirectories(wiki.resolve("concepts"));
        Files.writeString(wiki.resolve("index.md"),        "# Index");
        Files.writeString(wiki.resolve("overview.md"),     "# Overview");
        Files.writeString(wiki.resolve("entities/A.md"),   "# A");
        Files.writeString(wiki.resolve("concepts/B.md"),   "# B");

        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wiki);

        // collectPages()는 "overview.md" → "index.md" 순으로 검사하므로 overview가 먼저 나온다
        assertEquals("docs", pages.get(0).category());
        assertEquals("overview", pages.get(0).stem());
        // index는 두 번째
        assertEquals("docs", pages.get(1).category());
        assertEquals("index", pages.get(1).stem());
        // entities
        assertTrue(pages.stream().anyMatch(p -> "entities".equals(p.category()) && "A".equals(p.stem())));
        // concepts
        assertTrue(pages.stream().anyMatch(p -> "concepts".equals(p.category()) && "B".equals(p.stem())));
    }
}
