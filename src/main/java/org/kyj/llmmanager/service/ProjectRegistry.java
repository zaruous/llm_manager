/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.kyj.llmmanager.model.ProjectConfig;
import org.kyj.llmmanager.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.Locale;

public class ProjectRegistry {
    private static final Logger log = LoggerFactory.getLogger(ProjectRegistry.class);

    private final Path configFile;
    private final ObjectMapper mapper;
    private final List<ProjectConfig> projects = new ArrayList<>();

    public ProjectRegistry() {
        configFile = PlatformUtil.getAppHome().resolve("projects.json");
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void load() {
        if (!Files.exists(configFile)) return;
        try {
            ProjectConfig[] arr = mapper.readValue(configFile.toFile(), ProjectConfig[].class);
            projects.clear();
            Map<String, ProjectConfig> byPath = new LinkedHashMap<>();
            for (ProjectConfig project : Arrays.asList(arr)) {
                String key = normalizeProjectPath(project.getPath());
                if (key.isBlank()) {
                    continue;
                }
                if (byPath.containsKey(key)) {
                    byPath.remove(key);
                }
                byPath.put(key, project);
            }
            projects.addAll(byPath.values());
        } catch (IOException e) {
            log.error("Failed to load projects.json", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            mapper.writeValue(configFile.toFile(), projects);
        } catch (IOException e) {
            log.error("Failed to save projects.json", e);
        }
    }

    public List<ProjectConfig> getAll() { return new ArrayList<>(projects); }

    public void save(ProjectConfig config) {
        String pathKey = normalizeProjectPath(config.getPath());
        projects.removeIf(p -> p.getId().equals(config.getId())
                || normalizeProjectPath(p.getPath()).equals(pathKey));
        projects.add(config);
        save();
    }

    public void remove(String id) {
        projects.removeIf(p -> p.getId().equals(id));
        save();
    }

    private static String normalizeProjectPath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = Path.of(path).toAbsolutePath().normalize().toString();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("dos")
                || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }
}
