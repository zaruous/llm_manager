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

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiChunker의 청킹·frontmatter 추출·hash 생성 로직을 검증한다.
 */
class WikiChunkerTest {

    // ─────────────────────────────────────────────────────────────
    // 기본 청킹
    // ─────────────────────────────────────────────────────────────

    @Test
    void emptyFile_returnsEmptyList(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("empty.md");
        Files.writeString(f, "");
        assertTrue(WikiChunker.chunk(f, "entities").isEmpty());
    }

    @Test
    void noHeadings_singleChunk(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "단순 본문입니다.");
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "sources");
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).chunkNo());
        assertTrue(chunks.get(0).content().contains("단순 본문"));
    }

    @Test
    void h2Headings_splitIntoSeparateChunks(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ## 개요
                첫 번째 섹션입니다.

                ## 상세
                두 번째 섹션입니다.

                ## 결론
                세 번째 섹션입니다.
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "concepts");
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).content().startsWith("## 개요"));
        assertTrue(chunks.get(1).content().startsWith("## 상세"));
        assertTrue(chunks.get(2).content().startsWith("## 결론"));
    }

    @Test
    void h3HeadingAlsoSplits(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ### 하위 항목 A
                내용 A

                ### 하위 항목 B
                내용 B
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "entities");
        assertEquals(2, chunks.size());
    }

    @Test
    void h1HeadingIsNotSplitBoundary(@TempDir Path dir) throws IOException {
        // # (h1) 은 분할 경계가 아님 — ## 이상만 경계
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                # 제목
                본문 내용입니다.
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "entities");
        assertEquals(1, chunks.size());
        // h1 제목도 본문에 포함
        assertTrue(chunks.get(0).content().contains("# 제목"));
    }

    // ─────────────────────────────────────────────────────────────
    // Frontmatter 추출
    // ─────────────────────────────────────────────────────────────

    @Test
    void frontmatter_typeExtracted(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ---
                type: entity
                tags: AI, ML
                ---
                ## 본문
                내용입니다.
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "sources");
        assertFalse(chunks.isEmpty());
        assertEquals("entity", chunks.get(0).type());
        assertEquals("AI, ML", chunks.get(0).tags());
    }

    @Test
    void frontmatter_fallsBackToCategory(@TempDir Path dir) throws IOException {
        // type frontmatter 없으면 category 사용
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ---
                tags: java
                ---
                ## 본문
                내용입니다.
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "concepts");
        assertEquals("concepts", chunks.get(0).type());
    }

    @Test
    void frontmatter_missingTagsIsEmptyString(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ---
                type: source
                ---
                ## 본문
                내용입니다.
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "sources");
        assertEquals("", chunks.get(0).tags());
    }

    @Test
    void frontmatterNotIncludedInChunkContent(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ---
                type: entity
                ---
                ## 본문
                frontmatter는 청크에 들어가지 않아야 한다.
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "entities");
        assertFalse(chunks.isEmpty());
        // frontmatter 구분자(---)가 내용에 없어야 한다
        assertFalse(chunks.get(0).content().contains("---"));
    }

    // ─────────────────────────────────────────────────────────────
    // 슬라이딩 윈도우 (MAX_CHUNK_CHARS 초과 섹션)
    // ─────────────────────────────────────────────────────────────

    @Test
    void largeSection_splitBySlideWindow(@TempDir Path dir) throws IOException {
        // 800자 초과 섹션 → 여러 청크로 분할
        String longContent = "가".repeat(900); // 900자
        Path f = dir.resolve("large.md");
        Files.writeString(f, "## 긴 섹션\n" + longContent);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "sources");
        // 900자 → 첫 청크 800자, 두 번째 청크 300자 (overlap 포함 시작: 900-800=100, start=800-200=600)
        assertTrue(chunks.size() >= 2);
        // 모든 청크가 MAX_CHUNK_CHARS 이하여야 한다
        for (WikiChunker.Chunk c : chunks) {
            assertTrue(c.content().length() <= 800,
                    "청크 길이 초과: " + c.content().length());
        }
    }

    @Test
    void overlapExists_inSubsequentChunks(@TempDir Path dir) throws IOException {
        // OVERLAP_CHARS=200 만큼 이전 청크 끝이 다음 청크 앞에 포함되어야 한다
        String longContent = "A".repeat(800) + "B".repeat(400); // 1200자
        Path f = dir.resolve("overlap.md");
        Files.writeString(f, "## 섹션\n" + longContent);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "sources");
        assertTrue(chunks.size() >= 2);
        // 첫 청크의 마지막 200자가 두 번째 청크의 시작 부분에 포함되어야 한다
        String firstEnd = chunks.get(0).content().substring(
                Math.max(0, chunks.get(0).content().length() - 200));
        assertTrue(chunks.get(1).content().startsWith(firstEnd.substring(0, 50)),
                "두 번째 청크가 첫 청크의 끝 부분으로 시작해야 한다 (overlap)");
    }

    // ─────────────────────────────────────────────────────────────
    // content_hash
    // ─────────────────────────────────────────────────────────────

    @Test
    void contentHash_sameTextProducesSameHash(@TempDir Path dir) throws IOException {
        Path f1 = dir.resolve("a.md");
        Path f2 = dir.resolve("b.md");
        String content = "## 동일 내용\n텍스트가 같습니다.";
        Files.writeString(f1, content);
        Files.writeString(f2, content);
        List<WikiChunker.Chunk> c1 = WikiChunker.chunk(f1, "entities");
        List<WikiChunker.Chunk> c2 = WikiChunker.chunk(f2, "entities");
        assertFalse(c1.isEmpty());
        assertEquals(c1.get(0).contentHash(), c2.get(0).contentHash());
    }

    @Test
    void contentHash_differentTextProducesDifferentHash(@TempDir Path dir) throws IOException {
        Path f1 = dir.resolve("a.md");
        Path f2 = dir.resolve("b.md");
        Files.writeString(f1, "## 섹션\n내용 A");
        Files.writeString(f2, "## 섹션\n내용 B");
        List<WikiChunker.Chunk> c1 = WikiChunker.chunk(f1, "entities");
        List<WikiChunker.Chunk> c2 = WikiChunker.chunk(f2, "entities");
        assertNotEquals(c1.get(0).contentHash(), c2.get(0).contentHash());
    }

    @Test
    void contentHash_is16Chars(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "## 항목\n내용");
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "entities");
        assertEquals(16, chunks.get(0).contentHash().length());
    }

    @Test
    void contentHash_isHexString(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, "## 항목\n내용");
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "entities");
        assertTrue(chunks.get(0).contentHash().matches("[0-9a-f]{16}"));
    }

    // ─────────────────────────────────────────────────────────────
    // chunkNo
    // ─────────────────────────────────────────────────────────────

    @Test
    void chunkNo_startsAtZeroAndIncreases(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ## 섹션 1
                내용 1

                ## 섹션 2
                내용 2

                ## 섹션 3
                내용 3
                """);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(f, "entities");
        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).chunkNo());
        assertEquals(1, chunks.get(1).chunkNo());
        assertEquals(2, chunks.get(2).chunkNo());
    }

    @Test
    void nonExistentFile_returnsEmptyList() {
        Path missing = Path.of("/nonexistent/path/file.md");
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(missing, "entities");
        assertTrue(chunks.isEmpty());
    }
}
