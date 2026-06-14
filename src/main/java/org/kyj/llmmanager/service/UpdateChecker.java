/*
 * 작성자 : kyj
 * 작성일 : 2026-06-14
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;

/**
 * GitHub Releases API를 조회해 현재 버전보다 새 버전이 있는지 확인한다.
 * app.properties에서 현재 버전과 레포 정보를 읽고,
 * semver 정수 비교로 "1.10.0 > 1.9.0"을 올바르게 처리한다.
 */
public class UpdateChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 새 버전 정보.
     *
     * @param tagName      GitHub Release 태그 (예: "v1.0.5")
     * @param releaseNotes GitHub Release body 텍스트
     * @param zipUrl       zip 산출물의 download URL (없으면 null)
     */
    public record UpdateInfo(String tagName, String releaseNotes, String zipUrl) {}

    /** app.properties에서 읽은 현재 버전 (예: "1.0.4"). */
    private final String currentVersion;

    /** GitHub 레포 (예: "zaruous/llm_manager"). */
    private final String repo;

    public UpdateChecker(String currentVersion, String repo) {
        this.currentVersion = currentVersion;
        this.repo           = repo;
    }

    /**
     * 클래스패스의 app.properties를 읽어 UpdateChecker를 생성한다.
     * 파일이 없거나 읽기 실패 시 기본값으로 폴백한다.
     *
     * @return app.properties 기반 UpdateChecker 인스턴스
     */
    public static UpdateChecker fromProperties() {
        try (InputStream is = UpdateChecker.class
                .getResourceAsStream("/org/kyj/llmmanager/app.properties")) {
            Properties props = new Properties();
            if (is != null) props.load(is);
            return new UpdateChecker(
                    props.getProperty("app.version",     "0.0.0"),
                    props.getProperty("app.github.repo", "zaruous/llm_manager"));
        } catch (Exception e) {
            return new UpdateChecker("0.0.0", "zaruous/llm_manager");
        }
    }

    /** @return 현재 버전 문자열 (app.properties의 app.version) */
    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * GitHub Releases API에서 최신 릴리즈를 조회하고 현재 버전보다 새 버전이 있으면 반환한다.
     *
     * @return 새 버전 정보, 최신 버전이면 empty
     * @throws Exception API 호출 실패·네트워크 오류 시
     */
    public Optional<UpdateInfo> checkForUpdate() throws Exception {
        String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        // 릴리즈가 하나도 없는 경우
        if (res.statusCode() == 404) return Optional.empty();
        if (res.statusCode() != 200)
            throw new Exception("GitHub API 오류: HTTP " + res.statusCode());

        JsonNode root    = MAPPER.readTree(res.body());
        String tagName   = root.path("tag_name").asText("").strip();
        if (tagName.isBlank()) return Optional.empty();

        String latestVer = tagName.startsWith("v") ? tagName.substring(1) : tagName;
        if (!isNewer(latestVer, currentVersion)) return Optional.empty();

        String notes  = root.path("body").asText("").strip();
        String zipUrl = null;
        for (JsonNode asset : root.path("assets")) {
            if (asset.path("name").asText("").endsWith(".zip")) {
                zipUrl = asset.path("browser_download_url").asText(null);
                break;
            }
        }
        return Optional.of(new UpdateInfo(tagName, notes, zipUrl));
    }

    /**
     * candidate가 current보다 새 버전인지 판단한다.
     * x.y.z 각 부분을 정수로 비교해 "1.10.0 > 1.9.0"을 올바르게 처리한다.
     *
     * @param candidate 비교 대상 버전 문자열
     * @param current   현재 버전 문자열
     * @return candidate가 더 높은 버전이면 true
     */
    static boolean isNewer(String candidate, String current) {
        int[] c = parseSemver(candidate);
        int[] v = parseSemver(current);
        for (int i = 0; i < 3; i++) {
            if (c[i] != v[i]) return c[i] > v[i];
        }
        return false;
    }

    private static int[] parseSemver(String ver) {
        String[] parts = ver.replaceAll("[^0-9.]", "").split("\\.", -1);
        int[] result = new int[3];
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try { result[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) {}
        }
        return result;
    }
}
