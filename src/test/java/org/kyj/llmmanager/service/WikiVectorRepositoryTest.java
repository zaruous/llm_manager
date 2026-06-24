/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiVectorRepository의 BLOB 직렬화, 증분 색인, 선형 탐색 폴백을 검증한다.
 * vec0 네이티브 바이너리 없이 실행 가능한 테스트만 포함한다.
 */
class WikiVectorRepositoryTest {

    // ─────────────────────────────────────────────────────────────
    // BLOB 직렬화 (package-private static 메서드 직접 검증)
    // ─────────────────────────────────────────────────────────────

    @Test
    void toBlob_lengthIs4xFloatCount() {
        float[] v = {1.0f, 2.0f, 3.0f, 4.0f};
        byte[] blob = WikiVectorRepository.toBlob(v);
        assertEquals(v.length * 4, blob.length);
    }

    @Test
    void toBlob_fromBlob_roundtrip() {
        float[] original = {0.5f, -1.0f, 3.14f, 0.0f, 100.0f};
        byte[] blob = WikiVectorRepository.toBlob(original);
        float[] restored = WikiVectorRepository.fromBlob(blob);
        assertArrayEquals(original, restored, 1e-6f);
    }

    @Test
    void toBlob_isLittleEndian() {
        // 1.0f = 0x3F800000 → LITTLE_ENDIAN 직렬화: [0x00, 0x00, 0x80, 0x3F]
        float[] v = {1.0f};
        byte[] blob = WikiVectorRepository.toBlob(v);
        assertEquals(4, blob.length);
        assertEquals((byte) 0x00, blob[0]);
        assertEquals((byte) 0x00, blob[1]);
        assertEquals((byte) 0x80, blob[2]);
        assertEquals((byte) 0x3F, blob[3]);
    }

    @Test
    void toBlob_pythonStructCompatible() {
        // Python struct.pack('<4f', 1.0, 2.0, 3.0, 4.0) 결과와 동일한지 검증
        float[] v = {1.0f, 2.0f, 3.0f, 4.0f};
        byte[] blob = WikiVectorRepository.toBlob(v);
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.0f, buf.getFloat(), 1e-7f);
        assertEquals(2.0f, buf.getFloat(), 1e-7f);
        assertEquals(3.0f, buf.getFloat(), 1e-7f);
        assertEquals(4.0f, buf.getFloat(), 1e-7f);
    }

    @Test
    void fromBlob_emptyBytes_returnsEmptyArray() {
        float[] result = WikiVectorRepository.fromBlob(new byte[0]);
        assertEquals(0, result.length);
    }

    // ─────────────────────────────────────────────────────────────
    // DB 기본 동작 (vec0 없이 선형 탐색 폴백 모드)
    // ─────────────────────────────────────────────────────────────

    @Test
    void upsertAndGetChunkHashes(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            WikiChunker.Chunk chunk = makeChunk(0, "abc123def456abcd");
            repo.upsertChunk("wiki/entities/Test.md", chunk, unitVec(4));

            Map<Integer, String> hashes = repo.getChunkHashes("wiki/entities/Test.md");
            assertEquals(1, hashes.size());
            assertEquals("abc123def456abcd", hashes.get(0));
        }
    }

    @Test
    void upsertChunk_replaceOnSamePageAndChunkNo(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            WikiChunker.Chunk v1 = makeChunk(0, "hash1111aaaabbbb");
            WikiChunker.Chunk v2 = makeChunk(0, "hash2222ccccdddd");
            float[] emb = unitVec(4);

            repo.upsertChunk("wiki/Test.md", v1, emb);
            repo.upsertChunk("wiki/Test.md", v2, emb);

            Map<Integer, String> hashes = repo.getChunkHashes("wiki/Test.md");
            // 동일 (page_path, chunk_no) → 교체, 한 개만 남아야 한다
            assertEquals(1, hashes.size());
            assertEquals("hash2222ccccdddd", hashes.get(0));
        }
    }

    @Test
    void countChunks_reflectsInsertedCount(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            assertEquals(0L, repo.countChunks());

            for (int i = 0; i < 3; i++) {
                WikiChunker.Chunk c = new WikiChunker.Chunk(
                        i, "source", "", "내용 " + i, "hash" + String.format("%012d", i));
                repo.upsertChunk("wiki/page.md", c, unitVec(4));
            }
            assertEquals(3L, repo.countChunks());
        }
    }

    @Test
    void getIndexedPagePaths_returnsDistinctPaths(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            repo.upsertChunk("wiki/A.md", makeChunk(0, "aabb11223344aaaa"), emb);
            repo.upsertChunk("wiki/B.md", makeChunk(0, "ccdd55667788cccc"), emb);
            // 같은 페이지에 청크 추가 — 경로는 1개만 나와야 한다
            repo.upsertChunk("wiki/A.md", makeChunk(1, "eeff99001122eeff"), emb);

            Set<String> paths = repo.getIndexedPagePaths();
            assertEquals(2, paths.size());
            assertTrue(paths.contains("wiki/A.md"));
            assertTrue(paths.contains("wiki/B.md"));
        }
    }

    @Test
    void deleteChunksByPage_removesOnlyTargetPage(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            repo.upsertChunk("wiki/A.md", makeChunk(0, "aabb11223344aaaa"), emb);
            repo.upsertChunk("wiki/B.md", makeChunk(0, "ccdd55667788cccc"), emb);

            repo.deleteChunksByPage("wiki/A.md");

            Set<String> paths = repo.getIndexedPagePaths();
            assertEquals(1, paths.size());
            assertTrue(paths.contains("wiki/B.md"));
            assertFalse(paths.contains("wiki/A.md"));
        }
    }

    @Test
    void deleteChunksFrom_removesStaleTailOnly(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            for (int i = 0; i < 4; i++) {
                repo.upsertChunk("wiki/A.md",
                        makeChunk(i, "hash" + String.format("%012d", i)), emb);
            }

            repo.deleteChunksFrom("wiki/A.md", 2);

            Map<Integer, String> hashes = repo.getChunkHashes("wiki/A.md");
            assertEquals(Set.of(0, 1), hashes.keySet());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 선형 탐색 (vec0 없는 폴백 — cosineSimilarity 간접 검증)
    // ─────────────────────────────────────────────────────────────

    @Test
    void searchSimilar_returnsNearestFirst(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            // near: query [1,0,0,0]와 유사
            float[] near = normalize(new float[]{0.99f, 0.01f, 0.0f, 0.0f});
            // far: query와 직교
            float[] far  = normalize(new float[]{0.0f,  0.0f,  1.0f, 0.0f});

            repo.upsertChunk("wiki/Near.md",
                    new WikiChunker.Chunk(0, "entity", "", "near 페이지", "aabb11223344aaaa"), near);
            repo.upsertChunk("wiki/Far.md",
                    new WikiChunker.Chunk(0, "entity", "", "far 페이지",  "ccdd55667788cccc"), far);

            float[] query = {1.0f, 0.0f, 0.0f, 0.0f};
            List<WikiVectorRepository.SearchResult> results = repo.searchSimilar(query, 2);

            assertEquals(2, results.size());
            // near 페이지가 먼저 와야 한다 (코사인 거리 더 낮음)
            assertEquals("wiki/Near.md", results.get(0).pagePath());
            assertEquals("wiki/Far.md",  results.get(1).pagePath());
        }
    }

    @Test
    void searchSimilar_emptyDb_returnsEmptyList(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            List<WikiVectorRepository.SearchResult> results =
                    repo.searchSimilar(unitVec(4), 10);
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void searchSimilar_topK_limitsResults(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            for (int i = 0; i < 5; i++) {
                repo.upsertChunk("wiki/P" + i + ".md",
                        new WikiChunker.Chunk(0, "entity", "", "내용 " + i,
                                "hash" + String.format("%012d", i)), emb);
            }
            List<WikiVectorRepository.SearchResult> results =
                    repo.searchSimilar(unitVec(4), 3);
            assertEquals(3, results.size());
        }
    }

    @Test
    void searchResult_containsCorrectFields(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            repo.upsertChunk("wiki/A.md",
                    new WikiChunker.Chunk(0, "entity", "", "테스트 내용", "abcd1234efgh5678"), emb);

            List<WikiVectorRepository.SearchResult> results =
                    repo.searchSimilar(emb, 1);

            assertEquals(1, results.size());
            WikiVectorRepository.SearchResult r = results.get(0);
            assertEquals("wiki/A.md", r.pagePath());
            assertEquals(0, r.chunkNo());
            assertEquals("entity", r.type());
            assertEquals("테스트 내용", r.content());
            // 동일 벡터 → 코사인 거리 ≈ 0 (1 - similarity)
            assertTrue(r.distance() < 0.01f,
                    "동일 벡터 간 distance는 0에 가까워야 한다: " + r.distance());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────

    /** 첫 번째 차원만 1인 단위 벡터를 반환한다. */
    private static float[] unitVec(int dim) {
        float[] v = new float[dim];
        v[0] = 1.0f;
        return v;
    }

    /** L2 정규화된 벡터를 반환한다. */
    private static float[] normalize(float[] v) {
        float norm = 0;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = norm > 0 ? v[i] / norm : 0;
        return out;
    }

    /** 지정한 chunkNo와 contentHash를 가진 테스트용 Chunk를 생성한다. */
    private static WikiChunker.Chunk makeChunk(int chunkNo, String contentHash) {
        return new WikiChunker.Chunk(chunkNo, "entity", "", "테스트 내용 " + chunkNo, contentHash);
    }
}
