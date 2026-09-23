/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 내장 스킬 팩(tools.json)과 DB 라이브러리 스킬을 프로젝트 디렉토리에 설치한다.
 *
 * 설치·백업·미리보기·충돌 검사는 모두 {@link #selectedFiles(ProjectConfig)}가 고른
 * 동일한 파일 목록을 기준으로 동작한다.
 */
public class LlmSkillInstaller {
    private static final Logger log = LoggerFactory.getLogger(LlmSkillInstaller.class);
    private static final String TOOLS_JSON = "/llm-skills/tools.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmSkillLibraryRepository libraryRepository;

    /** tools.json + DB 라이브러리 병합 결과 캐시. null이면 다음 loadTools()에서 다시 읽는다. */
    private List<LlmTool> tools;

    public LlmSkillInstaller(LlmSkillLibraryRepository libraryRepository) {
        this.libraryRepository = libraryRepository;
    }

    /**
     * 내장 tools.json과 DB 라이브러리의 도구 목록을 합쳐 반환한다. 결과는 캐시된다.
     *
     * @return 설치 가능한 도구 목록 (tools.json 순서 → DB 라이브러리 순)
     */
    public List<LlmTool> loadTools() {
        if (tools != null) return tools;
        List<LlmTool> loaded = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream(TOOLS_JSON)) {
            if (is == null) {
                log.warn("tools.json not found");
            } else {
                loaded.addAll(mapper.readValue(is, new TypeReference<List<LlmTool>>() {}));
            }
        } catch (IOException e) {
            log.error("Failed to load tools.json", e);
        }
        loaded.addAll(libraryRepository.loadTools());
        tools = loaded;
        log.info("Loaded {} tools", tools.size());
        return tools;
    }

    /** 도구 캐시를 비워 다음 loadTools()가 DB 라이브러리 변경분을 반영하게 한다. */
    public void refreshLibrary() {
        libraryRepository.refresh();
        tools = null;
    }

    public LlmSkillLibraryRepository getLibraryRepository() {
        return libraryRepository;
    }

    /** DB 커넥션 풀을 닫는다. 앱 종료 시 호출. */
    public void shutdown() {
        libraryRepository.close();
    }

    public record InstallResult(List<String> installed, List<String> skipped, List<String> errors) {}

    /** 설치 대상으로 선택된 파일 1건과 그 파일이 속한 도구·팩. */
    public record SelectedFile(LlmTool tool, SkillPack pack, SkillFile file) {
        /** 화면 표시용 "도구 / 팩" 라벨. */
        public String label() {
            return tool.getDisplayName() + " / " + pack.getName();
        }
    }

    /**
     * 선택된 도구·팩의 파일을 프로젝트 경로에 쓴다.
     * 같은 대상 경로가 여러 팩에서 선택된 경우 먼저 나온 파일만 쓰고 나머지는 오류로 보고한다.
     *
     * @param project   설치 대상 프로젝트 설정
     * @param overwrite false면 이미 존재하는 파일은 건너뛴다
     * @return 설치·건너뜀·오류 파일 목록
     */
    public InstallResult install(ProjectConfig project, boolean overwrite) {
        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Path projectRoot = Path.of(project.getPath());
        Set<String> written = new HashSet<>();

        for (SelectedFile sel : selectedFiles(project)) {
            SkillFile sf = sel.file();
            try {
                // 한 번의 설치에서 같은 파일을 두 팩이 번갈아 덮어쓰지 않도록 첫 파일만 쓴다
                if (!written.add(targetKey(sf.getTargetPath()))) {
                    errors.add(sf.getTargetPath() + ": 다른 팩과 대상 경로가 겹쳐 건너뜀 (" + sel.label() + ")");
                    continue;
                }
                Path target = projectRoot.resolve(sf.getTargetPath());
                if (Files.exists(target) && !overwrite) {
                    skipped.add(sf.getTargetPath() + " (기존 파일 유지)");
                    continue;
                }

                // 읽기 실패 시 오류 문구가 파일 내용으로 기록되지 않도록 예외를 던지는 버전을 쓴다
                String content = readSkillContentStrict(sf, project.getVariables());

                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8);
                installed.add(sf.getTargetPath());
                log.info("Installed: {}", target);

            } catch (Exception e) {
                log.error("Failed to install {}", sf.getTargetPath(), e);
                errors.add(sf.getTargetPath() + ": " + e.getMessage());
            }
        }
        return new InstallResult(installed, skipped, errors);
    }

    /**
     * 설치 전 기존 파일을 {@code .llm-backup/<yyyyMMdd_HHmmss>/} 아래에 복사한다.
     *
     * @param project 백업할 프로젝트 설정
     * @throws IOException 복사 실패 시
     */
    public void backup(ProjectConfig project) throws IOException {
        Path projectRoot = Path.of(project.getPath());
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupDir = projectRoot.resolve(".llm-backup").resolve(timestamp);

        for (SelectedFile sel : selectedFiles(project)) {
            String targetPath = sel.file().getTargetPath();
            Path original = projectRoot.resolve(targetPath);
            if (Files.exists(original)) {
                Path dest = backupDir.resolve(targetPath);
                Files.createDirectories(dest.getParent());
                Files.copy(original, dest, StandardCopyOption.REPLACE_EXISTING);
                log.info("Backed up: {}", targetPath);
            }
        }
    }

    /**
     * 선택된 팩의 설치 대상 파일 목록을 반환한다.
     * status는 "신규" / "덮어쓰기", 프로젝트 경로가 비어 있으면 "경로 미지정".
     *
     * @param project 미리볼 프로젝트 설정
     * @return tool·pack·target·status 키를 가진 항목 목록
     */
    public List<Map<String, String>> preview(ProjectConfig project) {
        String path = project.getPath();
        boolean hasPath = path != null && !path.isBlank();
        List<Map<String, String>> result = new ArrayList<>();

        for (SelectedFile sel : selectedFiles(project)) {
            String targetPath = sel.file().getTargetPath();
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("tool", sel.tool().getDisplayName());
            entry.put("pack", sel.pack().getName());
            entry.put("target", targetPath);
            // 경로가 비어 있으면 Path.of("")가 앱 실행 디렉토리로 해석돼 엉뚱한 판정이 나온다
            entry.put("status", !hasPath ? "경로 미지정"
                    : Files.exists(Path.of(path).resolve(targetPath)) ? "덮어쓰기" : "신규");
            result.add(entry);
        }
        return result;
    }

    /**
     * 선택된 팩들 사이에서 같은 대상 경로로 설치되는 파일을 찾는다.
     * 예: Claude Code 기본 규칙과 Wiki Agent 팩이 둘 다 CLAUDE.md를 쓰는 경우.
     *
     * @param project 검사할 프로젝트 설정
     * @return 대상 경로 → 해당 경로를 쓰는 "도구 / 팩" 라벨 목록 (2개 이상인 경로만)
     */
    public Map<String, List<String>> findConflicts(ProjectConfig project) {
        Map<String, String> displayPath = new LinkedHashMap<>();
        Map<String, List<String>> owners = new LinkedHashMap<>();
        for (SelectedFile sel : selectedFiles(project)) {
            String key = targetKey(sel.file().getTargetPath());
            displayPath.putIfAbsent(key, sel.file().getTargetPath());
            owners.computeIfAbsent(key, k -> new ArrayList<>()).add(sel.label());
        }

        Map<String, List<String>> conflicts = new LinkedHashMap<>();
        owners.forEach((key, labels) -> {
            if (labels.size() > 1) conflicts.put(displayPath.get(key), labels);
        });
        return conflicts;
    }

    /**
     * 프로젝트 설정에서 선택된 도구·팩에 속한 파일을 tools.json 순서대로 모은다.
     *
     * @param project 선택 정보가 담긴 프로젝트 설정
     * @return 설치 대상 파일 목록
     */
    public List<SelectedFile> selectedFiles(ProjectConfig project) {
        List<SelectedFile> result = new ArrayList<>();
        for (LlmTool tool : loadTools()) {
            if (!project.getEnabledToolIds().contains(tool.getId())) continue;
            for (SkillPack pack : tool.getPacks()) {
                if (!project.getEnabledPackIds().contains(pack.getId())) continue;
                for (SkillFile sf : pack.getFiles()) {
                    result.add(new SelectedFile(tool, pack, sf));
                }
            }
        }
        return result;
    }

    /**
     * 대상 경로 비교용 키. 구분자·"./"를 정규화하고,
     * Windows·macOS 기본 파일시스템은 대소문자를 구분하지 않으므로 소문자로 맞춘다.
     */
    private static String targetKey(String targetPath) {
        String key = Path.of(targetPath).normalize().toString().replace('\\', '/');
        return PlatformUtil.isWindows() || PlatformUtil.isMac()
                ? key.toLowerCase(Locale.ROOT) : key;
    }

    /**
     * 화면 미리보기용으로 스킬 파일 내용을 읽는다. 실패해도 예외 대신 안내 문자열을 반환하므로
     * 파일에 쓰는 용도로는 {@link #readSkillContentStrict}를 사용해야 한다.
     *
     * @param sf        읽을 스킬 파일 (DB 라이브러리 id 또는 클래스패스 리소스)
     * @param variables 템플릿 치환 변수
     * @return 파일 내용. 읽기 실패 시 오류 안내 문자열
     */
    public String readSkillContent(SkillFile sf, Map<String, String> variables) {
        try {
            return readSkillContentStrict(sf, variables);
        } catch (IOException e) {
            return "파일을 읽을 수 없습니다: " + e.getMessage();
        }
    }

    /**
     * 스킬 파일 내용을 읽는다. template 파일은 {{변수}}를 치환한다.
     *
     * @param sf        읽을 스킬 파일 (DB 라이브러리 id 또는 클래스패스 리소스)
     * @param variables 템플릿 치환 변수
     * @return 파일 내용
     * @throws IOException 리소스가 없거나 DB 읽기에 실패한 경우
     */
    public String readSkillContentStrict(SkillFile sf, Map<String, String> variables) throws IOException {
        String content = sf.getLibraryFileId() != null
                ? libraryRepository.readContent(sf.getLibraryFileId())
                : readResource(sf.getResourcePath());
        return sf.isTemplate() ? applyVariables(content, variables) : content;
    }

    private String readResource(String path) throws IOException {
        String cp = path.startsWith("/") ? path : "/" + path;
        try (InputStream is = getClass().getResourceAsStream(cp)) {
            if (is == null) throw new IOException("Resource not found: " + cp);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String applyVariables(String content, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) {
            content = content.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return content;
    }
}
