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
    // body_hash — 상태 조회·재연결·마이그레이션
    // ─────────────────────────────────────────────────────────────

    @Test
    void getChunkStates_returnsContentAndBodyHash(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            WikiChunker.Chunk chunk = new WikiChunker.Chunk(
                    0, "entity", "", "제목: A\n\n본문입니다.", "abc123def456abcd");
            repo.upsertChunk("wiki/A.md", chunk, unitVec(4));

            Map<Integer, WikiVectorRepository.ChunkState> states =
                    repo.getChunkStates("wiki/A.md");
            assertEquals(1, states.size());
            assertEquals("abc123def456abcd", states.get(0).contentHash());
            assertEquals(WikiChunker.bodyHash(chunk.content()), states.get(0).bodyHash());
        }
    }

    @Test
    void upsertChunk_replacePreservesEmbeddingLink(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = normalize(new float[]{1f, 0f, 0f, 0f});
            repo.upsertChunk("wiki/A.md",
                    new WikiChunker.Chunk(0, "entity", "", "v1 내용", "hash1111aaaabbbb"), emb);
            repo.upsertChunk("wiki/A.md",
                    new WikiChunker.Chunk(0, "entity", "", "v2 내용", "hash2222ccccdddd"), emb);

            // 교체 후에도 임베딩-청크 조인이 정확히 1건이어야 한다 (고아 임베딩 없음)
            List<WikiVectorRepository.SearchResult> results = repo.searchSimilar(emb, 10);
            assertEquals(1, results.size());
            assertEquals("v2 내용", results.get(0).content());
        }
    }

    @Test
    void relinkChunks_movesRowsKeepingEmbeddings(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            // 본문 A(0번)·B(1번)를 서로 다른 벡터로 색인
            float[] embA = normalize(new float[]{1f, 0f, 0f, 0f});
            float[] embB = normalize(new float[]{0f, 1f, 0f, 0f});
            repo.upsertChunk("wiki/P.md",
                    new WikiChunker.Chunk(0, "entity", "", "헤더\n\n본문 A", "hashaaaa00000000"), embA);
            repo.upsertChunk("wiki/P.md",
                    new WikiChunker.Chunk(1, "entity", "", "헤더\n\n본문 B", "hashbbbb00000000"), embB);

            // 문단 삽입 시나리오: A→1번, B→2번으로 이동 (0번은 신규용으로 비움)
            repo.relinkChunks("wiki/P.md", List.of(
                    new WikiVectorRepository.Relink(0,
                            new WikiChunker.Chunk(1, "entity", "", "새헤더\n\n본문 A", "hashaaaa11111111")),
                    new WikiVectorRepository.Relink(1,
                            new WikiChunker.Chunk(2, "entity", "", "새헤더\n\n본문 B", "hashbbbb11111111"))));

            Map<Integer, WikiVectorRepository.ChunkState> states = repo.getChunkStates("wiki/P.md");
            assertEquals(Set.of(1, 2), states.keySet());
            assertEquals("hashaaaa11111111", states.get(1).contentHash());

            // A 벡터로 검색하면 이동한 1번 청크가 나와야 한다 — 임베딩 링크 유지 증명
            List<WikiVectorRepository.SearchResult> results = repo.searchSimilar(embA, 1);
            assertEquals(1, results.get(0).chunkNo());
            assertEquals("새헤더\n\n본문 A", results.get(0).content());
        }
    }

    @Test
    void relinkChunks_swapPositions_noUniqueConflict(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            repo.upsertChunk("wiki/P.md", makeChunk(0, "hash0000aaaaaaaa"), emb);
            repo.upsertChunk("wiki/P.md", makeChunk(1, "hash1111bbbbbbbb"), emb);

            // 0↔1 맞교환 — 임시 음수 번호 2-pass가 없으면 UNIQUE 제약 위반
            repo.relinkChunks("wiki/P.md", List.of(
                    new WikiVectorRepository.Relink(0, makeChunk(1, "hash0000aaaaaaaa")),
                    new WikiVectorRepository.Relink(1, makeChunk(0, "hash1111bbbbbbbb"))));

            Map<Integer, WikiVectorRepository.ChunkState> states = repo.getChunkStates("wiki/P.md");
            assertEquals("hash0000aaaaaaaa", states.get(1).contentHash());
            assertEquals("hash1111bbbbbbbb", states.get(0).contentHash());
        }
    }

    @Test
    void deleteChunk_removesSingleChunkOnly(@TempDir Path workspace) throws SQLException {
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            float[] emb = unitVec(4);
            repo.upsertChunk("wiki/P.md", makeChunk(0, "hash0000aaaaaaaa"), emb);
            repo.upsertChunk("wiki/P.md", makeChunk(1, "hash1111bbbbbbbb"), emb);

            repo.deleteChunk("wiki/P.md", 0);

            assertEquals(Set.of(1), repo.getChunkStates("wiki/P.md").keySet());
            // 임베딩도 함께 삭제되어 검색 결과가 1건이어야 한다
            assertEquals(1, repo.searchSimilar(emb, 10).size());
        }
    }

    @Test
    void migration_backfillsBodyHashOnLegacyDb(@TempDir Path workspace) throws Exception {
        // body_hash 컬럼이 없는 구버전 스키마 DB를 직접 만든다
        Path dbFile = workspace.resolve(".llm-manager").resolve("wiki-vector.sqlite");
        java.nio.file.Files.createDirectories(dbFile.getParent());
        String legacyContent = "제목: A\n\n레거시 본문";
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.toAbsolutePath());
             java.sql.Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE chunks (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        page_path    TEXT NOT NULL,
                        chunk_no     INTEGER NOT NULL,
                        type         TEXT,
                        tags         TEXT,
                        content      TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        UNIQUE(page_path, chunk_no)
                    )
                    """);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO chunks(page_path, chunk_no, type, tags, content, content_hash) " +
                    "VALUES ('wiki/L.md', 0, 'entity', '', ?, 'legacyhash000000')")) {
                ps.setString(1, legacyContent);
                ps.executeUpdate();
            }
        }

        // 리포지토리를 열면 ALTER + 백필 마이그레이션이 실행된다
        try (WikiVectorRepository repo = new WikiVectorRepository(workspace, "")) {
            Map<Integer, WikiVectorRepository.ChunkState> states = repo.getChunkStates("wiki/L.md");
            assertEquals(WikiChunker.bodyHash(legacyContent), states.get(0).bodyHash());
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
