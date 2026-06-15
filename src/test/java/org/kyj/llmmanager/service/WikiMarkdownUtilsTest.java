/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiMarkdownUtils의 페이지 수집·색인·frontmatter 파싱·키 정규화 로직을 검증한다.
 */
class WikiMarkdownUtilsTest {

    // ─────────────────────────────────────────────────────────────
    // stem
    // ─────────────────────────────────────────────────────────────

    @Test
    void stem_stripsExtension() {
        assertEquals("index", WikiMarkdownUtils.stem("index.md"));
    }

    @Test
    void stem_noExtension_returnsAsIs() {
        assertEquals("readme", WikiMarkdownUtils.stem("readme"));
    }

    @Test
    void stem_path_stripsExtension(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("overview.md");
        Files.writeString(f, "");
        assertEquals("overview", WikiMarkdownUtils.stem(f));
    }

    // ─────────────────────────────────────────────────────────────
    // normalizeKey
    // ─────────────────────────────────────────────────────────────

    @Test
    void normalizeKey_toLowercase() {
        assertEquals("mypage", WikiMarkdownUtils.normalizeKey("MyPage"));
    }

    @Test
    void normalizeKey_hyphenToSpace() {
        assertEquals("my page", WikiMarkdownUtils.normalizeKey("my-page"));
    }

    @Test
    void normalizeKey_underscoreToSpace() {
        assertEquals("my page", WikiMarkdownUtils.normalizeKey("my_page"));
    }

    @Test
    void normalizeKey_trimmed() {
        assertEquals("hello", WikiMarkdownUtils.normalizeKey("  hello  "));
    }

    @Test
    void normalizeKey_mixedCase() {
        assertEquals("some thing", WikiMarkdownUtils.normalizeKey("Some_Thing"));
    }

    // ─────────────────────────────────────────────────────────────
    // stripFrontmatterToMeta
    // ─────────────────────────────────────────────────────────────

    @Test
    void stripFrontmatterToMeta_noFrontmatter_returnsOriginal() {
        String md = "## Hello\ncontent here";
        assertEquals(md, WikiMarkdownUtils.stripFrontmatterToMeta(md));
    }

    @Test
    void stripFrontmatterToMeta_keepsTypeAndLastUpdated() {
        String md = """
                ---
                type: entity
                title: Test Page
                last_updated: 2026-06-01
                ---
                ## Body
                content
                """;
        String result = WikiMarkdownUtils.stripFrontmatterToMeta(md);
        assertFalse(result.contains("title:"), "title은 메타에 포함되지 않아야 한다");
        assertTrue(result.contains("type: entity"));
        assertTrue(result.contains("last_updated: 2026-06-01"));
        assertTrue(result.contains("## Body"));
    }

    @Test
    void stripFrontmatterToMeta_noTypeOrLastUpdated_justReturnsBody() {
        String md = """
                ---
                title: Only Title
                ---
                body line
                """;
        String result = WikiMarkdownUtils.stripFrontmatterToMeta(md);
        assertFalse(result.trim().startsWith(">"), "타입·날짜 없으면 인용구 없어야 한다");
        assertTrue(result.contains("body line"));
    }

    @Test
    void stripFrontmatterToMeta_unclosedFrontmatter_returnsOriginal() {
        String md = "---\ntype: entity\nno closing fence";
        assertEquals(md, WikiMarkdownUtils.stripFrontmatterToMeta(md));
    }

    @Test
    void stripFrontmatterToMeta_typeOnly_producesMetaLine() {
        String md = """
                ---
                type: source
                ---
                ## Section
                """;
        String result = WikiMarkdownUtils.stripFrontmatterToMeta(md);
        assertTrue(result.startsWith("> type: source"), "type만 있으면 인용구 한 줄이어야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // listMarkdown
    // ─────────────────────────────────────────────────────────────

    @Test
    void listMarkdown_nonExistentDir_returnsEmpty() {
        List<Path> result = WikiMarkdownUtils.listMarkdown(Path.of("nonexistent-xyz-abc"));
        assertTrue(result.isEmpty());
    }

    @Test
    void listMarkdown_filtersMdFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), "# A");
        Files.writeString(dir.resolve("b.md"), "# B");
        Files.writeString(dir.resolve("c.txt"), "not md");
        List<Path> result = WikiMarkdownUtils.listMarkdown(dir);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(p -> p.getFileName().toString().endsWith(".md")));
    }

    @Test
    void listMarkdown_returnsSorted(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("c.md"), "c");
        Files.writeString(dir.resolve("a.md"), "a");
        Files.writeString(dir.resolve("b.md"), "b");
        List<Path> result = WikiMarkdownUtils.listMarkdown(dir);
        assertEquals("a.md", result.get(0).getFileName().toString());
        assertEquals("b.md", result.get(1).getFileName().toString());
        assertEquals("c.md", result.get(2).getFileName().toString());
    }

    @Test
    void listMarkdown_emptyDir_returnsEmpty(@TempDir Path dir) {
        assertTrue(WikiMarkdownUtils.listMarkdown(dir).isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    // readFrontmatterValue
    // ─────────────────────────────────────────────────────────────

    @Test
    void readFrontmatterValue_existingKey(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "---\ntitle: My Page\n---\nbody");
        assertEquals("My Page", WikiMarkdownUtils.readFrontmatterValue(f, "title"));
    }

    @Test
    void readFrontmatterValue_missingKey_returnsNull(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "---\ntitle: My Page\n---\nbody");
        assertNull(WikiMarkdownUtils.readFrontmatterValue(f, "author"));
    }

    @Test
    void readFrontmatterValue_noFrontmatter_returnsNull(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "# No frontmatter\nbody");
        assertNull(WikiMarkdownUtils.readFrontmatterValue(f, "title"));
    }

    @Test
    void readFrontmatterValue_quotedValue_stripsQuotes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "---\ntitle: \"Quoted Title\"\n---\nbody");
        assertEquals("Quoted Title", WikiMarkdownUtils.readFrontmatterValue(f, "title"));
    }

    @Test
    void readFrontmatterValue_nonExistentFile_returnsNull() {
        Path missing = Path.of("nonexistent-file.md");
        assertNull(WikiMarkdownUtils.readFrontmatterValue(missing, "title"));
    }

    // ─────────────────────────────────────────────────────────────
    // collectPages
    // ─────────────────────────────────────────────────────────────

    @Test
    void collectPages_noFiles_returnsEmpty(@TempDir Path wikiDir) {
        assertTrue(WikiMarkdownUtils.collectPages(wikiDir).isEmpty());
    }

    @Test
    void collectPages_findsDocsPages(@TempDir Path wikiDir) throws IOException {
        Files.writeString(wikiDir.resolve("index.md"), "# Index");
        Files.writeString(wikiDir.resolve("log.md"), "# Log");
        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        assertTrue(pages.stream().anyMatch(p -> p.stem().equals("index")));
        assertTrue(pages.stream().anyMatch(p -> p.stem().equals("log")));
    }

    @Test
    void collectPages_categoryPagesCollected(@TempDir Path wikiDir) throws IOException {
        Path entities = wikiDir.resolve("entities");
        Files.createDirectories(entities);
        Files.writeString(entities.resolve("thing.md"), "# Thing");
        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        assertTrue(pages.stream().anyMatch(p -> p.stem().equals("thing")));
    }

    @Test
    void collectPages_categoryAssignedCorrectly(@TempDir Path wikiDir) throws IOException {
        Path sources = wikiDir.resolve("sources");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("paper.md"), "# Paper");
        WikiMarkdownUtils.PageEntry entry = WikiMarkdownUtils.collectPages(wikiDir).stream()
                .filter(p -> p.stem().equals("paper")).findFirst().orElseThrow();
        assertEquals("sources", entry.category());
    }

    @Test
    void collectPages_reportsIncludedWhenPresent(@TempDir Path wikiDir) throws IOException {
        Files.writeString(wikiDir.resolve("health-report.md"), "# Health");
        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        assertTrue(pages.stream().anyMatch(p -> p.stem().equals("health-report")));
    }

    // ─────────────────────────────────────────────────────────────
    // buildPageIndex
    // ─────────────────────────────────────────────────────────────

    @Test
    void buildPageIndex_stemRegistered(@TempDir Path wikiDir) throws IOException {
        Files.writeString(wikiDir.resolve("index.md"), "---\ntitle: Wiki Home\n---\nbody");
        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        Map<String, Path> index = WikiMarkdownUtils.buildPageIndex(pages);
        assertTrue(index.containsKey("index"), "파일 스템이 색인에 등록되어야 한다");
    }

    @Test
    void buildPageIndex_normalizedTitleRegistered(@TempDir Path wikiDir) throws IOException {
        Files.writeString(wikiDir.resolve("index.md"), "---\ntitle: Wiki Home\n---\nbody");
        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        Map<String, Path> index = WikiMarkdownUtils.buildPageIndex(pages);
        assertTrue(index.containsKey("wiki home"), "정규화된 title이 색인에 등록되어야 한다");
    }

    @Test
    void buildPageIndex_pointsToCorrectFile(@TempDir Path wikiDir) throws IOException {
        Files.writeString(wikiDir.resolve("index.md"), "# Index");
        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        Map<String, Path> index = WikiMarkdownUtils.buildPageIndex(pages);
        assertEquals(wikiDir.resolve("index.md"), index.get("index"));
    }

    @Test
    void buildPageIndex_emptyPages_emptyIndex() {
        assertTrue(WikiMarkdownUtils.buildPageIndex(List.of()).isEmpty());
    }
}
