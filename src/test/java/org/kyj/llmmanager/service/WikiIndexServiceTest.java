/*
 * 작성자 : kyj
 * 작성일 : 2026-06-21
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WikiIndexService의 파일별 색인 메타데이터 판정을 검증한다.
 */
class WikiIndexServiceTest {

    /** 중앙 벡터 저장소를 임시 디렉토리로 격리해 실제 사용자 홈을 오염시키지 않는다. */
    @TempDir
    static Path vectorBase;

    @BeforeAll
    static void isolateVectorDir() {
        System.setProperty(WikiVectorRepository.VECTOR_DIR_PROP, vectorBase.toString());
    }

    @AfterAll
    static void restoreVectorDir() {
        System.clearProperty(WikiVectorRepository.VECTOR_DIR_PROP);
    }

    @Test
    void inspectWorkspace_distinguishesCurrentStaleAndNotIndexed(@TempDir Path workspace)
            throws Exception {
        Path wiki = workspace.resolve("wiki");
        Path sources = wiki.resolve("sources");
        Files.createDirectories(sources);
        Files.writeString(wiki.resolve("index.md"), "# Index");
        Path currentFile = sources.resolve("current.md");
        Path missingFile = sources.resolve("missing.md");
        Files.writeString(currentFile, """
                ---
                title: Current
                type: source
                tags: [test]
                sources: []
                last_updated: 2026-06-21
                ---
                ## 내용
                현재 내용
                """);
        Files.writeString(missingFile, "# Missing\n\n## 내용\n미색인 내용");

        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                currentFile, "sources", "wiki/sources/current.md",
                WikiPreprocessor.Options.defaults());
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            for (WikiChunker.Chunk chunk : chunks) {
                repo.upsertChunk("wiki/sources/current.md", chunk, new float[]{1, 0, 0, 0});
            }
        }

        try (WikiIndexService service = new WikiIndexService(new AppSettingsRepository())) {
            WikiIndexService.WorkspaceIndexMetadata first = service.inspectWorkspace(workspace);
            assertEquals(WikiIndexService.PageIndexState.CURRENT,
                    stateOf(first, "wiki/sources/current.md"));
            assertEquals(WikiIndexService.PageIndexState.NOT_INDEXED,
                    stateOf(first, "wiki/sources/missing.md"));

            Files.writeString(currentFile, Files.readString(currentFile) + "\n변경");
            WikiIndexService.WorkspaceIndexMetadata changed = service.inspectWorkspace(workspace);
            assertEquals(WikiIndexService.PageIndexState.STALE,
                    stateOf(changed, "wiki/sources/current.md"));
        }
    }

    private static WikiIndexService.PageIndexState stateOf(
            WikiIndexService.WorkspaceIndexMetadata metadata, String path) {
        return metadata.pages().stream()
                .filter(page -> path.equals(page.pagePath()))
                .findFirst()
                .orElseThrow()
                .state();
    }
}
