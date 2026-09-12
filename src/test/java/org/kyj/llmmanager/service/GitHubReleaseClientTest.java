/*
 * 작성자 : kyj
 * 작성일 : 2026-09-12
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GitHubReleaseClient}의 URL 파싱과 asset 선택 규칙 검증.
 * 네트워크를 타지 않도록 릴리즈 JSON을 직접 넣어 확인한다.
 */
class GitHubReleaseClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String asset(String name, long size) {
        return """
                {"name": "%s", "size": %d,
                 "browser_download_url": "https://github.com/o/r/releases/download/v1.0.0/%s"}
                """.formatted(name, size, name);
    }

    private static GitHubReleaseClient.ReleaseAsset select(String tag, String... assets)
            throws IOException {
        String json = """
                {"tag_name": "%s", "assets": [%s]}
                """.formatted(tag, String.join(",", assets));
        return GitHubReleaseClient.selectJarAsset(MAPPER.readTree(json));
    }

    @Test
    @DisplayName("릴리즈 다운로드 URL에서 owner/repo를 추출한다")
    void parsesOwnerRepo() {
        assertEquals("zaruous/sql-mcp-release", GitHubReleaseClient.parseOwnerRepo(
                "https://github.com/zaruous/sql-mcp-release/releases/download/v1.0.0/sql-gen-mcp-1.0.0.jar"));
    }

    @Test
    @DisplayName("GitHub 릴리즈 URL이 아니면 null을 돌려준다")
    void rejectsNonReleaseUrl() {
        assertNull(GitHubReleaseClient.parseOwnerRepo("https://example.com/a.jar"));
        assertNull(GitHubReleaseClient.parseOwnerRepo("https://github.com/zaruous/sql-mcp-release"));
        assertNull(GitHubReleaseClient.parseOwnerRepo(null));
    }

    @Test
    @DisplayName("SNAPSHOT 파일이 더 커도 태그 버전과 맞는 JAR을 고른다")
    void prefersVersionMatchOverSize() throws IOException {
        // 실제 v1.0.0 릴리즈 상황 — SNAPSHOT 쪽이 31바이트 더 크다
        var picked = select("v1.0.0",
                asset("sql-gen-mcp-1.0.0-SNAPSHOT.jar", 251034851L),
                asset("sql-gen-mcp-1.0.0.jar", 251034820L));

        assertEquals("sql-gen-mcp-1.0.0.jar", picked.assetName());
        assertEquals("v1.0.0", picked.tagName());
        assertEquals(251034820L, picked.size());
    }

    @Test
    @DisplayName("shade가 남기는 original- JAR은 후보에서 제외한다")
    void skipsOriginalJar() throws IOException {
        var picked = select("v1.0.0",
                asset("original-sql-gen-mcp-1.0.0.jar", 510000L),
                asset("sql-gen-mcp-1.0.0.jar", 251034820L));

        assertEquals("sql-gen-mcp-1.0.0.jar", picked.assetName());
    }

    @Test
    @DisplayName("버전이 맞는 파일이 없으면 SNAPSHOT이 아닌 쪽을 고른다")
    void fallsBackToNonSnapshot() throws IOException {
        var picked = select("v2.0.0",
                asset("app-1.9.0-SNAPSHOT.jar", 900L),
                asset("app-1.9.0.jar", 100L));

        assertEquals("app-1.9.0.jar", picked.assetName());
    }

    @Test
    @DisplayName("JAR asset이 없으면 예외를 던진다")
    void failsWhenNoJar() {
        IOException e = assertThrows(IOException.class,
                () -> select("v1.0.0", asset("notes.txt", 10L)));
        assertTrue(e.getMessage().contains("v1.0.0"));
    }
}
