/*
 * 작성자 : kyj
 * 작성일 : 2026-06-13
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 문서 수집(wiki.ingest)의 실행 계획을 세우는 유틸.
 *
 * 통일 원리: 수집 대상 전체를 "에이전트 1회 실행" 단위의 작업 목록으로 분해한다.
 * 작은 파일 여러 개는 개수·크기 예산의 배치로, 큰 텍스트 파일은 섹션 노트
 * 패스 + 병합 패스 시퀀스로(섹션 파일은 워크스페이스 .llm-manager/ingest/에
 * 스테이징), 큰 바이너리 파일은 단계 지시가 담긴 단독 작업으로 나눈다.
 * 실제 실행·진행 표시는 WikiIngestDialog가 담당한다.
 */
public final class WikiIngestPlanner {

    /** 한 배치에 담을 최대 파일 수 — 파일마다 10단계 워크플로우가 수행되므로 제한 */
    public static final int BATCH_MAX_FILES = 5;

    /** 한 배치의 누적 크기 예산 — 초과 시 새 배치로 분할 */
    public static final long BATCH_BYTES_BUDGET = 512 * 1024;

    /** 이 크기를 넘는 텍스트 파일은 섹션 분할 수집 대상 */
    public static final long TEXT_SECTION_THRESHOLD_BYTES = 200 * 1024;

    /** 이 크기를 넘는 바이너리 파일은 단독 작업(에이전트 내부 단계 처리) 대상 */
    public static final long LARGE_BINARY_THRESHOLD_BYTES = 4L * 1024 * 1024;

    /** 섹션 목표 크기(문자) — 노트 패스 한 번이 무리 없이 읽을 분량 */
    static final int SECTION_TARGET_CHARS = 40_000;

    /** 섹션 강제 분할 한계(문자) — 경계를 못 찾아도 이 이상 커지지 않게 함 */
    static final int SECTION_HARD_LIMIT_CHARS = 60_000;

    /** 한 페이지 판독 패스에 담을 페이지 수 — 이미지 판독은 페이지당 부담이 큼 */
    public static final int PAGE_GROUP_SIZE = 5;

    /** Java가 직접 분할할 수 있는 텍스트 계열 확장자 (나머지는 바이너리 취급) */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "txt", "html", "csv", "json", "xml", "rst", "yaml", "yml", "tsv", "ipynb");

    private WikiIngestPlanner() {
    }

    /**
     * 전처리 도구(extract_pages.py)가 만든 페이지 자산 한 건.
     *
     * @param page 페이지 번호 (1부터)
     * @param textFile 추출 텍스트 파일명 (pages 디렉토리 기준)
     * @param imageFile 렌더 이미지 파일명 (pages 디렉토리 기준)
     * @param textChars 추출 텍스트 길이 — 0에 가까우면 스캔/이미지 페이지
     */
    public record PageAsset(int page, String textFile, String imageFile, int textChars) {
    }

    /**
     * 대용량 PDF를 페이지 자산으로 분해하는 전처리 훅.
     * 실패 시 예외를 던지면 플래너가 단독 작업 모드로 폴백한다.
     */
    @FunctionalInterface
    public interface PageExtractor {
        List<PageAsset> extract(Path source, Path outputDir) throws Exception;
    }

    /**
     * 에이전트 1회 실행 단위 작업.
     *
     * @param label 진행 표시용 한글 라벨
     * @param payload 프롬프트 본문 (배치=파일 목록, 섹션=섹션 파일, 병합=노트 파일 — 워크스페이스 상대 경로)
     * @param options ingestMode 등 프롬프트 조립 파라미터 (PluginCommandRequest.options로 전달)
     * @param stagingDir 작업 성공 후 정리할 스테이징 디렉토리. null이면 정리 없음
     */
    public record IngestTask(String label, String payload,
                             Map<String, String> options, Path stagingDir) {
    }

    /**
     * 수집 실행 계획.
     *
     * @param tasks 순차 실행할 작업 목록
     * @param fileCount 수집 대상 파일 수
     * @param totalBytes 수집 대상 총 크기
     */
    public record IngestPlan(List<IngestTask> tasks, int fileCount, long totalBytes) {
    }

    /**
     * 수집 대상 파일들을 작업 목록으로 분해한다 (전처리 없음 — 큰 PDF는 단독 작업).
     *
     * @param files 수집 대상 (워크스페이스 내부 절대 경로)
     * @param workspace 워크스페이스 루트
     * @return 실행 계획
     * @throws IOException 파일 크기 조회·섹션 스테이징 실패 시
     */
    public static IngestPlan plan(List<Path> files, Path workspace) throws IOException {
        return plan(files, workspace, null, line -> { });
    }

    /**
     * 수집 대상 파일들을 작업 목록으로 분해한다. 큰 텍스트 파일은 이 시점에
     * 섹션 파일로 분할되고, 큰 PDF는 전처리 훅으로 페이지 자산(텍스트+이미지)으로
     * 분해되어 페이지 판독 패스 시퀀스가 된다. 전처리 실패 시 단독 작업으로 폴백.
     *
     * @param files 수집 대상 (워크스페이스 내부 절대 경로)
     * @param workspace 워크스페이스 루트
     * @param pageExtractor PDF 페이지 분해 훅. null이면 페이지 전처리 생략
     * @param onOutput 전처리 진행·폴백 안내 출력 콜백
     * @return 실행 계획
     * @throws IOException 파일 크기 조회·섹션 스테이징 실패 시
     */
    public static IngestPlan plan(List<Path> files, Path workspace,
                                  PageExtractor pageExtractor, Consumer<String> onOutput)
            throws IOException {
        List<IngestTask> tasks = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        long batchBytes = 0;
        long totalBytes = 0;

        for (Path file : files) {
            long size = Files.size(file);
            totalBytes += size;
            String rel = relative(workspace, file);

            if (isTextFile(file)) {
                if (size > TEXT_SECTION_THRESHOLD_BYTES) {
                    batchBytes = flushBatch(tasks, batch);
                    tasks.addAll(sectionTasks(workspace, file, rel));
                    continue;
                }
            } else if (size > LARGE_BINARY_THRESHOLD_BYTES) {
                batchBytes = flushBatch(tasks, batch);
                tasks.addAll(largeFileTasks(workspace, file, rel, size, pageExtractor, onOutput));
                continue;
            }

            if (!batch.isEmpty()
                    && (batch.size() >= BATCH_MAX_FILES || batchBytes + size > BATCH_BYTES_BUDGET)) {
                batchBytes = flushBatch(tasks, batch);
            }
            batch.add(rel);
            batchBytes += size;
        }
        flushBatch(tasks, batch);

        return new IngestPlan(List.copyOf(tasks), files.size(), totalBytes);
    }

    /**
     * 큰 바이너리 파일의 작업을 결정한다. PDF이고 전처리 훅이 있으면 페이지
     * 자산으로 분해해 페이지 판독+병합 시퀀스를 만들고, 그 외(전처리 실패 포함)는
     * 에이전트 내부 단계 처리의 단독 작업으로 폴백한다.
     */
    private static List<IngestTask> largeFileTasks(Path workspace, Path file, String rel,
                                                   long size, PageExtractor pageExtractor,
                                                   Consumer<String> onOutput) throws IOException {
        if (pageExtractor != null && isPdf(file)) {
            Path staging = stagingDir(workspace, file);
            Path pagesDir = staging.resolve("pages");
            try {
                List<PageAsset> pages = pageExtractor.extract(file, pagesDir);
                onOutput.accept("페이지 전처리 완료: " + file.getFileName()
                        + " → " + pages.size() + "페이지");
                return pageTasks(workspace, file, rel, pages, staging, pagesDir);
            } catch (Exception e) {
                onOutput.accept("[안내] 페이지 전처리 실패 — 단독 작업으로 폴백: " + e.getMessage());
            }
        }
        return List.of(largeBinaryTask(workspace, file, rel, size));
    }

    /**
     * 페이지 자산을 PAGE_GROUP_SIZE개씩 묶어 페이지 판독 패스 N개 + 병합 패스
     * 1개로 분해한다. 판독 패스는 페이지 이미지를 일차 소스로 읽고 추출 텍스트를
     * 보조로 참조해 노트를 누적한다.
     */
    private static List<IngestTask> pageTasks(Path workspace, Path file, String rel,
                                              List<PageAsset> pages, Path staging, Path pagesDir) {
        String notesRel = relative(workspace, staging.resolve("notes.md"));
        String pagesRel = relative(workspace, pagesDir);
        String fileName = file.getFileName().toString();
        int total = pages.size();

        List<IngestTask> tasks = new ArrayList<>();
        for (int start = 0; start < total; start += PAGE_GROUP_SIZE) {
            int end = Math.min(start + PAGE_GROUP_SIZE, total);
            List<PageAsset> group = pages.subList(start, end);
            String range = group.get(0).page() + "-" + group.get(group.size() - 1).page();

            StringBuilder payload = new StringBuilder();
            for (PageAsset page : group) {
                payload.append("page ").append(page.page())
                        .append(" | image: ").append(pagesRel).append('/').append(page.imageFile())
                        .append(" | text: ").append(pagesRel).append('/').append(page.textFile());
                // 추출 텍스트가 거의 없으면 스캔/그림 페이지 — 이미지 판독이 필수임을 표시
                if (page.textChars() < 30) payload.append(" (image-only)");
                payload.append('\n');
            }

            Map<String, String> options = new LinkedHashMap<>();
            options.put("ingestMode", "pages");
            options.put("source", rel);
            options.put("pageRange", range);
            options.put("pageTotal", String.valueOf(total));
            options.put("notes", notesRel);
            tasks.add(new IngestTask(
                    "페이지 판독 " + range + "/" + total + ": " + fileName,
                    payload.toString().trim(), options, null));
        }

        Map<String, String> mergeOptions = new LinkedHashMap<>();
        mergeOptions.put("ingestMode", "merge");
        mergeOptions.put("source", rel);
        tasks.add(new IngestTask("병합 수집: " + fileName, notesRel, mergeOptions, staging));
        return tasks;
    }

    /** 사람이 읽기 좋은 크기 문자열 (B/KB/MB). */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        return String.format("%.1f MB", kb / 1024.0);
    }

    /** 모인 배치를 작업으로 확정하고 누적 크기를 0으로 되돌린다. */
    private static long flushBatch(List<IngestTask> tasks, List<String> batch) {
        if (batch.isEmpty()) return 0;
        String payload = String.join("\n", batch);
        tasks.add(new IngestTask("배치 수집 (" + batch.size() + "개 파일)",
                payload, Map.of(), null));
        batch.clear();
        return 0;
    }

    /**
     * 큰 텍스트 파일을 섹션 노트 패스 N개 + 병합 패스 1개로 분해한다.
     * 섹션 파일과 노트 파일은 .llm-manager/ingest/<slug>/ 아래에 둔다.
     */
    private static List<IngestTask> sectionTasks(Path workspace, Path file, String rel)
            throws IOException {
        // 인코딩이 UTF-8이 아니어도 예외 없이 읽도록 바이트 → 문자 치환 변환
        String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        List<String> sections = splitSections(content);

        Path staging = stagingDir(workspace, file);
        String notesRel = relative(workspace, staging.resolve("notes.md"));
        String fileName = file.getFileName().toString();

        List<IngestTask> tasks = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Path sectionFile = staging.resolve(String.format("section-%02d.md", i + 1));
            Files.writeString(sectionFile, sections.get(i));

            Map<String, String> options = new LinkedHashMap<>();
            options.put("ingestMode", "section");
            options.put("source", rel);
            options.put("sectionIndex", String.valueOf(i + 1));
            options.put("sectionTotal", String.valueOf(sections.size()));
            options.put("notes", notesRel);
            tasks.add(new IngestTask(
                    "섹션 노트 " + (i + 1) + "/" + sections.size() + ": " + fileName,
                    relative(workspace, sectionFile), options, null));
        }

        Map<String, String> mergeOptions = new LinkedHashMap<>();
        mergeOptions.put("ingestMode", "merge");
        mergeOptions.put("source", rel);
        // 병합 성공 후 스테이징 정리를 다이얼로그가 수행하도록 디렉토리를 함께 전달
        tasks.add(new IngestTask("병합 수집: " + fileName, notesRel, mergeOptions, staging));
        return tasks;
    }

    /**
     * 큰 바이너리는 Java가 내용을 분할할 수 없으므로 단독 작업으로 배정한다.
     * 같은 원리(변환 → 섹션 노트 → 병합)를 에이전트가 내부 단계로 수행하도록
     * 스테이징·노트 경로를 옵션으로 전달한다.
     */
    private static IngestTask largeBinaryTask(Path workspace, Path file, String rel, long size)
            throws IOException {
        Path staging = stagingDir(workspace, file);

        Map<String, String> options = new LinkedHashMap<>();
        options.put("ingestMode", "large");
        options.put("staging", relative(workspace, staging));
        options.put("notes", relative(workspace, staging.resolve("notes.md")));
        return new IngestTask(
                "대용량 파일: " + file.getFileName() + " (" + formatBytes(size) + ")",
                rel, options, staging);
    }

    /**
     * 본문을 섹션 목표 크기 단위로 나눈다. 목표 지점 이후 첫 빈 줄 또는
     * 마크다운 헤딩 경계에서 자르고, 경계가 없으면 줄 끝, 그것도 없으면
     * (한 줄이 거대한 JSON 등) 강제 한계에서 자른다.
     */
    static List<String> splitSections(String content) {
        List<String> sections = new ArrayList<>();
        int start = 0;
        while (content.length() - start > SECTION_HARD_LIMIT_CHARS) {
            int cut = findBreak(content, start + SECTION_TARGET_CHARS,
                    start + SECTION_HARD_LIMIT_CHARS);
            sections.add(content.substring(start, cut));
            start = cut;
        }
        sections.add(content.substring(start));
        return sections;
    }

    /** target 이후 가장 가까운 자연 경계 위치를 찾는다 (hardLimit 이내). */
    private static int findBreak(String content, int target, int hardLimit) {
        int blank = content.indexOf("\n\n", target);
        int heading = content.indexOf("\n#", target);
        int best = -1;
        if (blank >= 0) best = blank + 1;
        if (heading >= 0 && (best < 0 || heading + 1 < best)) best = heading + 1;
        if (best >= 0 && best <= hardLimit) return best;
        int newline = content.indexOf('\n', target);
        if (newline >= 0 && newline < hardLimit) return newline + 1;
        return Math.min(hardLimit, content.length());
    }

    /** 파일별 스테이징 디렉토리(.llm-manager/ingest/<slug>/)를 만들어 반환한다. */
    private static Path stagingDir(Path workspace, Path file) throws IOException {
        Path staging = workspace.resolve(".llm-manager").resolve("ingest").resolve(slug(file));
        Files.createDirectories(staging);
        return staging;
    }

    /** 확장자 기준 PDF 여부 — 페이지 전처리(extract_pages.py) 적용 대상 판별. */
    private static boolean isPdf(Path file) {
        return file.getFileName().toString().toLowerCase().endsWith(".pdf");
    }

    /** 파일명(확장자 제외)을 스테이징 디렉토리명으로 쓸 수 있게 정규화한다. */
    private static String slug(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9가-힣]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "source" : slug;
    }

    /** 워크스페이스 상대 경로 (슬래시 통일 — 프롬프트·frontmatter 이식성). */
    private static String relative(Path workspace, Path path) {
        return workspace.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /** Java가 직접 읽고 분할할 수 있는 텍스트 계열 파일인지 확인한다. */
    private static boolean isTextFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }
}
