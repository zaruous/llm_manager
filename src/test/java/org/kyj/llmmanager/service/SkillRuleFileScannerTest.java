/*
 * 작성자 : kyj
 * 작성일 : 2026-09-24
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 스킬 로드 탭 스캔 필터가 정상 파일을 오탐으로 제외하지 않는지 검증한다.
 */
class SkillRuleFileScannerTest {

    @Test
    void scan_sourceRootUnderExcludedDirName_stillFindsFiles(@TempDir Path tempDir) throws Exception {
        // 이전 구현은 절대 경로의 모든 구성 요소를 검사해 build/ 아래 소스 루트에서 0개를 반환했다
        Path root = tempDir.resolve("build").resolve("skills");
        write(root.resolve(".cursor/rules/000-general.mdc"));
        write(root.resolve("CLAUDE.md"));

        assertEquals(List.of(".cursor/rules/000-general.mdc", "CLAUDE.md"),
                SkillRuleFileScanner.scan(root));
    }

    @Test
    void scan_skipsNoiseDirsBackupsAndNonSkillFiles(@TempDir Path root) throws Exception {
        write(root.resolve("rules/a.md"));
        write(root.resolve("node_modules/pkg/README.md"));
        write(root.resolve("sub/build/out.md"));
        write(root.resolve(".git/HEAD.txt"));
        write(root.resolve(".llm-backup/20260101_000000/CLAUDE.md"));
        write(root.resolve("src/Main.java"));

        assertEquals(List.of("rules/a.md"), SkillRuleFileScanner.scan(root));
    }

    @Test
    void isSensitiveFile_matchesWordsNotSubstrings() {
        assertFalse(SkillRuleFileScanner.isSensitiveFile("pattern.md"));
        assertFalse(SkillRuleFileScanner.isSensitiveFile("path-rules.mdc"));
        assertFalse(SkillRuleFileScanner.isSensitiveFile("compatibility.md"));
        assertFalse(SkillRuleFileScanner.isSensitiveFile("tokenizer.md"));
        assertFalse(SkillRuleFileScanner.isSensitiveFile("PATTERNS.md"));

        assertTrue(SkillRuleFileScanner.isSensitiveFile("github-pat.txt"));
        assertTrue(SkillRuleFileScanner.isSensitiveFile("api_token.json"));
        assertTrue(SkillRuleFileScanner.isSensitiveFile("githubToken.json"));
        assertTrue(SkillRuleFileScanner.isSensitiveFile("access-tokens.yml"));
        assertTrue(SkillRuleFileScanner.isSensitiveFile(".env"));
        assertTrue(SkillRuleFileScanner.isSensitiveFile(".env.local"));
        assertTrue(SkillRuleFileScanner.isSensitiveFile("server.pem"));
    }

    private static void write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }
}
