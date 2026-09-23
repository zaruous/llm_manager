/*
 * 작성자 : kyj
 * 작성일 : 2026-09-24
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.model.ProjectConfig;
import org.kyj.llmmanager.model.SkillFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스킬 설치의 대상 경로 충돌 검출과 덮어쓰기 옵션 동작을 검증한다. 실제 tools.json을 사용한다.
 */
class LlmSkillInstallerTest {

    @TempDir
    Path tempDir;

    private LlmSkillLibraryRepository repository;
    private LlmSkillInstaller installer;
    private Path projectDir;

    @BeforeEach
    void setUp() throws IOException {
        AppSettings settings = new AppSettings();
        settings.setSkillLibraryDbProvider("sqlite");
        settings.setSkillLibraryDbUrl("jdbc:sqlite:" + tempDir.resolve("skill-library.sqlite"));
        repository = new LlmSkillLibraryRepository(settings);
        installer = new LlmSkillInstaller(repository);
        projectDir = Files.createDirectories(tempDir.resolve("project"));
    }

    @AfterEach
    void tearDown() {
        repository.close();
    }

    @Test
    void findConflicts_claudeBaseAndWikiClaude_bothWriteClaudeMd() {
        ProjectConfig project = project(List.of("claude", "wiki-agent"),
                List.of("claude-base", "wiki-claude"));

        Map<String, List<String>> conflicts = installer.findConflicts(project);

        assertEquals(List.of("CLAUDE.md"), List.copyOf(conflicts.keySet()));
        assertEquals(2, conflicts.get("CLAUDE.md").size());
    }

    @Test
    void findConflicts_packOfUncheckedToolIsIgnored() {
        // 팩 체크박스는 기본 선택이므로, 도구가 해제돼 있으면 그 팩은 충돌 대상이 아니다
        ProjectConfig project = project(List.of("claude"),
                List.of("claude-base", "wiki-claude"));

        assertTrue(installer.findConflicts(project).isEmpty());
    }

    @Test
    void install_duplicateTarget_writesFirstPackOnlyAndReportsError() throws IOException {
        ProjectConfig project = project(List.of("claude", "wiki-agent"),
                List.of("claude-base", "wiki-claude"));

        LlmSkillInstaller.InstallResult result = installer.install(project, true);

        String claudeBase = installer.readSkillContentStrict(
                installer.selectedFiles(project).get(0).file(), project.getVariables());
        assertEquals(claudeBase, Files.readString(projectDir.resolve("CLAUDE.md")));
        assertEquals(1, result.errors().stream().filter(e -> e.startsWith("CLAUDE.md")).count(),
                () -> String.join(", ", result.errors()));
    }

    @Test
    void install_withoutOverwrite_keepsExistingFiles() throws IOException {
        Files.writeString(projectDir.resolve("CLAUDE.md"), "내 규칙");
        ProjectConfig project = project(List.of("claude"), List.of("claude-base"));

        LlmSkillInstaller.InstallResult result = installer.install(project, false);

        assertEquals("내 규칙", Files.readString(projectDir.resolve("CLAUDE.md")));
        assertTrue(result.installed().isEmpty());
        assertEquals(1, result.skipped().size());
    }

    @Test
    void preview_blankPath_doesNotJudgeAgainstWorkingDirectory() {
        ProjectConfig project = project(List.of("claude"), List.of("claude-base"));
        project.setPath("");

        assertEquals("경로 미지정", installer.preview(project).get(0).get("status"));
    }

    @Test
    void readSkillContentStrict_missingResource_throws() {
        SkillFile missing = new SkillFile();
        missing.setResourcePath("llm-skills/does-not-exist.md");
        missing.setTargetPath("x.md");

        assertThrows(IOException.class, () -> installer.readSkillContentStrict(missing, Map.of()));
        assertTrue(installer.readSkillContent(missing, Map.of()).startsWith("파일을 읽을 수 없습니다"));
    }

    private ProjectConfig project(List<String> toolIds, List<String> packIds) {
        ProjectConfig project = new ProjectConfig();
        project.setPath(projectDir.toString());
        project.setEnabledToolIds(toolIds);
        project.setEnabledPackIds(packIds);
        project.setVariables(Map.of("projectName", "demo", "language", "Java", "author", "tester"));
        return project;
    }
}
