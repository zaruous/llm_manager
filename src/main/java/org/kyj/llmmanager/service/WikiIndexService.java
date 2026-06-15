/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 위키 벡터 색인 오케스트레이터.
 *
 * 워크스페이스별 {@link WikiVectorRepository}를 관리하며 청킹·임베딩·색인 파이프라인을 실행한다.
 * AppContext가 단일 인스턴스를 소유하며 앱 종료 시 {@link #close()}를 호출해야 한다.
 */
public class WikiIndexService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WikiIndexService.class);

    /** 워크스페이스 경로 → Repository 캐시. 워크스페이스마다 DB 파일이 분리된다. */
    private final ConcurrentHashMap<Path, WikiVectorRepository> repos = new ConcurrentHashMap<>();

    private final AppSettingsRepository settingsRepository;

    /**
     * @param settingsRepository AppSettings 제공자 (embeddingUrl·vec0Path 읽기용)
     */
    public WikiIndexService(AppSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    /**
     * 색인 결과 요약.
     *
     * @param workspace      색인한 워크스페이스 경로
     * @param indexedChunks  새로 색인된 청크 수
     * @param skippedChunks  변경 없어 건너뛴 청크 수
     * @param deletedPages   삭제된 페이지 수
     * @param errorPages     오류가 발생한 페이지 수
     */
    public record IndexResult(Path workspace, int indexedChunks, int skippedChunks,
                              int deletedPages, int errorPages) {}

    // ─────────────────────────────────────────────────────────────
    // 공개 API
    // ─────────────────────────────────────────────────────────────

    /**
     * 워크스페이스 전체 위키를 (증분) 색인한다.
     * 변경되지 않은 청크(content_hash 일치)는 건너뛴다.
     * 임베딩 서버가 응답하지 않으면 색인을 건너뛰고 결과에 오류 수를 반영한다.
     *
     * @param workspace  위키 워크스페이스 루트 (wiki/index.md가 있는 디렉토리)
     * @param onProgress 진행 메시지 콜백 (UI 표시용, null 허용)
     * @return 색인 결과 요약
     */
    public IndexResult indexWorkspace(Path workspace, Consumer<String> onProgress) {
        log.info("wiki-index: 색인 시작 — {}", workspace);
        Consumer<String> progress = onProgress != null ? onProgress : ignored -> {};

        WikiEmbeddingClient embeddingClient = createEmbeddingClient();
        if (!embeddingClient.isAvailable()) {
            progress.accept("[wiki-index] 임베딩 서버에 연결할 수 없습니다 (" +
                    embeddingClient(embeddingUrl()) + "). bgem3-embedding 서비스를 먼저 시작해 주세요.");
            log.warn("wiki-index: 임베딩 서버 미기동 — 색인 건너뜀");
            return new IndexResult(workspace, 0, 0, 0, 0);
        }

        WikiVectorRepository repo = getRepo(workspace);
        Path wikiDir = workspace.resolve("wiki");

        List<WikiMarkdownUtils.PageEntry> pages = WikiMarkdownUtils.collectPages(wikiDir);
        progress.accept("[wiki-index] 페이지 " + pages.size() + "개 발견");

        // DB에 이미 색인된 페이지 경로 목록 (고아 페이지 삭제용)
        java.util.Set<String> currentPagePaths = new java.util.HashSet<>();
        for (WikiMarkdownUtils.PageEntry page : pages) {
            currentPagePaths.add(relativize(workspace, page.file()));
        }

        int indexed = 0, skipped = 0, deleted = 0, errors = 0;

        for (WikiMarkdownUtils.PageEntry page : pages) {
            String relPath = relativize(workspace, page.file());
            try {
                int[] counts = indexPage(relPath, page, repo, embeddingClient, progress);
                indexed += counts[0];
                skipped += counts[1];
            } catch (Exception e) {
                log.warn("wiki-index: 페이지 색인 실패 [{}]: {}", relPath, e.getMessage());
                progress.accept("[wiki-index] 오류: " + relPath + " — " + e.getMessage());
                errors++;
            }
        }

        // 현재 위키에 없는 페이지 색인 삭제
        try {
            deleted = cleanupDeletedPages(repo, currentPagePaths, progress);
        } catch (Exception e) {
            log.warn("wiki-index: 고아 페이지 정리 실패: {}", e.getMessage());
        }

        log.info("wiki-index: 완료 — 새 청크={}, 건너뜀={}, 삭제 페이지={}, 오류={}",
                indexed, skipped, deleted, errors);
        progress.accept(String.format("[wiki-index] 완료: 청크 %d개 색인, %d개 건너뜀, %d개 페이지 삭제",
                indexed, skipped, deleted));

        return new IndexResult(workspace, indexed, skipped, deleted, errors);
    }

    /**
     * 워크스페이스에서 쿼리와 의미적으로 가장 유사한 청크를 검색한다.
     * 임베딩 서버 또는 vec0가 없으면 빈 목록을 반환한다.
     *
     * @param workspace 위키 워크스페이스 루트
     * @param query     자연어 검색어
     * @param topK      반환할 최대 결과 수
     * @return 유사도 순 검색 결과
     */
    public List<WikiVectorRepository.SearchResult> search(Path workspace, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        try {
            WikiEmbeddingClient client = createEmbeddingClient();
            float[] queryEmb = client.embed(query);
            return getRepo(workspace).searchSimilar(queryEmb, topK);
        } catch (IOException | SQLException e) {
            log.warn("wiki-index: 검색 실패 [{}]: {}", query, e.getMessage());
            return List.of();
        }
    }

    /** 워크스페이스의 현재 색인 청크 수를 반환한다. 0이면 미색인 상태. */
    public long countChunks(Path workspace) {
        try {
            return getRepo(workspace).countChunks();
        } catch (SQLException e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        repos.values().forEach(WikiVectorRepository::close);
        repos.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────

    /**
     * 페이지 하나를 청킹·임베딩·upsert한다.
     *
     * @return int[]{indexed, skipped}
     */
    private int[] indexPage(String relPath, WikiMarkdownUtils.PageEntry page,
                            WikiVectorRepository repo,
                            WikiEmbeddingClient client,
                            Consumer<String> progress) throws IOException, SQLException {

        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(page.file(), page.category());
        if (chunks.isEmpty()) return new int[]{0, 0};

        Map<Integer, String> existingHashes = repo.getChunkHashes(relPath);

        // 변경된 청크만 선별
        List<WikiChunker.Chunk> toEmbed = new ArrayList<>();
        int skipped = 0;
        for (WikiChunker.Chunk chunk : chunks) {
            String existing = existingHashes.get(chunk.chunkNo());
            if (chunk.contentHash().equals(existing)) {
                skipped++;
            } else {
                toEmbed.add(chunk);
            }
        }

        if (toEmbed.isEmpty()) return new int[]{0, skipped};

        // 배치 임베딩
        List<String> texts = toEmbed.stream().map(WikiChunker.Chunk::content).toList();
        List<float[]> embeddings = client.embedBatch(texts);

        for (int i = 0; i < toEmbed.size(); i++) {
            repo.upsertChunk(relPath, toEmbed.get(i), embeddings.get(i));
        }

        progress.accept("[wiki-index] " + relPath + " — " + toEmbed.size() + "청크 색인");
        return new int[]{toEmbed.size(), skipped};
    }

    /**
     * DB에는 있으나 현재 위키에 없는 페이지(고아)의 색인을 삭제한다.
     *
     * @return 삭제된 페이지 수
     */
    private int cleanupDeletedPages(WikiVectorRepository repo,
                                    java.util.Set<String> currentPaths,
                                    Consumer<String> progress) throws SQLException {
        java.util.Set<String> indexed = repo.getIndexedPagePaths();
        int deleted = 0;
        for (String path : indexed) {
            if (!currentPaths.contains(path)) {
                repo.deleteChunksByPage(path);
                progress.accept("[wiki-index] 삭제된 페이지 색인 제거: " + path);
                deleted++;
            }
        }
        return deleted;
    }

    private WikiVectorRepository getRepo(Path workspace) {
        return repos.computeIfAbsent(
                workspace.toAbsolutePath().normalize(),
                w -> new WikiVectorRepository(w, vec0Path()));
    }

    private WikiEmbeddingClient createEmbeddingClient() {
        return new WikiEmbeddingClient(embeddingUrl());
    }

    private String embeddingUrl() {
        AppSettings settings = settingsRepository.get();
        return settings.getPluginSetting("wiki-agent", "wiki.embeddingUrl",
                WikiEmbeddingClient.DEFAULT_BASE_URL);
    }

    private String vec0Path() {
        AppSettings settings = settingsRepository.get();
        return settings.getPluginSetting("wiki-agent", "wiki.vec0Path", "");
    }

    private static String relativize(Path workspace, Path file) {
        return workspace.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    /** 로그 메시지용 URL 단축 표시. */
    private static String embeddingClient(String url) {
        return url.isEmpty() ? WikiEmbeddingClient.DEFAULT_BASE_URL : url;
    }
}
