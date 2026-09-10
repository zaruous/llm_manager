/*
 * 작성자 : kyj
 * 작성일 : 2026-06-24
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 위키 마크다운 파일을 임베딩용 청크로 전처리한다.
 *
 * YAML frontmatter에서 type·tags를 추출하고, 본문에 경로·분류 접두어를 붙인 뒤
 * Options에 따라 단락 경계 우선으로 청크를 분할한다. WikiChunker가 해시를 부여한다.
 */
public final class WikiPreprocessor {

    private WikiPreprocessor() {}

    /**
     * 청킹 파라미터.
     *
     * @param targetChunkChars  목표 청크 크기 (문자 수) — 이 크기에 도달하면 청크를 확정한다
     * @param maxChunkChars     최대 청크 크기 — 단락 하나가 이를 초과하면 강제 분할한다
     * @param chunkOverlapChars 인접 청크 간 중첩 문자 수 (검색 경계 손실 완화)
     */
    public record Options(int targetChunkChars, int maxChunkChars, int chunkOverlapChars) {

        /** 기본값 — targetChunkChars=1500, maxChunkChars=2500, chunkOverlapChars=200. */
        public static Options defaults() {
            return new Options(1500, 2500, 200);
        }
    }

    /**
     * 전처리 결과.
     *
     * @param chunks   분할된 청크 텍스트 목록 (순서 보장)
     * @param type     frontmatter type 값, 없으면 카테고리 디렉토리명
     * @param tags     frontmatter tags 값, 없으면 빈 문자열
     * @param warnings 전처리 중 발생한 경고 메시지 목록 (정상 시 빈 목록)
     */
    public record PreparedDocument(List<String> chunks, String type, String tags,
                                   List<String> warnings) {}

    /**
     * 마크다운 파일을 읽어 임베딩 전처리된 문서를 반환한다.
     *
     * @param file         대상 마크다운 파일
     * @param category     frontmatter type 없을 때 사용할 카테고리 (예: "sources")
     * @param relativePath 워크스페이스 기준 상대 경로 (청크 접두어·로그에 사용)
     * @param options      청킹 파라미터
     * @return 전처리 결과
     */
    public static PreparedDocument preprocess(Path file, String category, String relativePath,
                                              Options options) {
        List<String> warnings = new ArrayList<>();

        if (file == null || !Files.isRegularFile(file)) {
            warnings.add("파일 없음: " + relativePath);
            return new PreparedDocument(List.of(), category, "", warnings);
        }

        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add("파일 읽기 실패: " + e.getMessage());
            return new PreparedDocument(List.of(), category, "", warnings);
        }

        // YAML frontmatter 파싱
        String type = category;
        String tags = "";
        String body = raw;

        if (raw.startsWith("---")) {
            int endIdx = raw.indexOf("\n---", 3);
            if (endIdx >= 0) {
                String frontmatter = raw.substring(3, endIdx);
                String extractedType = extractValue(frontmatter, "type");
                String extractedTags = extractValue(frontmatter, "tags");
                if (extractedType != null && !extractedType.isBlank()) {
                    type = extractedType;
                }
                if (extractedTags != null) {
                    tags = extractedTags;
                }
                int bodyStart = raw.indexOf('\n', endIdx + 1);
                body = bodyStart >= 0 ? raw.substring(bodyStart + 1) : "";
            } else {
                warnings.add("frontmatter 닫기(---) 없음 — 본문 전체를 청킹 대상으로 사용");
            }
        }

        // 의미 보강 접두어 — 검색 시 메타데이터 컨텍스트 제공
        String title = extractTitle(body, relativePath);
        String prefix = buildPrefix(title, type, tags, relativePath);
        String enriched = prefix + body.strip();

        List<String> chunks = splitIntoChunks(enriched, options);
        if (chunks.isEmpty() && !enriched.isBlank()) {
            warnings.add("청킹 결과 빈 목록 (내용 존재)");
        }

        return new PreparedDocument(chunks, type, tags, warnings);
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────

    /** frontmatter 블록에서 특정 키의 값을 추출한다. */
    private static String extractValue(String frontmatter, String key) {
        for (String line : frontmatter.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith(key + ":")) {
                return stripped.substring(key.length() + 1).trim()
                        .replaceAll("^\"|\"$", "");
            }
        }
        return null;
    }

    /** 본문 첫 번째 # 헤딩에서 제목을 추출하거나, 없으면 파일명으로 대체한다. */
    private static String extractTitle(String body, String relativePath) {
        for (String line : body.split("\\R", 10)) {
            String stripped = line.strip();
            if (stripped.startsWith("# ")) return stripped.substring(2).trim();
        }
        String fileName = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        if (fileName.endsWith(".md")) fileName = fileName.substring(0, fileName.length() - 3);
        return fileName.replace('-', ' ').replace('_', ' ');
    }

    /** 청크 공통 접두어 — 경로·분류·태그를 포함해 임베딩 품질을 높인다. */
    private static String buildPrefix(String title, String type, String tags, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(path).append("]\n");
        if (!title.isBlank()) sb.append("제목: ").append(title).append("\n");
        sb.append("분류: ").append(type).append("\n");
        if (!tags.isBlank()) sb.append("태그: ").append(tags).append("\n");
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 텍스트를 Options에 따라 청크로 분할한다.
     * 단락(빈 줄) 경계를 우선하며, 단락이 maxChunkChars를 초과하면 강제 분할한다.
     */
    private static List<String> splitIntoChunks(String text, Options options) {
        if (text == null || text.isBlank()) return List.of();

        int target = Math.max(100, options.targetChunkChars());
        int max = Math.max(target + 100, options.maxChunkChars());
        int overlap = Math.max(0, Math.min(options.chunkOverlapChars(), target / 2));

        String[] paragraphs = text.split("\n\n+");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String p = para.strip();
            if (p.isEmpty()) continue;

            if (p.length() > max) {
                // 현재까지 쌓인 내용 먼저 저장
                if (!current.isEmpty()) {
                    String saved = current.toString().strip();
                    chunks.add(saved);
                    current.setLength(0);
                    if (overlap > 0 && saved.length() > overlap) {
                        current.append(saved, saved.length() - overlap, saved.length()).append("\n\n");
                    }
                }
                chunks.addAll(forceSplit(p, target, max, overlap));
                continue;
            }

            int addedLen = current.isEmpty() ? p.length() : current.length() + 2 + p.length();

            if (addedLen > max && !current.isEmpty()) {
                String saved = current.toString().strip();
                chunks.add(saved);
                current.setLength(0);
                if (overlap > 0 && saved.length() > overlap) {
                    current.append(saved, saved.length() - overlap, saved.length()).append("\n\n");
                }
                current.append(p);
            } else if (addedLen >= target && !current.isEmpty()) {
                if (!current.isEmpty()) current.append("\n\n");
                current.append(p);
                String saved = current.toString().strip();
                chunks.add(saved);
                current.setLength(0);
                if (overlap > 0 && saved.length() > overlap) {
                    current.append(saved, saved.length() - overlap, saved.length()).append("\n\n");
                }
            } else {
                if (!current.isEmpty()) current.append("\n\n");
                current.append(p);
            }
        }

        if (!current.isEmpty() && !current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }

        return chunks.isEmpty() ? List.of(text.strip()) : chunks;
    }

    /** 단락이 maxChunkChars를 초과할 때 단어 경계에서 강제 분할한다. */
    private static List<String> forceSplit(String text, int target, int max, int overlap) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + target, text.length());
            // 단어 경계 탐색 — 분할 지점을 공백 앞으로 당긴다
            if (end < text.length()) {
                int spaceIdx = text.lastIndexOf(' ', end);
                if (spaceIdx > start + target / 2) end = spaceIdx;
            }
            result.add(text.substring(start, end).strip());
            start = Math.max(start + 1, end - overlap);
        }
        return result;
    }
}
