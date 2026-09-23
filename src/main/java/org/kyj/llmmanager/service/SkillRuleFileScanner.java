/*
 * 작성자 : kyj
 * 작성일 : 2026-09-24
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 스킬 로드 탭에서 소스 디렉토리를 스캔해 LLM 스킬·룰 파일 후보를 찾는다.
 *
 * 제외 판정은 소스 루트 기준 상대 경로에만 적용한다 — 소스 루트 자체가 build/ 같은
 * 이름의 디렉토리 아래에 있어도 스캔 결과가 비지 않는다.
 */
public final class SkillRuleFileScanner {

    /** 탐색하지 않을 디렉토리 이름. 하위 트리 전체를 건너뛴다. */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".gradle", ".idea");

    /** 스킬·룰 파일로 인식할 텍스트 확장자. */
    private static final List<String> SKILL_EXTENSIONS = List.of(
            ".md", ".mdc", ".json", ".yaml", ".yml", ".txt", ".toml", ".xml");

    /** 비밀값 파일로 간주할 파일명 단어 (PAT = Personal Access Token). */
    private static final Set<String> SECRET_WORDS = Set.of("token", "tokens", "pat");

    private SkillRuleFileScanner() {}

    /**
     * sourceRoot 아래의 스킬·룰 파일을 찾아 상대 경로('/' 구분) 목록으로 반환한다.
     *
     * @param sourceRoot 스캔할 소스 디렉토리
     * @return 정렬된 상대 경로 목록
     * @throws IOException 디렉토리 탐색 실패 시
     */
    public static List<String> scan(Path sourceRoot) throws IOException {
        List<String> found = new ArrayList<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 루트 자체는 이름과 무관하게 탐색한다
                if (!dir.equals(sourceRoot) && isExcludedDir(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (attrs.isRegularFile() && isSkillRuleFile(name) && !isSensitiveFile(name)) {
                    found.add(sourceRoot.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 권한 없는 파일·디렉토리 하나 때문에 전체 스캔을 중단하지 않는다
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(found);
        return found;
    }

    /**
     * 탐색에서 제외할 디렉토리 이름인지 판별한다. .llm-backup은 설치 시 만든 백업이다.
     *
     * @param dirName 디렉토리 이름 (경로 아님)
     * @return 제외 대상이면 true
     */
    static boolean isExcludedDir(String dirName) {
        return EXCLUDED_DIRS.contains(dirName) || dirName.startsWith(".llm-backup");
    }

    /**
     * 스킬·룰 파일로 인식할 확장자인지 판별한다.
     *
     * @param fileName 파일 이름
     * @return 텍스트 기반 스킬·룰 파일이면 true
     */
    static boolean isSkillRuleFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return SKILL_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /**
     * 비밀값이 담겼을 가능성이 있는 파일인지 판별한다.
     * 파일명을 단어 단위(구분자·camelCase)로 쪼개 비교한다 — 단순 부분 문자열 비교는
     * pattern.md, tokenizer.md 같은 정상 파일까지 제외했다.
     *
     * @param fileName 파일 이름
     * @return 제외해야 하면 true
     */
    static boolean isSensitiveFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.equals(".env") || lower.startsWith(".env.")
                || lower.endsWith(".key") || lower.endsWith(".pem")) {
            return true;
        }
        for (String word : fileName.split("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+")) {
            if (SECRET_WORDS.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
