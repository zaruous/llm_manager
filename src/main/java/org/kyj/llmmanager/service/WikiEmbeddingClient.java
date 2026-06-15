/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BGE-M3 임베딩 서버(bgem3-embedding service)의 HTTP API를 호출하는 클라이언트.
 *
 * OpenAI 호환 POST /v1/embeddings 엔드포인트를 사용한다.
 * 서버가 응답하지 않으면 IOException을 발생시키며, 호출자가 폴백 처리를 결정한다.
 */
public class WikiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(WikiEmbeddingClient.class);

    /** 배치당 최대 텍스트 수. bgem3의 기본 batch-size에 맞춤. */
    private static final int MAX_BATCH = 32;
    /** BGE-M3 임베딩 차원 수. */
    public static final int DIMENSION = 1024;
    /** 기본 임베딩 서버 주소. AppSettings으로 덮어쓸 수 있다. */
    public static final String DEFAULT_BASE_URL = "http://localhost:18080";

    private static final String EMBED_PATH = "/v1/embeddings";
    private static final String MODEL = "BAAI/bge-m3";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * @param baseUrl 임베딩 서버 베이스 URL (예: "http://localhost:18080")
     */
    public WikiEmbeddingClient(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.stripTrailing() : DEFAULT_BASE_URL;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * 단일 텍스트를 임베딩 벡터로 변환한다.
     *
     * @param text 임베딩할 텍스트
     * @return 1024차원 float 배열
     * @throws IOException 서버 오류 또는 연결 실패
     */
    public float[] embed(String text) throws IOException {
        List<float[]> result = embedBatch(List.of(text));
        return result.get(0);
    }

    /**
     * 텍스트 목록을 배치 단위로 나눠 임베딩 벡터 목록으로 변환한다.
     * 입력 순서가 출력 순서와 일치한다.
     *
     * @param texts 임베딩할 텍스트 목록 (최대 MAX_BATCH 단위로 자동 분할)
     * @return 각 텍스트에 대응하는 1024차원 float 배열 목록
     * @throws IOException 서버 오류 또는 연결 실패
     */
    public List<float[]> embedBatch(List<String> texts) throws IOException {
        List<float[]> allResults = new ArrayList<>(texts.size());
        // MAX_BATCH 단위로 분할 호출
        for (int i = 0; i < texts.size(); i += MAX_BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH, texts.size()));
            allResults.addAll(callApi(batch));
        }
        return allResults;
    }

    /**
     * 서버에 HTTP 요청을 보내 임베딩 벡터를 수신한다.
     *
     * @param texts 최대 MAX_BATCH개 텍스트
     * @return float 배열 목록 (texts 순서와 동일)
     */
    private List<float[]> callApi(List<String> texts) throws IOException {
        String body = mapper.writeValueAsString(Map.of("input", texts, "model", MODEL));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + EMBED_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("임베딩 요청이 인터럽트되었습니다.", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("임베딩 서버 응답 오류: HTTP " + response.statusCode()
                    + " — " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode data = root.path("data");
        if (data.isMissingNode() || !data.isArray()) {
            throw new IOException("임베딩 서버 응답 형식 오류: 'data' 배열이 없습니다.");
        }

        // index 기준 정렬 보장
        int size = data.size();
        float[][] ordered = new float[size][];
        for (JsonNode item : data) {
            int idx = item.path("index").asInt(0);
            JsonNode embNode = item.path("embedding");
            float[] emb = new float[embNode.size()];
            for (int i = 0; i < emb.length; i++) {
                emb[i] = (float) embNode.get(i).asDouble();
            }
            if (idx < size) ordered[idx] = emb;
        }

        List<float[]> result = new ArrayList<>(size);
        for (float[] emb : ordered) {
            result.add(emb != null ? emb : new float[DIMENSION]);
        }
        return result;
    }

    /**
     * 임베딩 서버에 연결 가능한지 확인한다. 헬스체크용 경량 요청.
     *
     * @return 서버 응답 시 true
     */
    public boolean isAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            int status = httpClient.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.debug("임베딩 서버 연결 불가 ({}): {}", baseUrl, e.getMessage());
            return false;
        }
    }
}
