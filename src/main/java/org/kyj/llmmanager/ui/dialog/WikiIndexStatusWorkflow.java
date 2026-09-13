/*
 * 작성자 : kyj
 * 작성일 : 2026-07-08
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.service.WikiIndexService;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * Wiki 색인 상태 조회/재색인 워크플로를 JavaFX 바깥으로 분리한 helper.
 */
final class WikiIndexStatusWorkflow {

    interface IndexOperations {
        WikiIndexService.WorkspaceIndexMetadata inspectWorkspace(Path workspace) throws SQLException;

        WikiIndexService.IndexResult reindexWorkspace(Path workspace, Consumer<String> onProgress);
    }

    record InspectionOutcome(WikiIndexService.WorkspaceIndexMetadata metadata,
                             String summary) {}

    record ReindexOutcome(WikiIndexService.IndexResult indexResult,
                          InspectionOutcome refresh,
                          String message) {}

    private WikiIndexStatusWorkflow() {
    }

    static InspectionOutcome inspect(IndexOperations ops, Path workspace) throws SQLException {
        WikiIndexService.WorkspaceIndexMetadata meta = ops.inspectWorkspace(workspace);
        return new InspectionOutcome(meta, formatSummary(meta));
    }

    static ReindexOutcome reindexAndRefresh(IndexOperations ops, Path workspace,
                                            Consumer<String> onProgress) throws SQLException {
        WikiIndexService.IndexResult result = ops.reindexWorkspace(workspace, onProgress);
        InspectionOutcome refreshed = inspect(ops, workspace);
        return new ReindexOutcome(result, refreshed, formatReindexMessage(result));
    }

    static String formatSummary(WikiIndexService.WorkspaceIndexMetadata meta) {
        long current = meta.count(WikiIndexService.PageIndexState.CURRENT);
        long stale = meta.count(WikiIndexService.PageIndexState.STALE);
        long notIndexed = meta.count(WikiIndexService.PageIndexState.NOT_INDEXED);
        long orphaned = meta.count(WikiIndexService.PageIndexState.ORPHANED);
        long empty = meta.count(WikiIndexService.PageIndexState.EMPTY);
        return String.format("총 %d 페이지 — 최신: %d, 갱신 필요: %d, 미색인: %d, 빈 파일: %d, 고아: %d",
                meta.pages().size(), current, stale, notIndexed, empty, orphaned);
    }

    static String formatReindexMessage(WikiIndexService.IndexResult result) {
        return String.format("재색인 완료 — 새 청크: %d, 건너뜀: %d, 재연결: %d, 삭제 페이지: %d, 오류: %d",
                result.indexedChunks(), result.skippedChunks(), result.relinkedChunks(),
                result.deletedPages(), result.errorPages());
    }
}
