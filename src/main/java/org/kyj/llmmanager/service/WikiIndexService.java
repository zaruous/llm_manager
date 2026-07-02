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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * @param relinkedChunks 본문 동일로 임베딩을 재사용해 재연결한 청크 수
     * @param deletedPages   삭제된 페이지 수
     * @param errorPages     오류가 발생한 페이지 수
     */
    public record IndexResult(Path workspace, int indexedChunks, int skippedChunks,
                              int relinkedChunks, int deletedPages, int errorPages) {}

    /**
     * 현재 Wiki 파일과 SQLite 색인의 비교 상태.
     */
    public enum PageIndexState {
        CURRENT,
        STALE,
        NOT_INDEXED,
        EMPTY,
        ORPHANED
    }

    /**
     * 페이지 한 건의 색인 메타데이터.
     *
     * @param pagePath      워크스페이스 상대 경로
     * @param category      Wiki 페이지 카테고리
     * @param fileBytes     현재 파일 크기, 삭제된 파일은 0
     * @param expectedChunks 현재 전처리로 생성되는 청크 수
     * @param indexedChunks SQLite에 저장된 청크 수
     * @param state         현재 파일과 색인의 비교 상태
     */
    public record PageIndexMetadata(String pagePath, String category, long fileBytes,
                                    int expectedChunks, int indexedChunks,
                                    PageIndexState state) {}

    /**
     * 워크스페이스의 색인 상태 요약.
     *
     * @param workspace 조회한 워크스페이스
     * @param pages     파일별 색인 메타데이터
     */
    public record WorkspaceIndexMetadata(Path workspace, List<PageIndexMetadata> pages) {
        /** 지정 상태의 페이지 수를 반환한다. */
        public long count(PageIndexState state) {
            return pages.stream().filter(page -> page.state() == state).count();
        }
    }

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
            return new IndexResult(workspace, 0, 0, 0, 0, 0);
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

        int indexed = 0, skipped = 0, relinked = 0, deleted = 0, errors = 0;

        for (WikiMarkdownUtils.PageEntry page : pages) {
            String relPath = relativize(workspace, page.file());
            try {
                int[] counts = indexPage(relPath, page, repo, embeddingClient, progress);
                indexed  += counts[0];
                skipped  += counts[1];
                relinked += counts[2];
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

        log.info("wiki-index: 완료 — 새 청크={}, 건너뜀={}, 재연결={}, 삭제 페이지={}, 오류={}",
                indexed, skipped, relinked, deleted, errors);
        progress.accept(String.format(
                "[wiki-index] 완료: 청크 %d개 색인, %d개 건너뜀, %d개 재연결, %d개 페이지 삭제",
                indexed, skipped, relinked, deleted));

        return new IndexResult(workspace, indexed, skipped, relinked, deleted, errors);
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

    /**
     * 임베딩 서버 호출 없이 현재 Wiki 파일과 SQLite 색인 상태를 비교한다.
     * 현재 전처리 설정으로 다시 계산한 청크 해시가 모두 일치해야 CURRENT로 판정한다.
     *
     * @param workspace 위키 워크스페이스 루트
     * @return 파일별 색인 메타데이터
     * @throws SQLException SQLite 조회 실패 시
     */
    public WorkspaceIndexMetadata inspectWorkspace(Path workspace) throws SQLException {
        Path normalized = workspace.toAbsolutePath().normalize();
        WikiVectorRepository repo = getRepo(normalized);
        List<WikiMarkdownUtils.PageEntry> pages =
                WikiMarkdownUtils.collectPages(normalized.resolve("wiki"));
        List<PageIndexMetadata> metadata = new ArrayList<>();
        Set<String> currentPaths = new java.util.LinkedHashSet<>();

        for (WikiMarkdownUtils.PageEntry page : pages) {
            String relPath = relativize(normalized, page.file());
            currentPaths.add(relPath);
            List<WikiChunker.Chunk> chunks = WikiChunker.chunk(
                    page.file(), page.category(), relPath, chunkOptions());
            Map<Integer, String> expected = new LinkedHashMap<>();
            for (WikiChunker.Chunk chunk : chunks) {
                expected.put(chunk.chunkNo(), chunk.contentHash());
            }
            Map<Integer, String> indexed = repo.getChunkHashes(relPath);
            PageIndexState state;
            if (expected.isEmpty()) {
                state = PageIndexState.EMPTY;
            } else if (indexed.isEmpty()) {
                state = PageIndexState.NOT_INDEXED;
            } else if (expected.equals(indexed)) {
                state = PageIndexState.CURRENT;
            } else {
                state = PageIndexState.STALE;
            }
            metadata.add(new PageIndexMetadata(relPath, page.category(),
                    fileSize(page.file()), expected.size(), indexed.size(), state));
        }

        for (String indexedPath : repo.getIndexedPagePaths()) {
            if (!currentPaths.contains(indexedPath)) {
                metadata.add(new PageIndexMetadata(indexedPath, "deleted",
                        0, 0, repo.getChunkHashes(indexedPath).size(), PageIndexState.ORPHANED));
            }
        }
        return new WorkspaceIndexMetadata(normalized, List.copyOf(metadata));
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
     * 페이지 하나를 청킹한 뒤 재연결 계획에 따라 색인한다.
     *
     * 본문(body_hash)이 같은 청크는 헤더·위치가 바뀌어도 기존 임베딩을 재사용해
     * 재연결하고, 본문이 실제로 바뀐 청크만 임베딩 API를 호출한다.
     *
     * @return int[]{indexed, skipped, relinked}
     */
    private int[] indexPage(String relPath, WikiMarkdownUtils.PageEntry page,
                            WikiVectorRepository repo,
                            WikiEmbeddingClient client,
                            Consumer<String> progress) throws IOException, SQLException {

        WikiPreprocessor.PreparedDocument document = WikiPreprocessor.preprocess(
                page.file(), page.category(), relPath, chunkOptions());
        if (!document.warnings().isEmpty()) {
            String message = String.join(", ", document.warnings());
            log.debug("wiki-index: 전처리 보정 [{}] — {}", relPath, message);
            progress.accept("[wiki-index] 전처리 보정: " + relPath + " — " + message);
        }
        List<WikiChunker.Chunk> chunks = WikiChunker.chunk(document);
        if (chunks.isEmpty()) {
            repo.deleteChunksFrom(relPath, 0);
            return new int[]{0, 0, 0};
        }

        ReconcilePlan plan = planReconcile(repo.getChunkStates(relPath), chunks);

        // 순서 중요: 삭제로 자리를 비운 뒤 재연결(번호 이동)하고, 마지막에 신규 삽입
        for (int chunkNo : plan.deletes()) {
            repo.deleteChunk(relPath, chunkNo);
        }
        repo.relinkChunks(relPath, plan.relinks());

        if (!plan.toEmbed().isEmpty()) {
            // 배치 임베딩 — 본문이 실제로 바뀐 청크만 호출
            List<String> texts = plan.toEmbed().stream().map(WikiChunker.Chunk::content).toList();
            List<float[]> embeddings = client.embedBatch(texts);
            for (int i = 0; i < plan.toEmbed().size(); i++) {
                repo.upsertChunk(relPath, plan.toEmbed().get(i), embeddings.get(i));
            }
        }

        if (!plan.toEmbed().isEmpty() || !plan.relinks().isEmpty()) {
            progress.accept("[wiki-index] " + relPath + " — " + plan.toEmbed().size()
                    + "청크 색인, " + plan.relinks().size() + "청크 재연결");
        }
        return new int[]{plan.toEmbed().size(), plan.skipped(), plan.relinks().size()};
    }

    /**
     * 페이지 재색인 실행 계획.
     *
     * @param skipped 완전 일치(content_hash 동일)로 아무 작업도 필요 없는 청크 수
     * @param relinks 본문 동일 — 기존 임베딩을 유지한 채 내용·위치만 갱신할 목록
     * @param toEmbed 본문 변경 — 임베딩을 새로 계산해야 하는 청크 목록
     * @param deletes 새 청크 어디에도 대응되지 않아 삭제할 기존 chunk_no 목록
     */
    record ReconcilePlan(int skipped, List<WikiVectorRepository.Relink> relinks,
                         List<WikiChunker.Chunk> toEmbed, List<Integer> deletes) {}

    /**
     * 기존 색인 상태와 새 청크 목록을 비교해 재색인 계획을 세운다.
     *
     * <p>매칭 우선순위:
     * <ol>
     *   <li>같은 위치 + content_hash 일치 → 스킵</li>
     *   <li>같은 위치 + body_hash 일치 → 제자리 재연결 (헤더만 변경)</li>
     *   <li>다른 위치의 미사용 행과 body_hash 일치 → 이동 재연결 (문단 삽입/재배열)</li>
     *   <li>매칭 없음 → 재임베딩</li>
     * </ol>
     * 어느 새 청크와도 매칭되지 않은 기존 행은 삭제 대상이 된다.
     * body_hash가 null인 레거시 행은 재연결 후보에서 제외된다.
     *
     * @param existing DB의 chunkNo → ChunkState
     * @param chunks   현재 전처리 결과 청크 목록
     * @return 재색인 실행 계획
     */
    static ReconcilePlan planReconcile(Map<Integer, WikiVectorRepository.ChunkState> existing,
                                       List<WikiChunker.Chunk> chunks) {
        int skipped = 0;
        List<WikiVectorRepository.Relink> relinks = new ArrayList<>();
        List<WikiChunker.Chunk> toEmbed = new ArrayList<>();
        Set<Integer> consumed = new java.util.HashSet<>();
        List<WikiChunker.Chunk> unresolved = new ArrayList<>();

        // 1차: 같은 위치 매칭
        for (WikiChunker.Chunk chunk : chunks) {
            WikiVectorRepository.ChunkState state = existing.get(chunk.chunkNo());
            if (state == null) {
                unresolved.add(chunk);
                continue;
            }
            if (chunk.contentHash().equals(state.contentHash())) {
                consumed.add(chunk.chunkNo());
                skipped++;
            } else if (state.bodyHash() != null
                    && state.bodyHash().equals(WikiChunker.bodyHash(chunk.content()))) {
                consumed.add(chunk.chunkNo());
                relinks.add(new WikiVectorRepository.Relink(chunk.chunkNo(), chunk));
            } else {
                unresolved.add(chunk);
            }
        }

        // 2차: 이동 매칭 — 남은 기존 행을 body_hash로 색인해 위치가 바뀐 본문을 찾는다
        Map<String, java.util.ArrayDeque<Integer>> byBodyHash = new java.util.HashMap<>();
        for (Map.Entry<Integer, WikiVectorRepository.ChunkState> e : existing.entrySet()) {
            if (consumed.contains(e.getKey()) || e.getValue().bodyHash() == null) continue;
            byBodyHash.computeIfAbsent(e.getValue().bodyHash(),
                    k -> new java.util.ArrayDeque<>()).add(e.getKey());
        }
        for (WikiChunker.Chunk chunk : unresolved) {
            java.util.ArrayDeque<Integer> candidates =
                    byBodyHash.get(WikiChunker.bodyHash(chunk.content()));
            if (candidates != null && !candidates.isEmpty()) {
                int oldNo = candidates.poll();
                consumed.add(oldNo);
                relinks.add(new WikiVectorRepository.Relink(oldNo, chunk));
            } else {
                toEmbed.add(chunk);
            }
        }

        // 어느 새 청크에도 대응되지 않은 기존 행은 삭제
        List<Integer> deletes = new ArrayList<>();
        for (Integer oldNo : existing.keySet()) {
            if (!consumed.contains(oldNo)) deletes.add(oldNo);
        }

        return new ReconcilePlan(skipped, List.copyOf(relinks),
                List.copyOf(toEmbed), List.copyOf(deletes));
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

    private WikiPreprocessor.Options chunkOptions() {
        AppSettings settings = settingsRepository.get();
        return new WikiPreprocessor.Options(
                intSetting(settings, "wiki.targetChunkChars", 1500),
                intSetting(settings, "wiki.maxChunkChars", 2500),
                intSetting(settings, "wiki.chunkOverlapChars", 200));
    }

    private static int intSetting(AppSettings settings, String key, int fallback) {
        String value = settings.getPluginSetting("wiki-agent", key, String.valueOf(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long fileSize(Path file) {
        try {
            return java.nio.file.Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
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
