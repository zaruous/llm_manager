/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class LlmSkillInstaller {
    private static final Logger log = LoggerFactory.getLogger(LlmSkillInstaller.class);
    private static final String TOOLS_JSON = "/llm-skills/tools.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private List<LlmTool> tools;

    public List<LlmTool> loadTools() {
        if (tools != null) return tools;
        try (InputStream is = getClass().getResourceAsStream(TOOLS_JSON)) {
            if (is == null) {
                log.warn("tools.json not found");
                return Collections.emptyList();
            }
            tools = mapper.readValue(is, new TypeReference<>() {});
            log.info("Loaded {} tools", tools.size());
        } catch (IOException e) {
            log.error("Failed to load tools.json", e);
            tools = Collections.emptyList();
        }
        return tools;
    }

    public record InstallResult(List<String> installed, List<String> skipped, List<String> errors) {}

    public InstallResult install(ProjectConfig project, boolean overwrite) {
        List<String> installed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Path projectRoot = Path.of(project.getPath());

        for (LlmTool tool : loadTools()) {
            if (!project.getEnabledToolIds().contains(tool.getId())) continue;

            for (SkillPack pack : tool.getPacks()) {
                if (!project.getEnabledPackIds().contains(pack.getId())) continue;

                for (SkillFile sf : pack.getFiles()) {
                    try {
                        Path target = projectRoot.resolve(sf.getTargetPath());
                        if (Files.exists(target) && !overwrite) {
                            skipped.add(sf.getTargetPath() + " (기존 파일 유지)");
                            continue;
                        }

                        String content = readResource(sf.getResourcePath());
                        if (sf.isTemplate()) {
                            content = applyVariables(content, project.getVariables());
                        }

                        Files.createDirectories(target.getParent());
                        Files.writeString(target, content, StandardCharsets.UTF_8);
                        installed.add(sf.getTargetPath());
                        log.info("Installed: {}", target);

                    } catch (Exception e) {
                        log.error("Failed to install {}", sf.getTargetPath(), e);
                        errors.add(sf.getTargetPath() + ": " + e.getMessage());
                    }
                }
            }
        }
        return new InstallResult(installed, skipped, errors);
    }

    /** 설치 전 기존 파일을 .llm-backup/ 아래에 백업 */
    public void backup(ProjectConfig project) throws IOException {
        Path projectRoot = Path.of(project.getPath());
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupDir = projectRoot.resolve(".llm-backup").resolve(timestamp);

        for (LlmTool tool : loadTools()) {
            if (!project.getEnabledToolIds().contains(tool.getId())) continue;
            for (SkillPack pack : tool.getPacks()) {
                if (!project.getEnabledPackIds().contains(pack.getId())) continue;
                for (SkillFile sf : pack.getFiles()) {
                    Path original = projectRoot.resolve(sf.getTargetPath());
                    if (Files.exists(original)) {
                        Path dest = backupDir.resolve(sf.getTargetPath());
                        Files.createDirectories(dest.getParent());
                        Files.copy(original, dest, StandardCopyOption.REPLACE_EXISTING);
                        log.info("Backed up: {}", sf.getTargetPath());
                    }
                }
            }
        }
    }

    /** 선택된 팩의 설치 대상 파일 목록 미리보기 */
    public List<Map<String, String>> preview(ProjectConfig project) {
        Path projectRoot = Path.of(project.getPath());
        List<Map<String, String>> result = new ArrayList<>();

        for (LlmTool tool : loadTools()) {
            if (!project.getEnabledToolIds().contains(tool.getId())) continue;
            for (SkillPack pack : tool.getPacks()) {
                if (!project.getEnabledPackIds().contains(pack.getId())) continue;
                for (SkillFile sf : pack.getFiles()) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("tool", tool.getDisplayName());
                    entry.put("pack", pack.getName());
                    entry.put("target", sf.getTargetPath());
                    entry.put("status", Files.exists(projectRoot.resolve(sf.getTargetPath()))
                            ? "덮어쓰기" : "신규");
                    result.add(entry);
                }
            }
        }
        return result;
    }

    /** 스킬 파일의 내용을 읽어 반환 */
    public String readSkillContent(SkillFile sf, Map<String, String> variables) {
        try {
            String content = readResource(sf.getResourcePath());
            return sf.isTemplate() ? applyVariables(content, variables) : content;
        } catch (IOException e) {
            return "파일을 읽을 수 없습니다: " + e.getMessage();
        }
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
