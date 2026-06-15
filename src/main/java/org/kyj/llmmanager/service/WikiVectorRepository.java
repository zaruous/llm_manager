/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 위키 페이지 청크의 임베딩 벡터를 SQLite + sqlite-vec(vec0 가상 테이블)로 관리한다.
 *
 * Connection pool size를 1로 고정해 vec0 확장 로딩을 한 번만 수행한다.
 * 워크스페이스의 {@code .llm-manager/wiki-vector.sqlite}에 DB 파일을 생성한다.
 */
public class WikiVectorRepository implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WikiVectorRepository.class);

    private static final String DB_FILE_NAME = "wiki-vector.sqlite";

    /** vec0 확장 로딩 완료 플래그. pool size=1이므로 한 번만 처리하면 된다. */
    private final AtomicBoolean vecLoaded = new AtomicBoolean(false);
    /** ensureSchema() 중복 실행 방지 플래그. */
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    private final HikariDataSource dataSource;
    private final String vec0Path;

    /**
     * @param workspace 위키 워크스페이스 루트 경로
     * @param vec0Path  vec0.dll / vec0.so 절대 경로
     */
    public WikiVectorRepository(Path workspace, String vec0Path) {
        this.vec0Path = vec0Path;
        Path dbFile = workspace.resolve(".llm-manager").resolve(DB_FILE_NAME)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("벡터 DB 디렉토리 생성 실패: " + dbFile.getParent(), e);
        }
        this.dataSource = createDataSource(dbFile);
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────────

    /**
     * 청크를 DB에 upsert한다. 동일 (page_path, chunk_no)이면 교체한다.
     *
     * @param pagePath    워크스페이스 상대 경로 (예: wiki/entities/JohnDoe.md)
     * @param chunk       청크 메타·내용
     * @param embedding   BGE-M3 1024차원 float 배열
     * @throws SQLException DB 오류
     */
    public synchronized void upsertChunk(String pagePath, WikiChunker.Chunk chunk,
                                          float[] embedding) throws SQLException {
        ensureSchema();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT OR REPLACE INTO chunks
                         (page_path, chunk_no, type, tags, content, content_hash)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, pagePath);
            ps.setInt(2, chunk.chunkNo());
            ps.setString(3, chunk.type());
            ps.setString(4, chunk.tags());
            ps.setString(5, chunk.content());
            ps.setString(6, chunk.contentHash());
            ps.executeUpdate();
        }
        // chunk id를 조회해 vec0에 upsert
        try (Connection conn = connect()) {
            long chunkId;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id FROM chunks WHERE page_path = ? AND chunk_no = ?
                    """)) {
                ps.setString(1, pagePath);
                ps.setInt(2, chunk.chunkNo());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;
                    chunkId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT OR REPLACE INTO chunk_embeddings(rowid, embedding) VALUES (?, ?)
                    """)) {
                ps.setLong(1, chunkId);
                ps.setBytes(2, toBlob(embedding));
                ps.executeUpdate();
            }
        }
    }

    /**
     * 쿼리 임베딩과 가장 유사한 청크를 KNN 검색으로 반환한다.
     * vec0 확장이 없으면 코사인 유사도 선형 탐색으로 폴백한다.
     *
     * @param queryEmbedding 쿼리 벡터
     * @param topK           반환할 최대 건수
     * @return 유사도 순 검색 결과 목록
     */
    public List<SearchResult> searchSimilar(float[] queryEmbedding, int topK) throws SQLException {
        ensureSchema();
        if (vecLoaded.get()) {
            return searchVec0(queryEmbedding, topK);
        }
        return searchLinear(queryEmbedding, topK);
    }

    /**
     * 특정 페이지의 모든 청크 content_hash를 반환한다. 증분 색인 비교에 사용.
     *
     * @param pagePath 워크스페이스 상대 경로
     * @return chunkNo → contentHash 맵 (순서 보장)
     */
    public java.util.Map<Integer, String> getChunkHashes(String pagePath) throws SQLException {
        ensureSchema();
        java.util.Map<Integer, String> result = new java.util.LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT chunk_no, content_hash FROM chunks WHERE page_path = ? ORDER BY chunk_no
                     """)) {
            ps.setString(1, pagePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getInt(1), rs.getString(2));
            }
        }
        return result;
    }

    /**
     * 특정 페이지의 모든 청크와 임베딩을 삭제한다.
     *
     * @param pagePath 워크스페이스 상대 경로
     */
    public synchronized void deleteChunksByPage(String pagePath) throws SQLException {
        ensureSchema();
        try (Connection conn = connect()) {
            // vec0 삭제: rowid가 chunks.id와 1:1이므로 먼저 id 목록 조회 후 삭제
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM chunks WHERE page_path = ?")) {
                ps.setString(1, pagePath);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> ids = new ArrayList<>();
                    while (rs.next()) ids.add(rs.getLong(1));
                    for (long id : ids) {
                        try (PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM chunk_embeddings WHERE rowid = ?")) {
                            del.setLong(1, id);
                            del.executeUpdate();
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM chunks WHERE page_path = ?")) {
                ps.setString(1, pagePath);
                ps.executeUpdate();
            }
        }
    }

    /**
     * 색인된 모든 페이지 경로를 반환한다. 고아 페이지 정리에 사용.
     *
     * @return 현재 DB에 청크가 있는 page_path 집합
     */
    public java.util.Set<String> getIndexedPagePaths() throws SQLException {
        ensureSchema();
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT page_path FROM chunks")) {
            while (rs.next()) paths.add(rs.getString(1));
        }
        return paths;
    }

    /** 색인된 전체 청크 수를 반환한다. */
    public long countChunks() throws SQLException {
        ensureSchema();
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM chunks")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────

    /**
     * 스키마를 초기화한다. schemaReady 플래그로 인스턴스 생존 기간 동안 한 번만 실행된다.
     * vec0 확장 로딩을 여기서 먼저 시도한다.
     */
    private synchronized void ensureSchema() throws SQLException {
        if (schemaReady.get()) return;
        try (Connection conn = connect()) {
            tryLoadVec0(conn);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS chunks (
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
                st.executeUpdate("""
                        CREATE INDEX IF NOT EXISTS idx_chunks_page_path
                        ON chunks(page_path)
                        """);
                if (vecLoaded.get()) {
                    // vec0 가상 테이블: 1024차원 BGE-M3
                    st.executeUpdate("""
                            CREATE VIRTUAL TABLE IF NOT EXISTS chunk_embeddings
                            USING vec0(embedding float[1024])
                            """);
                } else {
                    // vec0 없는 폴백: BLOB 컬럼 일반 테이블
                    st.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS chunk_embeddings (
                                rowid     INTEGER PRIMARY KEY,
                                embedding BLOB NOT NULL
                            )
                            """);
                }
            }
        }
        schemaReady.set(true);
    }

    /**
     * vec0 SQLite 확장을 로드한다. 로딩 실패 시 경고만 남기고 선형 탐색 폴백으로 전환.
     *
     * extension loading은 createDataSource()에서 SQLiteConfig 수준으로 허용되어 있으므로
     * 여기서는 load_extension SQL만 실행한다.
     */
    private void tryLoadVec0(Connection conn) {
        if (vec0Path == null || vec0Path.isBlank()) {
            log.info("wiki-vector: vec0 경로 미설정 → 선형 탐색 폴백");
            return;
        }
        try (Statement st = conn.createStatement()) {
            // Windows 경로 역슬래시를 슬래시로 변환 (load_extension 요구사항)
            String path = vec0Path.replace('\\', '/');
            st.execute("SELECT load_extension('" + path + "')");
            vecLoaded.set(true);
            log.info("wiki-vector: sqlite-vec 로드 성공 ({})", vec0Path);
        } catch (Exception e) {
            log.warn("wiki-vector: sqlite-vec 로드 실패 → 선형 탐색 폴백. 원인: {}", e.getMessage());
        }
    }

    /** vec0 KNN 쿼리로 유사 청크를 검색한다. */
    private List<SearchResult> searchVec0(float[] queryEmbedding, int topK) throws SQLException {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT c.page_path, c.chunk_no, c.type, c.content, ce.distance
                     FROM chunk_embeddings ce
                     JOIN chunks c ON c.id = ce.rowid
                     WHERE ce.embedding MATCH ?
                       AND k = ?
                     ORDER BY ce.distance
                     """)) {
            ps.setBytes(1, toBlob(queryEmbedding));
            ps.setInt(2, topK);
            return collectResults(ps.executeQuery());
        }
    }

    /**
     * vec0 없이 전체 청크를 메모리에 로드해 코사인 유사도로 top-K를 찾는다.
     * 수천 청크 규모에서 허용 가능한 수준(~50ms)이다.
     */
    private List<SearchResult> searchLinear(float[] queryEmbedding, int topK) throws SQLException {
        record Candidate(String pagePath, int chunkNo, String type,
                         String content, float score) {}
        List<Candidate> candidates = new ArrayList<>();

        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT c.page_path, c.chunk_no, c.type, c.content, ce.embedding
                     FROM chunk_embeddings ce
                     JOIN chunks c ON c.id = ce.rowid
                     """)) {
            while (rs.next()) {
                byte[] blob = rs.getBytes("embedding");
                float[] emb = fromBlob(blob);
                float score = cosineSimilarity(queryEmbedding, emb);
                candidates.add(new Candidate(
                        rs.getString("page_path"),
                        rs.getInt("chunk_no"),
                        rs.getString("type"),
                        rs.getString("content"),
                        score));
            }
        }

        return candidates.stream()
                .sorted((a, b) -> Float.compare(b.score(), a.score()))
                .limit(topK)
                .map(c -> new SearchResult(c.pagePath(), c.chunkNo(), c.type(), c.content(),
                        1.0f - c.score()))
                .toList();
    }

    private List<SearchResult> collectResults(ResultSet rs) throws SQLException {
        List<SearchResult> list = new ArrayList<>();
        while (rs.next()) {
            list.add(new SearchResult(
                    rs.getString("page_path"),
                    rs.getInt("chunk_no"),
                    rs.getString("type"),
                    rs.getString("content"),
                    rs.getFloat("distance")));
        }
        return list;
    }

    /** float 배열을 LITTLE_ENDIAN BLOB으로 직렬화한다. Python struct.pack('<Nf') 호환. */
    static byte[] toBlob(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) buf.putFloat(f);
        return buf.array();
    }

    /** LITTLE_ENDIAN BLOB을 float 배열로 역직렬화한다. */
    static float[] fromBlob(byte[] blob) {
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[blob.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = buf.getFloat();
        return result;
    }

    /** 두 벡터의 코사인 유사도를 계산한다. [-1, 1] 범위. */
    private static float cosineSimilarity(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0 ? 0 : dot / denom;
    }

    private Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * HikariCP DataSource를 생성한다.
     * vec0 경로가 지정된 경우 SQLiteDataSource + SQLiteConfig를 통해
     * Connection 레벨에서 extension loading을 허용한다.
     * pool size=1 고정: 단일 Connection에만 vec0 로드가 유효하기 때문.
     */
    private HikariDataSource createDataSource(Path dbFile) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("wiki-vector");
        // vec0는 Connection당 load_extension이 필요하므로 pool size=1로 고정
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(-1);

        if (vec0Path != null && !vec0Path.isBlank()) {
            // SQLiteDataSource를 통해 extension loading을 Connection 레벨에서 활성화
            org.sqlite.SQLiteDataSource sqDs = new org.sqlite.SQLiteDataSource();
            sqDs.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
            org.sqlite.SQLiteConfig sqConf = new org.sqlite.SQLiteConfig();
            sqConf.enableLoadExtension(true);
            sqDs.setConfig(sqConf);
            config.setDataSource(sqDs);
        } else {
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
        }

        return new HikariDataSource(config);
    }

    // ─────────────────────────────────────────────────────────────
    // 공개 레코드
    // ─────────────────────────────────────────────────────────────

    /**
     * 유사도 검색 결과 한 항목.
     *
     * @param pagePath 위키 페이지 경로 (워크스페이스 상대)
     * @param chunkNo  페이지 내 청크 번호
     * @param type     페이지 타입 (entity, source 등)
     * @param content  청크 텍스트
     * @param distance 거리 값 (낮을수록 유사, vec0 HNSW 또는 1-cosine)
     */
    public record SearchResult(String pagePath, int chunkNo, String type,
                               String content, float distance) {}
}
