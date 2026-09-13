/*
 * 작성자 : kyj
 * 작성일 : 2026-06-21
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.model.ProjectConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiki 대화 수집 스킬의 Codex 설치 경로와 안전 정책을 검증한다.
 */
class WikiConversationSkillTest {

    @Test
    void codexPack_installsSkillAndExplicitInvocationPolicy(@TempDir Path tempDir)
            throws Exception {
        AppSettings settings = new AppSettings();
        settings.setSkillLibraryDbProvider("sqlite");
        settings.setSkillLibraryDbUrl(
                "jdbc:sqlite:" + tempDir.resolve("skill-library.sqlite"));

        try (LlmSkillLibraryRepository repository =
                     new LlmSkillLibraryRepository(settings)) {
            LlmSkillInstaller installer = new LlmSkillInstaller(repository);
            ProjectConfig project = new ProjectConfig();
            project.setPath(tempDir.resolve("workspace").toString());
            project.setEnabledToolIds(List.of("wiki-agent"));
            project.setEnabledPackIds(List.of("wiki-codex"));

            LlmSkillInstaller.InstallResult result = installer.install(project, true);

            Path skill = Path.of(project.getPath()).resolve(
                    ".agents/skills/wiki-ingest-conversation/SKILL.md");
            Path metadata = Path.of(project.getPath()).resolve(
                    ".agents/skills/wiki-ingest-conversation/agents/openai.yaml");
            assertTrue(result.errors().isEmpty(), () -> String.join(", ", result.errors()));
            assertTrue(Files.isRegularFile(skill));
            assertTrue(Files.isRegularFile(metadata));
            assertTrue(Files.readString(skill).contains("wiki_ingest"));
            assertTrue(Files.readString(skill).contains("Do not invoke automatically"));
            assertTrue(Files.readString(metadata)
                    .contains("allow_implicit_invocation: false"));
            assertTrue(Files.readString(metadata).contains("value: \"wiki-mcp\""));
        }
    }
}
