/*
 * 작성자 : kyj
 * 작성일 : 2026-06-25
 */
package org.kyj.llmmanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위키 마크다운 파일을 임베딩용 의미 보강 텍스트로 전처리한다.
 *
 * frontmatter(title·type·tags)를 추출하고, 누락 시 h1 헤딩·파일명·카테고리로 보정한다.
 * 본문을 ## 이상 헤딩 경계로 섹션 분할한 뒤 목표/최대 청크 크기에 맞춰 재분할하며,
 * 각 청크 앞에 "제목/유형/경로/태그/섹션" 메타데이터 헤더를 주입해 검색 문맥을 보강한다.
 * 코드 펜스(```)는 의미 훼손을 막기 위해 최대 크기를 초과해도 분할하지 않는다.
 */
public final class WikiPreprocessor {

    /** frontmatter 블록 패턴 (문서 시작의 --- 사이). */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", Pattern.DOTALL);

    /** ## 이상 헤딩 경계 패턴. h1(#)은 문서 제목으로 취급하므로 경계가 아니다. */
    private static final Pattern HEADING = Pattern.compile(
            "^#{2,}\\s+(.+)$", Pattern.MULTILINE);

    /** h1 헤딩 패턴. frontmatter title 부재 시 제목 보정에 사용. */
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    private WikiPreprocessor() {}

    /**
     * 청킹 크기 설정.
     *
     * @param targetChunkChars  청크 목표 문자 수. 블록 패킹·슬라이딩 윈도우 기준 크기.
     * @param maxChunkChars     청크 최대 문자 수. 초과하는 단일 블록은 재분할 대상.
     * @param overlapChars      슬라이딩 윈도우 분할 시 인접 청크 간 중첩 문자 수.
     */
    public record Options(int targetChunkChars, int maxChunkChars, int overlapChars) {

        /** 기본 설정(1500/2500/200). WikiIndexService의 설정 fallback 값과 동일해야 한다. */
        public static Options defaults() {
            return new Options(1500, 2500, 200);
        }
    }

    /**
     * 전처리 결과 문서.
     *
     * @param title        문서 제목 (frontmatter title → h1 → 파일명 순 보정)
     * @param type         페이지 유형 (frontmatter type → 카테고리 순 보정)
     * @param tags         쉼표 구분 태그 문자열 (없으면 빈 문자열)
     * @param relativePath 워크스페이스 기준 페이지 경로
     * @param chunks       메타데이터 헤더가 주입된 임베딩용 청크 텍스트 목록
     * @param warnings     메타데이터 보정·읽기 실패 등 전처리 중 발생한 경고 목록
     */
    public record PreparedDocument(String title, String type, String tags,
                                   String relativePath,
                                   List<String> chunks, List<String> warnings) {}

    /**
     * 마크다운 파일을 읽어 임베딩용 청크로 전처리한다.
     * 파일 읽기 실패 시 예외 대신 빈 청크 목록과 경고를 담아 반환한다.
     *
     * @param file         마크다운 파일 경로
     * @param category     type frontmatter 부재 시 사용할 카테고리 디렉토리명
     * @param relativePath 워크스페이스 기준 페이지 경로 (메타데이터 헤더에 표기)
     * @param options      청킹 크기 설정
     * @return 전처리 결과 (실패 시에도 null이 아닌 빈 문서)
     */
    public static PreparedDocument preprocess(Path file, String category,
                                              String relativePath, Options options) {
        List<String> warnings = new ArrayList<>();
        String safeCategory = category != null ? category : "";
        String safePath = relativePath != null ? relativePath : "";

        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            warnings.add("파일 읽기 실패: " + e.getMessage());
            return new PreparedDocument("", safeCategory, "", safePath,
                    List.of(), List.copyOf(warnings));
        }

        // ── frontmatter 추출 ─────────────────────────────────────
        Frontmatter fm = parseFrontmatter(raw);
        String body = fm.body();
        if (!fm.present()) {
            warnings.add("frontmatter 없음 — 제목·유형·태그를 파일명·카테고리로 보정");
        }

        // type: frontmatter → 카테고리 순 보정
        String type = fm.get("type");
        if (type == null || type.isBlank()) {
            if (fm.present()) warnings.add("type 누락 — 카테고리(" + safeCategory + ")로 보정");
            type = safeCategory;
        }

        String tags = fm.get("tags");
        if (tags == null) tags = "";

        // title: frontmatter → h1 헤딩 → 파일명 순 보정. h1은 본문에서 제거해 청크 중복을 막는다.
        String title = fm.get("title");
        if (title == null || title.isBlank()) {
            Matcher h1 = H1.matcher(body);
            if (h1.find()) {
                title = h1.group(1).strip();
                body = body.substring(0, h1.start()) + body.substring(h1.end());
            } else {
                title = fileNameWithoutExtension(file);
                if (fm.present()) warnings.add("title 누락 — 파일명(" + title + ")으로 보정");
            }
        }
        body = body.strip();

        // ── 섹션 분할 → 청크 조립 ────────────────────────────────
        List<String> chunks = new ArrayList<>();
        for (Section section : splitSections(body)) {
            String header = buildHeader(title, type, safePath, tags, section.heading());
            for (String chunkBody : chunkSection(section.content(), options)) {
                String text = chunkBody.strip();
                if (!text.isBlank()) chunks.add(header + "\n\n" + text);
            }
        }

        return new PreparedDocument(title, type, tags, safePath,
                List.copyOf(chunks), List.copyOf(warnings));
    }

    // ─────────────────────────────────────────────────────────────
    // frontmatter
    // ─────────────────────────────────────────────────────────────

    /** frontmatter 파싱 결과. present=false면 문서에 frontmatter 블록이 없다. */
    private record Frontmatter(boolean present, java.util.Map<String, String> values, String body) {
        String get(String key) { return values.get(key); }
    }

    /**
     * 문서 시작의 frontmatter 블록을 파싱한다.
     * "key: value" 단일 값과 "key:" 뒤 "- item" YAML 블록 리스트를 지원하며,
     * 리스트 값은 임베딩 텍스트에 쓰기 좋게 ", "로 이어 붙인다.
     */
    private static Frontmatter parseFrontmatter(String raw) {
        Matcher m = FRONTMATTER.matcher(raw);
        if (!m.find()) {
            return new Frontmatter(false, java.util.Map.of(), raw);
        }

        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        String[] lines = m.group(1).split("\r?\n");
        String pendingListKey = null;
        List<String> pendingItems = new ArrayList<>();

        for (String line : lines) {
            String stripped = line.strip();
            // 블록 리스트 항목: 직전 키가 값 없이 끝났을 때만 수집
            if (pendingListKey != null && stripped.startsWith("- ")) {
                pendingItems.add(stripped.substring(2).strip());
                continue;
            }
            if (pendingListKey != null) {
                values.put(pendingListKey, String.join(", ", pendingItems));
                pendingListKey = null;
                pendingItems = new ArrayList<>();
            }

            int colon = stripped.indexOf(':');
            if (colon <= 0) continue;
            String key = stripped.substring(0, colon).strip();
            String value = stripped.substring(colon + 1).strip();
            if (value.isEmpty()) {
                // 값이 비어 있으면 다음 줄부터 블록 리스트일 수 있다
                pendingListKey = key;
            } else {
                values.put(key, unquote(value));
            }
        }
        if (pendingListKey != null) {
            values.put(pendingListKey, String.join(", ", pendingItems));
        }

        return new Frontmatter(true, values, m.replaceFirst(""));
    }

    /** 값을 감싼 홑/쌍따옴표를 제거한다. */
    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                 || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    // ─────────────────────────────────────────────────────────────
    // 섹션 분할
    // ─────────────────────────────────────────────────────────────

    /** 헤딩 하나에 속한 본문 구간. heading은 ##를 제외한 텍스트, 프리앰블이면 null. */
    private record Section(String heading, String content) {}

    /** ## 이상 헤딩 경계에서 본문을 섹션으로 분할한다. 빈 섹션은 제외한다. */
    private static List<Section> splitSections(String body) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING.matcher(body);
        int prev = 0;
        String prevHeading = null;
        while (m.find()) {
            addSection(sections, prevHeading, body.substring(prev, m.start()));
            prevHeading = m.group(1).strip();
            prev = m.end();
        }
        addSection(sections, prevHeading, body.substring(prev));
        return sections;
    }

    private static void addSection(List<Section> sections, String heading, String content) {
        String stripped = content.strip();
        if (!stripped.isBlank()) sections.add(new Section(heading, stripped));
    }

    // ─────────────────────────────────────────────────────────────
    // 청크 조립
    // ─────────────────────────────────────────────────────────────

    /**
     * 섹션 본문을 블록(빈 줄 구분 문단, 코드 펜스는 원자 블록) 단위로 나눈 뒤
     * 목표 크기까지 패킹한다. 최대 크기를 초과하는 일반 텍스트 블록은
     * 슬라이딩 윈도우로 재분할하고, 코드 펜스는 초과해도 통째로 유지한다.
     */
    private static List<String> chunkSection(String content, Options options) {
        List<String> bodies = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String block : splitBlocks(content)) {
            if (block.length() > options.maxChunkChars()) {
                flush(buf, bodies);
                if (block.startsWith("```")) {
                    // 코드 펜스는 분할하면 문법 문맥이 깨지므로 단독 청크로 유지
                    bodies.add(block);
                } else {
                    bodies.addAll(slidingWindow(block, options));
                }
                continue;
            }
            // 블록을 더하면 목표 크기를 넘는 경우 현재 버퍼를 청크로 확정
            if (buf.length() > 0
                    && buf.length() + 2 + block.length() > options.targetChunkChars()) {
                flush(buf, bodies);
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(block);
        }
        flush(buf, bodies);
        return bodies;
    }

    private static void flush(StringBuilder buf, List<String> bodies) {
        if (buf.length() > 0) {
            bodies.add(buf.toString());
            buf.setLength(0);
        }
    }

    /**
     * 섹션 본문을 블록 목록으로 나눈다.
     * 빈 줄이 블록 경계이며, ``` 코드 펜스 내부는 빈 줄이 있어도 하나의 블록으로 유지한다.
     */
    private static List<String> splitBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inFence = false;

        for (String line : content.split("\r?\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                if (!inFence) {
                    // 펜스 시작 — 진행 중이던 문단을 먼저 확정
                    flushBlock(blocks, cur);
                    inFence = true;
                    cur.append(line).append('\n');
                } else {
                    cur.append(line);
                    flushBlock(blocks, cur);
                    inFence = false;
                }
                continue;
            }
            if (inFence) {
                cur.append(line).append('\n');
                continue;
            }
            if (stripped.isEmpty()) {
                flushBlock(blocks, cur);
            } else {
                cur.append(line).append('\n');
            }
        }
        flushBlock(blocks, cur);
        return blocks;
    }

    private static void flushBlock(List<String> blocks, StringBuilder cur) {
        String text = cur.toString().strip();
        if (!text.isBlank()) blocks.add(text);
        cur.setLength(0);
    }

    /**
     * 최대 크기를 초과하는 텍스트를 목표 크기 윈도우로 자른다.
     * 다음 윈도우는 (목표 크기 - 중첩)만큼 전진해 인접 청크 간 문맥을 보존한다.
     */
    private static List<String> slidingWindow(String text, Options options) {
        int target = Math.max(1, options.targetChunkChars());
        // 중첩이 목표 크기 이상이면 전진하지 못하므로 target-1로 제한
        int overlap = Math.max(0, Math.min(options.overlapChars(), target - 1));
        int step = target - overlap;

        List<String> windows = new ArrayList<>();
        for (int pos = 0; pos < text.length(); pos += step) {
            int end = Math.min(pos + target, text.length());
            windows.add(text.substring(pos, end));
            if (end == text.length()) break;
        }
        return windows;
    }

    /**
     * 청크 앞에 붙일 메타데이터 헤더를 만든다.
     * 헤더와 본문은 빈 줄 하나로 구분되므로 헤더 내부에는 빈 줄이 없어야 한다.
     */
    private static String buildHeader(String title, String type, String relativePath,
                                      String tags, String sectionHeading) {
        StringBuilder sb = new StringBuilder();
        sb.append("제목: ").append(title);
        sb.append("\n유형: ").append(type);
        sb.append("\n경로: ").append(relativePath);
        if (tags != null && !tags.isBlank()) sb.append("\n태그: ").append(tags);
        if (sectionHeading != null && !sectionHeading.isBlank()) {
            sb.append("\n섹션: ").append(sectionHeading);
        }
        return sb.toString();
    }

    /** 파일명에서 확장자를 제거해 반환한다. 파일이 null이면 빈 문자열. */
    private static String fileNameWithoutExtension(Path file) {
        if (file == null || file.getFileName() == null) return "";
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
