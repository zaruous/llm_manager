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

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiWorkspaceInitializer의 골격 생성·초기화 상태 검사 로직을 검증한다.
 */
class WikiWorkspaceInitializerTest {

    // ─────────────────────────────────────────────────────────────
    // isInitialized
    // ─────────────────────────────────────────────────────────────

    @Test
    void isInitialized_false_whenIndexMissing(@TempDir Path workspace) {
        assertFalse(WikiWorkspaceInitializer.isInitialized(workspace));
    }

    @Test
    void isInitialized_false_whenWikiDirMissing(@TempDir Path workspace) {
        // wiki/ 디렉토리 자체가 없는 경우
        assertFalse(WikiWorkspaceInitializer.isInitialized(workspace));
    }

    @Test
    void isInitialized_true_whenIndexExists(@TempDir Path workspace) throws IOException {
        Path wikiDir = workspace.resolve("wiki");
        Files.createDirectories(wikiDir);
        Files.writeString(wikiDir.resolve("index.md"), "# Index");
        assertTrue(WikiWorkspaceInitializer.isInitialized(workspace));
    }

    @Test
    void isInitialized_false_whenIndexIsDirectory(@TempDir Path workspace) throws IOException {
        // index.md가 파일이 아닌 디렉토리인 경우
        Path wikiDir = workspace.resolve("wiki");
        Files.createDirectories(wikiDir.resolve("index.md"));
        assertFalse(WikiWorkspaceInitializer.isInitialized(workspace));
    }

    // ─────────────────────────────────────────────────────────────
    // initialize — 골격 파일 생성
    // ─────────────────────────────────────────────────────────────

    @Test
    void initialize_createsWikiIndexMd(@TempDir Path workspace) throws IOException {
        WikiWorkspaceInitializer.initialize(workspace);
        assertTrue(Files.isRegularFile(workspace.resolve("wiki/index.md")),
                "wiki/index.md가 생성되어야 한다");
    }

    @Test
    void initialize_createsWikiLogMd(@TempDir Path workspace) throws IOException {
        WikiWorkspaceInitializer.initialize(workspace);
        assertTrue(Files.isRegularFile(workspace.resolve("wiki/log.md")));
    }

    @Test
    void initialize_createsRawDirectory(@TempDir Path workspace) throws IOException {
        WikiWorkspaceInitializer.initialize(workspace);
        assertTrue(Files.isDirectory(workspace.resolve("raw")));
    }

    @Test
    void initialize_createsGraphGitignore(@TempDir Path workspace) throws IOException {
        WikiWorkspaceInitializer.initialize(workspace);
        assertTrue(Files.isRegularFile(workspace.resolve("graph/.gitignore")));
    }

    @Test
    void initialize_indexMdIsNotEmpty(@TempDir Path workspace) throws IOException {
        WikiWorkspaceInitializer.initialize(workspace);
        String content = Files.readString(workspace.resolve("wiki/index.md"));
        assertFalse(content.isBlank(), "index.md는 내용이 있어야 한다");
    }

    @Test
    void initialize_doesNotOverwriteExistingFile(@TempDir Path workspace) throws IOException {
        Path indexMd = workspace.resolve("wiki/index.md");
        Files.createDirectories(indexMd.getParent());
        Files.writeString(indexMd, "# Custom Content");

        WikiWorkspaceInitializer.initialize(workspace);

        assertEquals("# Custom Content", Files.readString(indexMd),
                "기존 파일을 덮어쓰지 않아야 한다");
    }

    @Test
    void initialize_isInitialized_afterInitialize(@TempDir Path workspace) throws IOException {
        assertFalse(WikiWorkspaceInitializer.isInitialized(workspace));
        WikiWorkspaceInitializer.initialize(workspace);
        assertTrue(WikiWorkspaceInitializer.isInitialized(workspace));
    }

    @Test
    void initialize_idempotent_calledTwice(@TempDir Path workspace) throws IOException {
        WikiWorkspaceInitializer.initialize(workspace);
        // 두 번 호출해도 예외 없이 통과해야 한다
        assertDoesNotThrow(() -> WikiWorkspaceInitializer.initialize(workspace));
    }

    // ─────────────────────────────────────────────────────────────
    // rememberWorkspace — null/blank 가드
    // ─────────────────────────────────────────────────────────────

    @Test
    void rememberWorkspace_nullWorkspace_noException() {
        // workspace가 null이면 repository 접근 없이 바로 반환
        assertDoesNotThrow(() -> WikiWorkspaceInitializer.rememberWorkspace(null, null));
    }

    @Test
    void rememberWorkspace_blankWorkspace_noException() {
        assertDoesNotThrow(() -> WikiWorkspaceInitializer.rememberWorkspace(null, "   "));
    }

    @Test
    void rememberWorkspace_emptyString_noException() {
        assertDoesNotThrow(() -> WikiWorkspaceInitializer.rememberWorkspace(null, ""));
    }
}
