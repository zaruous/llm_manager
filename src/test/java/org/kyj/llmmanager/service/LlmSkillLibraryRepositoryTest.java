package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.model.LlmTool;
import org.kyj.llmmanager.model.SkillFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSkillLibraryRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void saveFilesAndReadContentRoundTripWithSqlite() throws Exception {
        Path sourceRoot = tempDir.resolve("source");
        Files.createDirectories(sourceRoot.resolve("cursor/rules"));
        Files.writeString(sourceRoot.resolve("cursor/rules/java.mdc"), "Always write tests.");

        AppSettings settings = new AppSettings();
        settings.setSkillLibraryDbProvider("sqlite");
        settings.setSkillLibraryDbUrl("jdbc:sqlite:" + tempDir.resolve("skills.sqlite"));

        try (LlmSkillLibraryRepository repo = new LlmSkillLibraryRepository(settings)) {
            int saved = repo.saveFiles(sourceRoot, List.of("cursor/rules/java.mdc"));
            assertEquals(1, saved);

            List<LlmTool> tools = repo.loadTools();
            assertEquals(1, tools.size());
            assertEquals("cursor-library", tools.get(0).getId());
            assertEquals("cursor-library-loaded", tools.get(0).getPacks().get(0).getId());

            List<SkillFile> files = tools.get(0).getPacks().get(0).getFiles();
            assertEquals(1, files.size());
            assertEquals(".cursor/rules/java.mdc", files.get(0).getTargetPath());
            assertFalse(files.get(0).isTemplate());
            assertEquals("Always write tests.", repo.readContent(files.get(0).getLibraryFileId()));
        }
    }

    @Test
    void defaultSqliteLocationKeepsV101CursorLibraryPath() {
        AppSettings settings = new AppSettings();
        settings.setSkillLibraryDbProvider("sqlite");
        settings.setSkillLibraryDbUrl("");

        try (LlmSkillLibraryRepository repo = new LlmSkillLibraryRepository(settings)) {
            String normalized = repo.getDatabaseLocation().replace('\\', '/');
            assertTrue(normalized.endsWith("/lib/cursor/skill-library.sqlite"),
                    () -> "unexpected default SQLite location: " + repo.getDatabaseLocation());
        }
    }
}
