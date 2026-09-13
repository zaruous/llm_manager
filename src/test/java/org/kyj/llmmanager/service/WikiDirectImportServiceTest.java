/*
 * 작성자 : kyj
 * 작성일 : 2026-07-08
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.service.WikiIndexService.IndexResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 옵시디언 direct import가 source wrapper 생성·index/log 동기화·재색인 훅 호출을 수행하는지 검증한다.
 */
class WikiDirectImportServiceTest {

    @Test
    void importMarkdownFiles_createsSourceWrapper_updatesIndexAndLog_andTriggersReindex(
            @TempDir Path workspace) throws Exception {
        WikiWorkspaceInitializer.initialize(workspace);
        Path rawFile = workspace.resolve("raw/articles/architecture-notes.md");
        Files.createDirectories(rawFile.getParent());
        Files.writeString(rawFile, """
                ---
                title: Architecture Notes
                tags: [obsidian, arch]
                ---
                # Architecture Notes

                [[Docstor]] 와 [[WikiIndex]] 연결을 검토한다.
                """);

        AtomicInteger reindexCalls = new AtomicInteger();
        List<String> output = new ArrayList<>();
        WikiDirectImportService service = new WikiDirectImportService((root, onOutput) -> {
            reindexCalls.incrementAndGet();
            onOutput.accept("[test-index] reindex called");
            return new IndexResult(root, 3, 1, 0, 0, 0);
        });

        WikiDirectImportService.ImportResult result = service.importMarkdownFiles(
                workspace, List.of(rawFile), output::add);

        Path sourcePage = workspace.resolve("wiki/sources/architecture-notes.md");
        assertTrue(Files.isRegularFile(sourcePage), "wiki/sources wrapper가 생성되어야 한다");
        String sourceContent = Files.readString(sourcePage);
        assertTrue(sourceContent.contains("title: \"Architecture Notes\""));
        assertTrue(sourceContent.contains("type: source"));
        assertTrue(sourceContent.contains("tags: [obsidian-import]"));
        assertTrue(sourceContent.contains("source_file: raw/articles/architecture-notes.md"));
        assertTrue(sourceContent.contains("import_mode: direct-obsidian"));
        assertTrue(sourceContent.contains("[[Docstor]] 와 [[WikiIndex]] 연결을 검토한다."));

        String indexContent = Files.readString(workspace.resolve("wiki/index.md"));
        assertTrue(indexContent.contains("[Architecture Notes](sources/architecture-notes.md)"));
        assertTrue(indexContent.contains("direct obsidian import"));

        String logContent = Files.readString(workspace.resolve("wiki/log.md"));
        assertTrue(logContent.contains("ingest | Architecture Notes (direct-obsidian)"));

        assertEquals(1, result.importedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(1, reindexCalls.get(), "direct import 완료 후 재색인 훅이 1회 호출되어야 한다");
        assertNotNull(result.indexResult());
        assertTrue(output.stream().anyMatch(line -> line.contains("reindex called")));
    }

    @Test
    void importMarkdownFiles_skipsNonMarkdown_andDoesNotTriggerReindex(@TempDir Path workspace)
            throws Exception {
        WikiWorkspaceInitializer.initialize(workspace);
        Path textFile = workspace.resolve("raw/articles/plain.txt");
        Files.createDirectories(textFile.getParent());
        Files.writeString(textFile, "not markdown");

        AtomicInteger reindexCalls = new AtomicInteger();
        WikiDirectImportService service = new WikiDirectImportService((root, onOutput) -> {
            reindexCalls.incrementAndGet();
            return new IndexResult(root, 0, 0, 0, 0, 0);
        });

        WikiDirectImportService.ImportResult result = service.importMarkdownFiles(
                workspace, List.of(textFile), ignored -> {});

        assertEquals(0, result.importedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(0, reindexCalls.get(), "수입된 markdown가 없으면 재색인하지 않아야 한다");
        assertFalse(Files.isDirectory(workspace.resolve("wiki/sources"))
                        && Files.list(workspace.resolve("wiki/sources")).findAny().isPresent(),
                "비마크다운만 있을 때는 source wrapper가 없어야 한다");
    }
}
