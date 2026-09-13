/*
 * 작성자 : kyj
 * 작성일 : 2026-07-11
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiVectorWorkspaceRegistry의 ID 발급·재조회 안정성과 폴더명 충돌 격리를 검증한다.
 */
class WikiVectorWorkspaceRegistryTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateVectorDir() {
        System.setProperty(WikiVectorRepository.VECTOR_DIR_PROP,
                tempDir.resolve("vector-base").toString());
    }

    @AfterEach
    void restoreVectorDir() {
        System.clearProperty(WikiVectorRepository.VECTOR_DIR_PROP);
    }

    @Test
    void resolveId_sameWorkspaceReturnsStableId() {
        Path workspace = tempDir.resolve("my-wiki");

        String first = WikiVectorWorkspaceRegistry.resolveId(workspace);
        String second = WikiVectorWorkspaceRegistry.resolveId(workspace);

        assertEquals(first, second);
        assertTrue(first.matches("my-wiki-[0-9a-f]{8}"),
                "ID는 <폴더명>-<8자리 hex> 형식이어야 한다: " + first);
        assertTrue(Files.isRegularFile(tempDir.resolve("vector-base")
                .resolve(WikiVectorWorkspaceRegistry.REGISTRY_FILE_NAME)));
    }

    @Test
    void resolveId_sameFolderNameDifferentParentsGetDistinctIds() {
        // 폴더명이 "docs"로 동일한 서로 다른 워크스페이스
        Path wsA = tempDir.resolve("project-a").resolve("docs");
        Path wsB = tempDir.resolve("project-b").resolve("docs");

        String idA = WikiVectorWorkspaceRegistry.resolveId(wsA);
        String idB = WikiVectorWorkspaceRegistry.resolveId(wsB);

        assertNotEquals(idA, idB);
        // 재조회 시에도 각자의 ID가 유지되어야 한다
        assertEquals(idA, WikiVectorWorkspaceRegistry.resolveId(wsA));
        assertEquals(idB, WikiVectorWorkspaceRegistry.resolveId(wsB));
    }

    @Test
    void resolveDbFile_usesRegistryIdDirectory() {
        Path workspace = tempDir.resolve("wiki-ws");

        Path dbFile = WikiVectorRepository.resolveDbFile(workspace);
        String id = WikiVectorWorkspaceRegistry.resolveId(workspace);

        assertEquals(tempDir.resolve("vector-base").resolve(id)
                        .resolve("wiki-vector.sqlite").toAbsolutePath().normalize(),
                dbFile);
    }

    @Test
    void resolveId_survivesCorruptRegistryFile() throws Exception {
        Path base = tempDir.resolve("vector-base");
        Files.createDirectories(base);
        Files.writeString(base.resolve(WikiVectorWorkspaceRegistry.REGISTRY_FILE_NAME),
                "{ 깨진 JSON");

        Path workspace = tempDir.resolve("broken-case");
        String id = WikiVectorWorkspaceRegistry.resolveId(workspace);

        assertTrue(id.startsWith("broken-case-"));
        // 손상 파일이 정상 레지스트리로 대체되어 재조회가 안정적이어야 한다
        assertEquals(id, WikiVectorWorkspaceRegistry.resolveId(workspace));
    }
}
