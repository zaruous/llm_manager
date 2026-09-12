/*
 * 작성자 : kyj
 * 작성일 : 2026-09-12
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 릴리즈에서 최신 JAR 정보를 조회한다.
 *
 * <p>서비스 정의의 {@code downloadUrl}은 특정 태그에 고정돼 있어, 새 버전이 나와도
 * 계속 옛 파일을 받는다. 이 클래스로 최신 릴리즈를 조회해 다운로드 대상을 갱신한다.
 *
 * <p>인증 없이 공개 API를 호출하므로 IP당 시간당 60회 제한을 받는다. 버튼을 눌렀을 때만
 * 호출하므로 실사용에서 문제가 되지 않는다.
 */
public class GitHubReleaseClient {

    /**
     * 릴리즈 asset 하나의 정보.
     *
     * @param tagName     릴리즈 태그명 (예: v1.0.1)
     * @param assetName   asset 파일명 (예: sql-gen-mcp-1.0.1.jar)
     * @param downloadUrl asset 다운로드 URL
     * @param size        asset 크기 (바이트). 알 수 없으면 -1
     */
    public record ReleaseAsset(String tagName, String assetName, String downloadUrl, long size) {

        /**
         * @return "v1.0.1 / sql-gen-mcp-1.0.1.jar (239 MB)" 형태의 표시 문자열
         */
        public String describe() {
            String sizeText = size > 0 ? " (" + (size / (1024 * 1024)) + " MB)" : "";
            return tagName + " / " + assetName + sizeText;
        }
    }

    /**
     * {@code https://github.com/<owner>/<repo>/releases/download/<tag>/<file>} 형태에서
     * owner/repo를 뽑아낸다.
     */
    private static final Pattern RELEASE_URL =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+)/releases/download/", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 릴리즈 다운로드 URL에서 {@code owner/repo}를 추출한다.
     *
     * @param downloadUrl 릴리즈 asset 다운로드 URL
     * @return "owner/repo" 문자열. GitHub 릴리즈 URL이 아니면 null
     */
    public static String parseOwnerRepo(String downloadUrl) {
        if (downloadUrl == null) return null;
        Matcher m = RELEASE_URL.matcher(downloadUrl.trim());
        return m.find() ? m.group(1) + "/" + m.group(2) : null;
    }

    /**
     * 해당 레포의 최신 릴리즈에서 JAR asset 하나를 찾아 반환한다.
     *
     * <p>한 릴리즈에 JAR이 여러 개 올라가 있을 수 있어(빌드 산출물 재업로드, 버전 표기가
     * 다른 파일이 함께 남은 경우 등) 다음 순서로 점수를 매겨 고른다. 크기로 고르면
     * 낡은 SNAPSHOT 파일이 몇십 바이트 더 커서 선택되는 일이 실제로 생긴다.
     *
     * <ol>
     *   <li>{@code original-} 접두사 제외 — shade 플러그인이 남기는 비-fat JAR이다</li>
     *   <li>파일명이 태그 버전으로 끝나는 것 우선 (v1.0.1 → {@code ...-1.0.1.jar})</li>
     *   <li>{@code SNAPSHOT}이 없는 것 우선</li>
     *   <li>그래도 남으면 큰 것</li>
     * </ol>
     *
     * @param ownerRepo "owner/repo" 형태 문자열
     * @return 최신 릴리즈의 JAR asset 정보
     * @throws IOException          HTTP 오류, JAR asset이 없는 경우
     * @throws InterruptedException 요청 중 인터럽트된 경우
     */
    public ReleaseAsset fetchLatestJar(String ownerRepo) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/" + ownerRepo + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "llm-manager")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            throw new IOException("릴리즈를 찾을 수 없습니다: " + ownerRepo);
        }
        if (res.statusCode() == 403) {
            throw new IOException("GitHub API 호출 한도를 초과했습니다. 잠시 후 다시 시도하세요.");
        }
        if (res.statusCode() != 200) {
            throw new IOException("릴리즈 조회 실패 — HTTP " + res.statusCode());
        }

        return selectJarAsset(MAPPER.readTree(res.body()));
    }

    /**
     * 릴리즈 JSON에서 받을 JAR asset 하나를 고른다. 규칙은
     * {@link #fetchLatestJar(String)} 문서를 참고한다.
     *
     * <p>네트워크 없이 검증할 수 있도록 분리했다.
     *
     * @param root GitHub 릴리즈 API 응답 루트 노드
     * @return 선택된 asset 정보
     * @throws IOException 받을 만한 JAR asset이 없을 때
     */
    static ReleaseAsset selectJarAsset(JsonNode root) throws IOException {
        String tag = root.path("tag_name").asText("");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;

        JsonNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonNode asset : root.path("assets")) {
            String name = asset.path("name").asText("");
            String lower = name.toLowerCase();
            if (!lower.endsWith(".jar")) continue;
            if (lower.startsWith("original-")) continue;

            int score = 0;
            if (!version.isEmpty() && name.endsWith("-" + version + ".jar")) score += 4;
            if (!lower.contains("snapshot")) score += 2;

            if (best == null || score > bestScore
                    || (score == bestScore
                        && asset.path("size").asLong(0) > best.path("size").asLong(0))) {
                best = asset;
                bestScore = score;
            }
        }
        if (best == null) {
            throw new IOException("최신 릴리즈 " + tag + "에 JAR 파일이 없습니다.");
        }

        return new ReleaseAsset(
                tag,
                best.path("name").asText(""),
                best.path("browser_download_url").asText(""),
                best.path("size").asLong(-1));
    }
}
