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
        assertTrue(chunks.get(0).content().contains("제목: page"));
        assertTrue(chunks.get(0).content().contains("경로:"));
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
        assertTrue(chunks.get(0).content().contains("섹션: 개요"));
        assertTrue(chunks.get(1).content().contains("섹션: 상세"));
        assertTrue(chunks.get(2).content().contains("섹션: 결론"));
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
        assertTrue(chunks.get(0).content().contains("제목: 제목"));
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
    void largeSection_splitByConfiguredMaximum(@TempDir Path dir) throws IOException {
        String longContent = "가".repeat(3000);
        Path f = dir.resolve("large.md");
        Files.writeString(f, "## 긴 섹션\n" + longContent);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                f, "sources", "wiki/sources/large.md",
                new WikiPreprocessor.Options(700, 900, 100));
        assertTrue(chunks.size() >= 2);
        for (WikiChunker.Chunk c : chunks) {
            // 메타데이터 헤더가 추가되므로 본문 최대값보다 소폭 길다.
            assertTrue(c.content().length() <= 1100,
                    "청크 길이 초과: " + c.content().length());
        }
    }

    @Test
    void overlapExists_inSubsequentChunks(@TempDir Path dir) throws IOException {
        String longContent = "A".repeat(900) + "B".repeat(500);
        Path f = dir.resolve("overlap.md");
        Files.writeString(f, "## 섹션\n" + longContent);
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                f, "sources", "wiki/sources/overlap.md",
                new WikiPreprocessor.Options(500, 700, 100));
        assertTrue(chunks.size() >= 2);
        String firstBody = chunks.get(0).content().substring(
                chunks.get(0).content().indexOf("\n\n") + 2);
        String secondBody = chunks.get(1).content().substring(
                chunks.get(1).content().indexOf("\n\n") + 2);
        assertTrue(secondBody.startsWith(firstBody.substring(firstBody.length() - 50)),
                "두 번째 청크 본문이 첫 청크의 끝 문맥으로 시작해야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // content_hash
    // ─────────────────────────────────────────────────────────────

    @Test
    void contentHash_sameTextProducesSameHash(@TempDir Path dir) throws IOException {
        Path f1 = dir.resolve("same.md");
        String content = "## 동일 내용\n텍스트가 같습니다.";
        Files.writeString(f1, content);
        List<WikiChunker.Chunk> c1 = WikiChunker.chunk(f1, "entities");
        List<WikiChunker.Chunk> c2 = WikiChunker.chunk(f1, "entities");
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

    @Test
    void metadataInjected_whenFormatterDidNotCreateFrontmatter(@TempDir Path dir)
            throws IOException {
        Path f = dir.resolve("BGE-M3.md");
        Files.writeString(f, """
                # BGE-M3 검색 설계

                ## 증분 색인
                변경된 청크만 임베딩한다.
                """);

        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                f, "concepts", "wiki/concepts/BGE-M3.md",
                WikiPreprocessor.Options.defaults());

        assertEquals(1, chunks.size());
        String content = chunks.get(0).content();
        assertTrue(content.contains("제목: BGE-M3 검색 설계"));
        assertTrue(content.contains("유형: concepts"));
        assertTrue(content.contains("경로: wiki/concepts/BGE-M3.md"));
        assertTrue(content.contains("섹션: 증분 색인"));
    }

    @Test
    void yamlListTags_normalizedForEmbedding(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("page.md");
        Files.writeString(f, """
                ---
                title: 검색 설계
                type: concept
                tags:
                  - embedding
                  - vector-search
                sources: []
                last_updated: 2026-06-21
                ---
                ## 개요
                본문
                """);

        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                f, "concepts", "wiki/concepts/page.md",
                WikiPreprocessor.Options.defaults());

        assertEquals("embedding, vector-search", chunks.get(0).tags());
        assertTrue(chunks.get(0).content().contains("태그: embedding, vector-search"));
    }

    @Test
    void codeFence_keptInSingleChunk(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("code.md");
        Files.writeString(f, """
                ## 예제
                앞 문단입니다.

                ```java
                String value = "%s";
                System.out.println(value);
                ```

                뒤 문단입니다.
                """.formatted("x".repeat(900)));

        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                f, "concepts", "wiki/concepts/code.md",
                new WikiPreprocessor.Options(300, 500, 50));

        long codeChunks = chunks.stream()
                .filter(chunk -> chunk.content().contains("```java"))
                .count();
        assertEquals(1, codeChunks);
        assertTrue(chunks.stream().anyMatch(chunk ->
                chunk.content().contains("System.out.println(value);")));
    }
}
