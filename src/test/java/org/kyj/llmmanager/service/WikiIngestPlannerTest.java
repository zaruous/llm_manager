/*
 * 작성자 : kyj
 * 작성일 : 2026-06-13
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.service.WikiIngestPlanner.IngestPlan;
import org.kyj.llmmanager.service.WikiIngestPlanner.IngestTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WikiIngestPlanner의 작업 분해 규칙을 검증한다 —
 * 작은 파일 배치, 큰 텍스트의 섹션+병합, 큰 바이너리 단독 작업.
 */
class WikiIngestPlannerTest {

    @Test
    void smallFilesAreBatchedByCount(@TempDir Path workspace) throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Path file = workspace.resolve("raw/articles/doc-" + i + ".md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# Doc " + i + "\n\ncontent");
            files.add(file);
        }

        IngestPlan plan = WikiIngestPlanner.plan(files, workspace);

        // 12개 → 5 + 5 + 2 배치
        assertEquals(3, plan.tasks().size());
        assertTrue(plan.tasks().stream().allMatch(t -> t.options().isEmpty()),
                "배치 작업은 모드 옵션이 없어야 함");
        assertEquals(12, plan.fileCount());
        // payload는 워크스페이스 상대 경로(슬래시) 목록
        assertTrue(plan.tasks().get(0).payload().startsWith("raw/articles/"));
    }

    @Test
    void batchSplitsWhenSizeBudgetExceeded(@TempDir Path workspace) throws IOException {
        // 배치 크기 예산(512KB)을 넘기는 180KB 파일 4개 → 2개씩 두 배치
        List<Path> files = new ArrayList<>();
        String big = "x".repeat(180 * 1024);
        for (int i = 0; i < 4; i++) {
            Path file = workspace.resolve("raw/articles/big-" + i + ".md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, big);
            files.add(file);
        }

        IngestPlan plan = WikiIngestPlanner.plan(files, workspace);

        assertEquals(2, plan.tasks().size());
    }

    @Test
    void largeTextFileBecomesSectionAndMergeTasks(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("raw/articles/huge.md");
        Files.createDirectories(file.getParent());
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 12_000; i++) {
            content.append("# Heading ").append(i).append('\n')
                    .append("Some body line for section content.\n\n");
        }
        Files.writeString(file, content.toString());

        IngestPlan plan = WikiIngestPlanner.plan(List.of(file), workspace);
        List<IngestTask> tasks = plan.tasks();

        assertTrue(tasks.size() >= 3, "섹션 여러 개 + 병합이어야 함: " + tasks.size());

        // 마지막 작업은 병합, 그 앞은 전부 섹션 노트
        IngestTask merge = tasks.get(tasks.size() - 1);
        assertEquals("merge", merge.options().get("ingestMode"));
        assertEquals("raw/articles/huge.md", merge.options().get("source"));
        assertNotNull(merge.stagingDir(), "병합 작업은 정리할 스테이징 디렉토리를 가져야 함");

        for (int i = 0; i < tasks.size() - 1; i++) {
            IngestTask section = tasks.get(i);
            assertEquals("section", section.options().get("ingestMode"));
            assertEquals(String.valueOf(i + 1), section.options().get("sectionIndex"));
            // 섹션 파일이 스테이징에 실제로 기록되어야 함
            assertTrue(Files.isRegularFile(workspace.resolve(section.payload())),
                    "섹션 파일 없음: " + section.payload());
        }
    }

    @Test
    void largeBinaryGetsDedicatedStagedTask(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("raw/articles/big.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[5 * 1024 * 1024]);

        // 전처리 훅 없이 — 단독 작업 모드
        IngestPlan plan = WikiIngestPlanner.plan(List.of(file), workspace);

        assertEquals(1, plan.tasks().size());
        IngestTask task = plan.tasks().get(0);
        assertEquals("large", task.options().get("ingestMode"));
        assertEquals("raw/articles/big.pdf", task.payload());
        assertNotNull(task.options().get("staging"));
        assertNotNull(task.options().get("notes"));
        assertNotNull(task.stagingDir());
    }

    @Test
    void largePdfWithExtractorBecomesPageTasks(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("raw/articles/big.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[5 * 1024 * 1024]);

        // 가짜 전처리 훅 — 12페이지 자산 생성 (3번 페이지는 이미지 전용)
        WikiIngestPlanner.PageExtractor fake = (source, outputDir) -> {
            Files.createDirectories(outputDir);
            List<WikiIngestPlanner.PageAsset> pages = new ArrayList<>();
            for (int n = 1; n <= 12; n++) {
                String text = "page-%04d.md".formatted(n);
                String image = "page-%04d.png".formatted(n);
                Files.writeString(outputDir.resolve(text), "# Page " + n + "\n\ncontent");
                Files.write(outputDir.resolve(image), new byte[]{0});
                pages.add(new WikiIngestPlanner.PageAsset(n, text, image, n == 3 ? 0 : 100));
            }
            return pages;
        };

        IngestPlan plan = WikiIngestPlanner.plan(List.of(file), workspace, fake, line -> { });
        List<IngestTask> tasks = plan.tasks();

        // 12페이지 → 5+5+2 판독 패스 3개 + 병합 1개
        assertEquals(4, tasks.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("pages", tasks.get(i).options().get("ingestMode"));
            assertEquals("raw/articles/big.pdf", tasks.get(i).options().get("source"));
        }
        assertEquals("1-5", tasks.get(0).options().get("pageRange"));
        assertEquals("11-12", tasks.get(2).options().get("pageRange"));
        // 텍스트가 없는 페이지는 이미지 판독 필수 표시
        assertTrue(tasks.get(0).payload().contains("(image-only)"));
        assertTrue(tasks.get(0).payload().contains("image:"));

        IngestTask merge = tasks.get(3);
        assertEquals("merge", merge.options().get("ingestMode"));
        assertNotNull(merge.stagingDir(), "병합 작업이 페이지 스테이징 정리를 맡아야 함");
    }

    @Test
    void extractorFailureFallsBackToLargeTask(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("raw/articles/broken.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[5 * 1024 * 1024]);

        WikiIngestPlanner.PageExtractor failing = (source, outputDir) -> {
            throw new IOException("PyMuPDF not installed");
        };

        List<String> logs = new ArrayList<>();
        IngestPlan plan = WikiIngestPlanner.plan(List.of(file), workspace, failing, logs::add);

        assertEquals(1, plan.tasks().size());
        assertEquals("large", plan.tasks().get(0).options().get("ingestMode"));
        assertTrue(logs.stream().anyMatch(l -> l.contains("폴백")), "폴백 안내가 출력되어야 함");
    }

    @Test
    void splitSectionsHandlesGiantSingleLine() {
        // 줄바꿈 없는 거대 한 줄(JSON 등)도 강제 한계에서 잘려야 함
        String oneLine = "a".repeat(WikiIngestPlanner.SECTION_HARD_LIMIT_CHARS * 3);
        List<String> sections = WikiIngestPlanner.splitSections(oneLine);
        assertTrue(sections.size() >= 3);
        assertTrue(sections.stream()
                .allMatch(s -> s.length() <= WikiIngestPlanner.SECTION_HARD_LIMIT_CHARS));
    }
}
