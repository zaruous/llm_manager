/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 위키 마크다운 파일을 임베딩용 청크로 분할한다.
 *
 * ## 헤딩 경계에서 분할하며, 최대 800자를 초과하는 청크는 200자 overlap으로 재분할한다.
 * frontmatter에서 type·tags를 추출하고, 각 청크에 SHA-256 앞 16자리 content_hash를 부여한다.
 */
public final class WikiChunker {

    /** 청크 최대 문자 수 (한글 기준 약 400어절). */
    private static final int MAX_CHUNK_CHARS = 800;
    /** 인접 청크 간 overlap 문자 수. 문맥 연속성 보존용. */
    private static final int OVERLAP_CHARS = 200;

    /** ## 이상 헤딩 경계 패턴. */
    private static final Pattern HEADING = Pattern.compile("^#{2,}\\s+.+$", Pattern.MULTILINE);
    /** frontmatter 블록 패턴 (--- 사이). */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", Pattern.DOTALL);
    /** frontmatter 단일 키 추출 패턴. */
    private static final Pattern FM_VALUE = Pattern.compile(
            "^(\\w[\\w.\\-]*)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    private WikiChunker() {}

    /**
     * 청크 한 항목. chunkNo는 파일 내 0-based 순번.
     *
     * @param chunkNo     파일 내 0-based 청크 번호
     * @param type        frontmatter type 값 또는 카테고리 디렉토리명
     * @param tags        frontmatter tags 값 (없으면 빈 문자열)
     * @param content     청크 텍스트
     * @param contentHash SHA-256 앞 16자 (증분 재색인 비교용)
     */
    public record Chunk(int chunkNo, String type, String tags, String content, String contentHash) {}

    /**
     * 파일을 읽어 청크 목록으로 분할한다.
     *
     * @param file     마크다운 파일 경로
     * @param category 카테고리 디렉토리명 (type frontmatter 없을 때 사용)
     * @return 청크 목록 (순서 보장)
     */
    public static List<Chunk> chunk(Path file, String category) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }

        // frontmatter 추출
        String type = extractFrontmatter(raw, "type");
        if (type == null || type.isBlank()) type = category != null ? category : "";
        String tags = extractFrontmatter(raw, "tags");
        if (tags == null) tags = "";

        // frontmatter 제거 후 본문
        String body = FRONTMATTER.matcher(raw).replaceFirst("").trim();

        // ## 헤딩 경계로 섹션 분할
        List<String> sections = splitBySections(body);

        // 각 섹션을 MAX_CHUNK_CHARS 이내로 재분할 (overlap 포함)
        List<String> rawChunks = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= MAX_CHUNK_CHARS) {
                rawChunks.add(section);
            } else {
                rawChunks.addAll(slidingWindow(section));
            }
        }

        List<Chunk> result = new ArrayList<>();
        final String typeFinal = type;
        final String tagsFinal = tags;
        for (int i = 0; i < rawChunks.size(); i++) {
            String text = rawChunks.get(i).strip();
            if (text.isBlank()) continue;
            result.add(new Chunk(i, typeFinal, tagsFinal, text, hash(text)));
        }
        return result;
    }

    // ──────────────────────────────────────────────
    // 내부 구현
    // ──────────────────────────────────────────────

    /** ## 이상 헤딩 경계에서 본문을 섹션으로 분할한다. */
    private static List<String> splitBySections(String body) {
        List<String> sections = new ArrayList<>();
        var matcher = HEADING.matcher(body);
        int prev = 0;
        while (matcher.find()) {
            int start = matcher.start();
            if (start > prev) {
                String s = body.substring(prev, start).strip();
                if (!s.isBlank()) sections.add(s);
            }
            prev = start;
        }
        if (prev < body.length()) {
            String s = body.substring(prev).strip();
            if (!s.isBlank()) sections.add(s);
        }
        if (sections.isEmpty() && !body.isBlank()) {
            sections.add(body.strip());
        }
        return sections;
    }

    /**
     * 텍스트를 MAX_CHUNK_CHARS 단위로 슬라이딩 윈도우 분할한다.
     * 각 청크는 이전 청크의 마지막 OVERLAP_CHARS 문자를 앞에 포함한다.
     */
    private static List<String> slidingWindow(String text) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + MAX_CHUNK_CHARS, len);
            chunks.add(text.substring(start, end));
            if (end == len) break;
            start = end - OVERLAP_CHARS;
        }
        return chunks;
    }

    /** frontmatter에서 특정 키 값을 추출한다. 없으면 null 반환. */
    private static String extractFrontmatter(String raw, String key) {
        var fmMatcher = FRONTMATTER.matcher(raw);
        if (!fmMatcher.find()) return null;
        String fmBlock = fmMatcher.group(1);
        var valMatcher = FM_VALUE.matcher(fmBlock);
        while (valMatcher.find()) {
            if (valMatcher.group(1).equalsIgnoreCase(key)) {
                return valMatcher.group(2).trim();
            }
        }
        return null;
    }

    /** 텍스트의 SHA-256 앞 16자리를 반환한다. */
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM에서 보장되므로 도달하지 않는다.
            throw new IllegalStateException(e);
        }
    }
}
