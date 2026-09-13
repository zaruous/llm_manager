/*
 * 작성자 : kyj
 * 작성일 : 2026-07-08
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 옵시디언 구조가 이미 갖춰진 Markdown을 LLM 없이 빠르게 위키 source wrapper와
 * 벡터 색인으로 적재한다.
 *
 * raw 원본은 수정하지 않고 유지하며, `wiki/sources/`에 최소 frontmatter wrapper를
 * 생성·갱신한 뒤 필요 시 재색인 훅을 호출한다.
 */
public class WikiDirectImportService {

    @FunctionalInterface
    public interface Reindexer {
        WikiIndexService.IndexResult reindex(Path workspace, Consumer<String> onOutput);
    }

    public record ImportResult(int importedCount, int skippedCount,
                               List<Path> sourcePages,
                               WikiIndexService.IndexResult indexResult) {}

    private final Reindexer reindexer;

    public WikiDirectImportService(Reindexer reindexer) {
        this.reindexer = reindexer;
    }

    /**
     * markdown 파일 목록을 `wiki/sources/` wrapper로 적재하고 필요 시 재색인한다.
     *
     * @param workspace 위키 워크스페이스 루트
     * @param files raw 내부 또는 워크스페이스 기준 markdown 파일 목록
     * @param onOutput 진행 메시지 콜백
     * @return 적재 결과 요약
     * @throws IOException 파일 읽기/쓰기 실패 시
     */
    public ImportResult importMarkdownFiles(Path workspace, List<Path> files,
                                            Consumer<String> onOutput) throws IOException {
        Consumer<String> output = onOutput != null ? onOutput : ignored -> {};
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (!WikiWorkspaceInitializer.isInitialized(normalizedWorkspace)) {
            throw new IOException("위키 워크스페이스가 아닙니다 (wiki/index.md 없음): "
                    + normalizedWorkspace);
        }

        int imported = 0;
        int skipped = 0;
        List<Path> sourcePages = new ArrayList<>();
        Path sourcesDir = normalizedWorkspace.resolve("wiki").resolve("sources");
        boolean sourcesDirCreated = false;

        for (Path file : files) {
            Path normalized = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) || !isMarkdown(normalized)) {
                skipped++;
                output.accept("[direct-import] skip: markdown 아님 — " + normalized.getFileName());
                continue;
            }
            if (!sourcesDirCreated) {
                Files.createDirectories(sourcesDir);
                sourcesDirCreated = true;
            }
            Path page = materializeSourcePage(normalizedWorkspace, normalized, sourcesDir);
            sourcePages.add(page);
            imported++;
            output.accept("[direct-import] source 생성: "
                    + normalizedWorkspace.relativize(page).toString().replace('\\', '/'));
        }

        if (imported == 0) {
            output.accept("[direct-import] 적재할 markdown가 없어 재색인을 건너뜁니다.");
            return new ImportResult(0, skipped, List.of(), null);
        }

        syncIndex(normalizedWorkspace, sourcePages);
        appendLog(normalizedWorkspace, sourcePages);

        WikiIndexService.IndexResult indexResult = null;
        if (reindexer != null) {
            output.accept("[direct-import] 벡터 색인 시작");
            indexResult = reindexer.reindex(normalizedWorkspace, output);
        }
        return new ImportResult(imported, skipped, List.copyOf(sourcePages), indexResult);
    }

    private Path materializeSourcePage(Path workspace, Path rawFile, Path sourcesDir) throws IOException {
        String title = readTitle(rawFile);
        String raw = Files.readString(rawFile, StandardCharsets.UTF_8);
        String relativeRaw = workspace.relativize(rawFile).toString().replace('\\', '/');
        Path page = resolveSourcePage(workspace, rawFile, sourcesDir);
        String content = buildWrapper(title, relativeRaw, raw);
        Files.writeString(page, content, StandardCharsets.UTF_8);
        return page;
    }

    private Path resolveSourcePage(Path workspace, Path rawFile, Path sourcesDir) throws IOException {
        String relativeRaw = workspace.relativize(rawFile).toString().replace('\\', '/');
        for (Path existing : WikiMarkdownUtils.listMarkdown(sourcesDir)) {
            String mapped = WikiMarkdownUtils.readFrontmatterValue(existing, "source_file");
            if (relativeRaw.equals(mapped)) {
                return existing;
            }
        }

        String slug = slug(rawFile);
        Path candidate = sourcesDir.resolve(slug + ".md");
        for (int i = 1; Files.exists(candidate); i++) {
            String mapped = WikiMarkdownUtils.readFrontmatterValue(candidate, "source_file");
            if (relativeRaw.equals(mapped)) {
                return candidate;
            }
            candidate = sourcesDir.resolve(slug + "-" + i + ".md");
        }
        return candidate;
    }

    private String buildWrapper(String title, String relativeRaw, String rawMarkdown) {
        return "---\n"
                + "title: \"" + escapeYaml(title) + "\"\n"
                + "type: source\n"
                + "tags: [obsidian-import]\n"
                + "source_file: " + relativeRaw + "\n"
                + "import_mode: direct-obsidian\n"
                + "last_updated: " + LocalDate.now() + "\n"
                + "---\n\n"
                + "## 원문\n\n"
                + rawMarkdown.strip() + "\n";
    }

    private void syncIndex(Path workspace, List<Path> pages) throws IOException {
        Path indexFile = workspace.resolve("wiki").resolve("index.md");
        List<String> lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        List<String> entries = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (Path page : pages) {
            String rel = workspace.resolve("wiki").relativize(page).toString().replace('\\', '/');
            targets.add(rel);
            String title = readTitle(page);
            entries.add("- [" + title + "](" + rel + ") — direct obsidian import");
        }

        int sourcesHeader = indexOf(lines, "## Sources");
        if (sourcesHeader < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add("## Sources");
            lines.add("");
            sourcesHeader = lines.size() - 2;
        }

        int nextHeader = lines.size();
        for (int i = sourcesHeader + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                nextHeader = i;
                break;
            }
        }

        List<String> preserved = new ArrayList<>();
        for (int i = sourcesHeader + 1; i < nextHeader; i++) {
            String line = lines.get(i);
            boolean replace = false;
            for (String target : targets) {
                if (line.contains("(" + target + ")")) {
                    replace = true;
                    break;
                }
            }
            if (!replace) preserved.add(line);
        }

        List<String> replacement = new ArrayList<>();
        replacement.addAll(preserved.stream().filter(line -> !line.isBlank()).toList());
        replacement.addAll(entries);
        replacement.sort(String::compareToIgnoreCase);

        List<String> updated = new ArrayList<>(lines.subList(0, sourcesHeader + 1));
        updated.add("");
        updated.addAll(replacement);
        updated.add("");
        updated.addAll(lines.subList(nextHeader, lines.size()));
        Files.write(indexFile, updated, StandardCharsets.UTF_8);
    }

    private void appendLog(Path workspace, List<Path> pages) throws IOException {
        Path logFile = workspace.resolve("wiki").resolve("log.md");
        String current = Files.readString(logFile, StandardCharsets.UTF_8);
        StringBuilder append = new StringBuilder(current);
        if (!current.endsWith("\n")) append.append('\n');
        for (Path page : pages) {
            String title = readTitle(page);
            append.append("## [")
                    .append(LocalDate.now())
                    .append("] ingest | ")
                    .append(title)
                    .append(" (direct-obsidian)\n");
        }
        Files.writeString(logFile, append.toString(), StandardCharsets.UTF_8);
    }

    private String readTitle(Path markdownFile) {
        String title = WikiMarkdownUtils.readFrontmatterValue(markdownFile, "title");
        if (title != null && !title.isBlank()) return title;
        return WikiMarkdownUtils.stem(markdownFile);
    }

    private static boolean isMarkdown(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md");
    }

    private static int indexOf(List<String> lines, String exactLine) {
        for (int i = 0; i < lines.size(); i++) {
            if (exactLine.equals(lines.get(i).strip())) return i;
        }
        return -1;
    }

    private static String slug(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9가-힣]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "source" : slug;
    }

    private static String escapeYaml(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
