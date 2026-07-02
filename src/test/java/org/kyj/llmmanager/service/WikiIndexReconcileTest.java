/*
 * 작성자 : kyj
 * 작성일 : 2026-07-01
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.service.WikiIndexService.ReconcilePlan;
import org.kyj.llmmanager.service.WikiVectorRepository.ChunkState;
import org.kyj.llmmanager.service.WikiVectorRepository.Relink;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiIndexService.planReconcile의 매칭 우선순위(스킵·재연결·재임베딩·삭제)를 검증한다.
 */
class WikiIndexReconcileTest {

    /** "헤더\n\n본문" 형태의 content를 가진 테스트용 청크를 생성한다. */
    private static WikiChunker.Chunk chunk(int no, String header, String body) {
        String content = header + "\n\n" + body;
        // contentHash는 실제 파이프라인처럼 content 전체에서 유도 (여기선 간이 유일값)
        String contentHash = String.format("%016x", content.hashCode() & 0xFFFFFFFFL);
        return new WikiChunker.Chunk(no, "entity", "", content, contentHash);
    }

    /** 청크와 동일한 해시 상태(완전 일치)를 만든다. */
    private static ChunkState stateOf(WikiChunker.Chunk c) {
        return new ChunkState(c.contentHash(), WikiChunker.bodyHash(c.content()));
    }

    @Test
    void allUnchanged_everythingSkipped() {
        WikiChunker.Chunk c0 = chunk(0, "H", "본문 A");
        WikiChunker.Chunk c1 = chunk(1, "H", "본문 B");
        Map<Integer, ChunkState> existing = new LinkedHashMap<>();
        existing.put(0, stateOf(c0));
        existing.put(1, stateOf(c1));

        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(c0, c1));

        assertEquals(2, plan.skipped());
        assertTrue(plan.relinks().isEmpty());
        assertTrue(plan.toEmbed().isEmpty());
        assertTrue(plan.deletes().isEmpty());
    }

    @Test
    void headerOnlyChange_relinkedInPlace_notReembedded() {
        // DB에는 옛 헤더로 색인된 상태, 새 청크는 같은 본문 + 새 헤더
        WikiChunker.Chunk old0 = chunk(0, "옛헤더", "같은 본문");
        WikiChunker.Chunk new0 = chunk(0, "새헤더", "같은 본문");
        Map<Integer, ChunkState> existing = Map.of(0, stateOf(old0));

        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(new0));

        assertEquals(0, plan.skipped());
        assertEquals(1, plan.relinks().size());
        assertEquals(0, plan.relinks().get(0).oldChunkNo());
        assertEquals(0, plan.relinks().get(0).chunk().chunkNo());
        assertTrue(plan.toEmbed().isEmpty(), "본문이 같으면 재임베딩하지 않아야 한다");
    }

    @Test
    void paragraphInsertedAtFront_tailRelinkedByMove() {
        // 기존: A(0), B(1) — 새 문서: C(0, 신규), A(1), B(2)
        WikiChunker.Chunk oldA = chunk(0, "H", "본문 A");
        WikiChunker.Chunk oldB = chunk(1, "H", "본문 B");
        Map<Integer, ChunkState> existing = new LinkedHashMap<>();
        existing.put(0, stateOf(oldA));
        existing.put(1, stateOf(oldB));

        WikiChunker.Chunk newC = chunk(0, "H", "새 문단 C");
        WikiChunker.Chunk newA = chunk(1, "H", "본문 A");
        WikiChunker.Chunk newB = chunk(2, "H", "본문 B");

        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(newC, newA, newB));

        // C만 임베딩, A·B는 이동 재연결
        assertEquals(1, plan.toEmbed().size());
        assertEquals("새 문단 C", bodyOf(plan.toEmbed().get(0)));
        assertEquals(2, plan.relinks().size());
        Relink moveA = plan.relinks().stream()
                .filter(r -> r.oldChunkNo() == 0).findFirst().orElseThrow();
        assertEquals(1, moveA.chunk().chunkNo());
        assertTrue(plan.deletes().isEmpty());
    }

    @Test
    void chunkRemoved_orphanRowDeleted() {
        WikiChunker.Chunk oldA = chunk(0, "H", "본문 A");
        WikiChunker.Chunk oldB = chunk(1, "H", "본문 B");
        Map<Integer, ChunkState> existing = new LinkedHashMap<>();
        existing.put(0, stateOf(oldA));
        existing.put(1, stateOf(oldB));

        // B가 삭제되고 A만 남음
        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(oldA));

        assertEquals(1, plan.skipped());
        assertEquals(List.of(1), plan.deletes());
        assertTrue(plan.toEmbed().isEmpty());
    }

    @Test
    void legacyRowWithoutBodyHash_reembeddedNotRelinked() {
        // 백필 전 레거시 행(body_hash null) + 헤더 변경 → 재연결 불가, 재임베딩
        WikiChunker.Chunk new0 = chunk(0, "새헤더", "본문");
        Map<Integer, ChunkState> existing = Map.of(0, new ChunkState("oldhash000000000", null));

        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(new0));

        assertTrue(plan.relinks().isEmpty());
        assertEquals(1, plan.toEmbed().size());
        // 위치가 재사용되므로 기존 행은 upsert가 교체 — 삭제 목록에 없어야 함이 아니라,
        // 매칭 실패한 행은 삭제 후 신규 삽입되어도 무방하다. 현 구현은 삭제로 처리한다.
        assertEquals(List.of(0), plan.deletes());
    }

    @Test
    void legacyRow_identicalContentHash_stillSkipped() {
        // 레거시 행이라도 content_hash가 완전히 같으면 스킵되어야 한다
        WikiChunker.Chunk c0 = chunk(0, "H", "본문");
        Map<Integer, ChunkState> existing = Map.of(0, new ChunkState(c0.contentHash(), null));

        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(c0));

        assertEquals(1, plan.skipped());
        assertTrue(plan.deletes().isEmpty());
        assertTrue(plan.toEmbed().isEmpty());
    }

    @Test
    void duplicateBodies_eachOldRowConsumedOnce() {
        // 동일 본문 문단이 두 개 — 각 기존 행은 한 번만 재사용되어야 한다
        WikiChunker.Chunk oldA0 = chunk(0, "옛헤더", "반복 문단");
        WikiChunker.Chunk oldA1 = chunk(1, "옛헤더", "반복 문단");
        Map<Integer, ChunkState> existing = new LinkedHashMap<>();
        existing.put(0, stateOf(oldA0));
        existing.put(1, stateOf(oldA1));

        WikiChunker.Chunk new0 = chunk(0, "새헤더", "반복 문단");
        WikiChunker.Chunk new1 = chunk(1, "새헤더", "반복 문단");
        WikiChunker.Chunk new2 = chunk(2, "새헤더", "반복 문단");

        ReconcilePlan plan = WikiIndexService.planReconcile(existing, List.of(new0, new1, new2));

        // 두 행은 재연결, 세 번째는 재사용할 행이 없어 임베딩
        assertEquals(2, plan.relinks().size());
        assertEquals(1, plan.toEmbed().size());
        long distinctOld = plan.relinks().stream().map(Relink::oldChunkNo).distinct().count();
        assertEquals(2, distinctOld);
    }

    /** 청크 content에서 본문 부분을 추출한다 (테스트 가독성용). */
    private static String bodyOf(WikiChunker.Chunk chunk) {
        int idx = chunk.content().indexOf("\n\n");
        return idx >= 0 ? chunk.content().substring(idx + 2) : chunk.content();
    }
}
