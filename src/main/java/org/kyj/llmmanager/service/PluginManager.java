/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.model.plugin.PluginCommand;
import org.kyj.llmmanager.model.plugin.PluginManifest;
import org.kyj.llmmanager.model.plugin.PluginSettingsTab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kyj.llmmanager.model.ServiceDefinition;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads local declarative plugins from plugin directories.
 */
public class PluginManager {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path pluginDir;
    private final List<LoadedPlugin> plugins = new ArrayList<>();

    public PluginManager() {
        this.pluginDir = resolvePluginDir();
    }

    public void load() {
        plugins.clear();
        if (!Files.isDirectory(pluginDir)) {
            log.info("Plugin dir not found: {}", pluginDir.toAbsolutePath());
            return;
        }

        Set<String> pluginIds = new HashSet<>();
        Set<String> commandIds = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                LoadedPlugin loaded = loadOne(dir);
                validate(loaded, pluginIds, commandIds);
                plugins.add(loaded);
                if (loaded.isValid()) {
                    log.info("Loaded plugin: {} ({})",
                            loaded.getManifest().getName(), loaded.getManifest().getId());
                } else {
                    log.warn("Invalid plugin {}: {}", dir.getFileName(), loaded.getErrors());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan plugin dir: {}", e.getMessage());
        }
    }

    public List<LoadedPlugin> getPlugins() {
        return new ArrayList<>(plugins);
    }

    public LoadedPlugin findPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) return null;
        return plugins.stream()
                .filter(LoadedPlugin::isValid)
                .filter(plugin -> plugin.getManifest() != null)
                .filter(plugin -> pluginId.equals(plugin.getManifest().getId()))
                .findFirst()
                .orElse(null);
    }

    public List<PluginSettingsTabContribution> getSettingsTabs() {
        List<PluginSettingsTabContribution> result = new ArrayList<>();
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isValid()) continue;
            PluginManifest manifest = plugin.getManifest();
            for (PluginSettingsTab tab : manifest.getContributes().getSettingsTabs()) {
                if (tab.getId() == null || tab.getId().isBlank()) continue;
                if (!"schema".equalsIgnoreCase(tab.getType())) continue;
                result.add(new PluginSettingsTabContribution(manifest.getId(), manifest.getName(), tab));
            }
        }
        result.sort(Comparator
                .comparingInt((PluginSettingsTabContribution c) -> c.tab().getOrder())
                .thenComparing(c -> c.tab().getTitle()));
        return result;
    }

    /**
     * 서비스 레지스트리 없이 커맨드 목록을 반환한다. linkedServiceId는 항상 null.
     * PluginCommandRunDialog·PluginCommandExecutor처럼 서비스 연동이 불필요한 호출 지점용.
     */
    public List<PluginCommandContribution> getCommands() {
        return getCommands(null);
    }

    /**
     * 서비스 레지스트리를 참조해 linkedServiceId가 해석된 커맨드 기여 목록을 반환한다.
     * 플러그인 manifest의 linkedServiceType과 일치하는 packId를 가진 서비스가 정확히 1개일 때
     * 해당 서비스의 id를 linkedServiceId로 채운다. 0개·2개 이상이면 null을 유지한다.
     *
     * @param serviceRegistry 서비스 레지스트리 (null이면 linkedServiceId를 해석하지 않음)
     * @return 커맨드 기여 목록 (플러그인명·커맨드 제목 순 정렬)
     */
    public List<PluginCommandContribution> getCommands(ServiceRegistry serviceRegistry) {
        List<PluginCommandContribution> result = new ArrayList<>();
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isValid()) continue;
            PluginManifest manifest = plugin.getManifest();
            String linkedServiceId = resolveLinkedServiceId(manifest, serviceRegistry);
            for (PluginCommand command : manifest.getContributes().getCommands()) {
                if (command.getId() == null || command.getId().isBlank()) continue;
                result.add(new PluginCommandContribution(
                        manifest.getId(), manifest.getName(), command, linkedServiceId));
            }
        }
        result.sort(Comparator
                .comparing((PluginCommandContribution c) -> c.pluginName())
                .thenComparing(c -> c.command().getTitle()));
        return result;
    }

    /**
     * manifest의 linkedServiceType으로 레지스트리에서 서비스를 탐색해 id를 반환한다.
     * 등록된 서비스가 정확히 1개인 경우에만 연결한다.
     */
    private String resolveLinkedServiceId(PluginManifest manifest, ServiceRegistry serviceRegistry) {
        if (serviceRegistry == null) return null;
        String serviceType = manifest.getLinkedServiceType();
        if (serviceType == null || serviceType.isBlank()) return null;
        List<ServiceDefinition> found = serviceRegistry.findByPackId(serviceType);
        return found.size() == 1 ? found.get(0).getId() : null;
    }

    public Path getPluginDir() {
        return pluginDir;
    }

    private LoadedPlugin loadOne(Path dir) {
        LoadedPlugin loaded = new LoadedPlugin();
        loaded.setDirectory(dir);
        Path manifestFile = dir.resolve("plugin.json");
        if (!Files.isRegularFile(manifestFile)) {
            loaded.getErrors().add("plugin.json 없음");
            return loaded;
        }
        try {
            loaded.setManifest(mapper.readValue(manifestFile.toFile(), PluginManifest.class));
        } catch (IOException e) {
            loaded.getErrors().add("manifest 파싱 실패: " + e.getMessage());
        }
        return loaded;
    }

    private void validate(LoadedPlugin loaded, Set<String> pluginIds, Set<String> commandIds) {
        PluginManifest manifest = loaded.getManifest();
        if (manifest == null) {
            loaded.setValid(false);
            return;
        }

        if (isBlank(manifest.getId())) loaded.getErrors().add("id 필수");
        if (isBlank(manifest.getName())) loaded.getErrors().add("name 필수");
        if (isBlank(manifest.getVersion())) loaded.getErrors().add("version 필수");
        if (isBlank(manifest.getType())) loaded.getErrors().add("type 필수");
        if (!isBlank(manifest.getId()) && !pluginIds.add(manifest.getId())) {
            loaded.getErrors().add("id 중복: " + manifest.getId());
        }
        for (PluginCommand command : manifest.getContributes().getCommands()) {
            if (isBlank(command.getId())) {
                loaded.getErrors().add("command id 필수");
            } else if (!commandIds.add(command.getId())) {
                loaded.getErrors().add("command id 중복: " + command.getId());
            }
            if (isBlank(command.getTitle())) {
                loaded.getErrors().add("command title 필수: " + command.getId());
            }
        }
        loaded.setValid(loaded.getErrors().isEmpty());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Path resolvePluginDir() {
        try {
            Path codeLoc = Path.of(PluginManager.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());

            if (Files.isRegularFile(codeLoc)) {
                Path libDir = codeLoc.getParent();
                Path appDir = libDir != null ? libDir.getParent() : null;
                if (libDir != null && Files.isDirectory(libDir.resolve("plugins"))) {
                    return libDir.resolve("plugins");
                }
                if (appDir != null && Files.isDirectory(appDir.resolve("plugins"))) {
                    return appDir.resolve("plugins");
                }
            }

            Path dir = Files.isRegularFile(codeLoc) ? codeLoc.getParent() : codeLoc;
            for (int i = 0; i < 6; i++) {
                Path candidate = dir.resolve("plugins");
                if (Files.isDirectory(candidate)) return candidate;
                Path parent = dir.getParent();
                if (parent == null) break;
                dir = parent;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve plugin dir: {}", e.getMessage());
        }
        return Path.of("plugins");
    }

    public record PluginSettingsTabContribution(
            String pluginId,
            String pluginName,
            PluginSettingsTab tab
    ) {}

    public record PluginCommandContribution(
            String pluginId,
            String pluginName,
            PluginCommand command,
            /** 연결된 서비스 인스턴스 UUID. 연동 서비스가 없거나 복수이면 null. */
            String linkedServiceId
    ) {}
}
