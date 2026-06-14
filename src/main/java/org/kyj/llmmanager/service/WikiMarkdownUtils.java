/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 위키 마크다운 파일 처리 공통 유틸리티.
 *
 * WikiBrowserDialog와 WikiSiteExporter가 공유하는 페이지 수집·색인·
 * frontmatter 파싱·키 정규화 로직을 정적 메서드로 제공한다.
 */
public final class WikiMarkdownUtils {

    /** sources/entities/concepts/syntheses 순서 유지 */
    public static final List<String> CATEGORIES =
            List.of("sources", "entities", "concepts", "syntheses");

    private WikiMarkdownUtils() {}

    /** 위키 페이지 한 항목 — category는 "docs"·"sources" 등. */
    public record PageEntry(String category, String stem, Path file) {}

    /**
     * wiki/ 디렉토리에서 모든 페이지를 수집한다.
     * 순서: 문서(overview/index/log) → 카테고리 4종 → 리포트(health/lint).
     *
     * @param wikiDir wiki/ 디렉토리 경로
     * @return 페이지 목록 (순서 유지)
     */
    public static List<PageEntry> collectPages(Path wikiDir) {
        List<PageEntry> pages = new ArrayList<>();
        for (String name : List.of("overview.md", "index.md", "log.md")) {
            Path file = wikiDir.resolve(name);
            if (Files.isRegularFile(file))
                pages.add(new PageEntry("docs", stem(name), file));
        }
        for (String cat : CATEGORIES) {
            listMarkdown(wikiDir.resolve(cat))
                    .forEach(p -> pages.add(new PageEntry(cat, stem(p), p)));
        }
        for (String name : List.of("health-report.md", "lint-report.md")) {
            Path file = wikiDir.resolve(name);
            if (Files.isRegularFile(file))
                pages.add(new PageEntry("reports", stem(name), file));
        }
        return pages;
    }

    /**
     * 페이지 목록으로 이름→경로 색인을 구성한다.
     * 파일명 스템과 frontmatter title 양쪽 키로 등록해 위키링크 매칭률을 높인다.
     *
     * @param pages collectPages() 결과
     * @return normalizeKey(이름) → 파일 경로 맵 (LinkedHashMap, 순서 유지)
     */
    public static Map<String, Path> buildPageIndex(List<PageEntry> pages) {
        Map<String, Path> index = new LinkedHashMap<>();
        for (PageEntry page : pages) {
            index.put(normalizeKey(page.stem()), page.file());
            String title = readFrontmatterValue(page.file(), "title");
            if (title != null && !title.isBlank())
                index.put(normalizeKey(title), page.file());
        }
        return index;
    }

    /**
     * YAML frontmatter를 제거하고 type·last_updated만 인용구 메타 라인으로 남긴다.
     *
     * @param md 원본 마크다운 문자열
     * @return frontmatter가 제거된 마크다운
     */
    public static String stripFrontmatterToMeta(String md) {
        if (!md.startsWith("---")) return md;
        int end = md.indexOf("\n---", 3);
        if (end < 0) return md;
        String frontmatter = md.substring(3, end);
        String body = md.substring(md.indexOf('\n', end + 1) + 1);
        List<String> meta = new ArrayList<>();
        for (String line : frontmatter.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("type:") || stripped.startsWith("last_updated:"))
                meta.add(stripped);
        }
        return meta.isEmpty() ? body : "> " + String.join(" · ", meta) + "\n\n" + body;
    }

    /**
     * 대소문자·하이픈·언더스코어 차이를 무시하는 링크 매칭 키를 반환한다.
     *
     * @param name 정규화할 이름
     * @return 소문자+공백 정규화된 키
     */
    public static String normalizeKey(String name) {
        return name.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }

    /**
     * 파일의 YAML frontmatter에서 지정한 키의 값을 읽는다.
     *
     * @param file 마크다운 파일
     * @param key  찾을 YAML 키 (예: "title")
     * @return 값 문자열, 없으면 null
     */
    public static String readFrontmatterValue(Path file, String key) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).strip().equals("---")) return null;
            for (int i = 1; i < Math.min(lines.size(), 20); i++) {
                String line = lines.get(i).strip();
                if (line.equals("---")) break;
                if (line.startsWith(key + ":"))
                    return line.substring(key.length() + 1).trim().replaceAll("^\"|\"$", "");
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 파일의 스템(확장자 제거 이름)을 반환한다.
     *
     * @param file 파일 경로
     * @return .md 확장자가 제거된 파일명
     */
    public static String stem(Path file) {
        return stem(file.getFileName().toString());
    }

    /**
     * 파일명의 스템(확장자 제거)을 반환한다.
     *
     * @param fileName 파일명 문자열
     * @return .md 확장자가 제거된 이름
     */
    public static String stem(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    /** 디렉토리에서 .md 파일 목록을 정렬해 반환한다. */
    public static List<Path> listMarkdown(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return new ArrayList<>(stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList());
        } catch (Exception e) {
            return List.of();
        }
    }
}
