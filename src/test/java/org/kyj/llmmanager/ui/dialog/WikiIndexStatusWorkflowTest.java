/*
 * 작성자 : kyj
 * 작성일 : 2026-07-08
 */
package org.kyj.llmmanager.ui.dialog;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.service.WikiIndexService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WikiIndexStatusWorkflow의 재색인/상태 새로고침 흐름을 검증한다.
 */
class WikiIndexStatusWorkflowTest {

    @Test
    void reindexAndRefresh_runsReindexThenReloadsSummary() throws Exception {
        Path workspace = Path.of("D:/wiki-workspace");
        AtomicBoolean reindexCalled = new AtomicBoolean(false);

        WikiIndexStatusWorkflow.IndexOperations ops = new WikiIndexStatusWorkflow.IndexOperations() {
            @Override
            public WikiIndexService.WorkspaceIndexMetadata inspectWorkspace(Path target) {
                return new WikiIndexService.WorkspaceIndexMetadata(target, List.of(
                        new WikiIndexService.PageIndexMetadata(
                                "wiki/sources/sample.md",
                                "sources",
                                1200,
                                3,
                                3,
                                WikiIndexService.PageIndexState.CURRENT)));
            }

            @Override
            public WikiIndexService.IndexResult reindexWorkspace(Path target, java.util.function.Consumer<String> onProgress) {
                reindexCalled.set(true);
                if (onProgress != null) onProgress.accept("wiki-index: sample 진행");
                return new WikiIndexService.IndexResult(target, 5, 2, 1, 0, 0);
            }
        };

        WikiIndexStatusWorkflow.ReindexOutcome outcome =
                WikiIndexStatusWorkflow.reindexAndRefresh(ops, workspace, null);

        assertTrue(reindexCalled.get());
        assertEquals("총 1 페이지 — 최신: 1, 갱신 필요: 0, 미색인: 0, 빈 파일: 0, 고아: 0",
                outcome.refresh().summary());
        assertEquals("재색인 완료 — 새 청크: 5, 건너뜀: 2, 재연결: 1, 삭제 페이지: 0, 오류: 0",
                outcome.message());
    }

    @Test
    void inspect_formatsSummaryFromWorkspaceMetadata() throws Exception {
        Path workspace = Path.of("D:/wiki-workspace");
        WikiIndexStatusWorkflow.IndexOperations ops = new WikiIndexStatusWorkflow.IndexOperations() {
            @Override
            public WikiIndexService.WorkspaceIndexMetadata inspectWorkspace(Path target) {
                return new WikiIndexService.WorkspaceIndexMetadata(target, List.of(
                        new WikiIndexService.PageIndexMetadata("wiki/sources/a.md", "sources",
                                10, 1, 1, WikiIndexService.PageIndexState.CURRENT),
                        new WikiIndexService.PageIndexMetadata("wiki/sources/b.md", "sources",
                                20, 2, 1, WikiIndexService.PageIndexState.STALE),
                        new WikiIndexService.PageIndexMetadata("wiki/sources/c.md", "sources",
                                30, 2, 0, WikiIndexService.PageIndexState.NOT_INDEXED)));
            }

            @Override
            public WikiIndexService.IndexResult reindexWorkspace(Path target, java.util.function.Consumer<String> onProgress) {
                throw new UnsupportedOperationException();
            }
        };

        WikiIndexStatusWorkflow.InspectionOutcome outcome = WikiIndexStatusWorkflow.inspect(ops, workspace);

        assertEquals(3, outcome.metadata().pages().size());
        assertEquals("총 3 페이지 — 최신: 1, 갱신 필요: 1, 미색인: 1, 빈 파일: 0, 고아: 0",
                outcome.summary());
    }
}
