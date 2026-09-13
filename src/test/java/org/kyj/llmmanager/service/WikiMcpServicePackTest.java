/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.util.PlatformUtil;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wiki-mcp 서비스 팩의 Groovy 커스터마이징 결과를 검증한다.
 */
class WikiMcpServicePackTest {

    @Test
    void wikiMcpGroovyInstallsRuntimeFilesIntoInstallDir(@TempDir Path tempDir) throws Exception {
        Path pluginDir = tempDir.resolve("plugins").resolve("wiki-agent");
        Files.createDirectories(pluginDir.resolve("tools"));
        Files.writeString(pluginDir.resolve("server.py"), "print('wiki-mcp')\n");
        Files.writeString(pluginDir.resolve("plugin.json"), """
                {
                  "ingest": {
                    "include": ["*.md"],
                    "exclude": ["wiki/**"],
                    "contentMaxBytes": 10485760,
                    "stagingDirectory": "raw/mcp-ingest"
                  }
                }
                """);
        Files.writeString(pluginDir.resolve("tools").resolve("tool.py"), "print('tool')\n");
        Path cursorSidecar = tempDir.resolve("plugins").resolve("cursor-agent-runner")
                .resolve("sidecar").resolve("cursor-agent-sidecar.js");
        Files.createDirectories(cursorSidecar.getParent());
        Files.writeString(cursorSidecar, "console.log('cursor-agent')\n");

        Path installBase = tempDir.resolve("install-base");
        Path workspace = tempDir.resolve("workspace");

        String oldPluginsDir = System.getProperty("llm.pluginsDir");
        String oldInstallBase = System.getProperty("INSTALL_BASE");
        String oldVectorDir = System.getProperty(WikiVectorRepository.VECTOR_DIR_PROP);
        try {
            System.setProperty("llm.pluginsDir", tempDir.resolve("plugins").toString());
            System.setProperty("INSTALL_BASE", installBase.toString());
            // groovy의 resolveDbFile() 호출이 실제 사용자 홈에 레지스트리를 만들지 않도록 격리
            System.setProperty(WikiVectorRepository.VECTOR_DIR_PROP,
                    tempDir.resolve("vector-base").toString());

            ServiceDefinition def = new ServicePackLoader()
                    .load(Path.of("service-packs", "wiki-mcp.yml").toFile());
            def.getArgValues().put("workspace", workspace.toString());
            def.getArgValues().put("enable-write", "true");

            new ServiceCustomizer().apply(def, def.getGroovyScript());

            assertEquals(installBase.toString(), def.getInstallDir());
            assertEquals(installBase.toString(), def.getWorkingDir());
            assertTrue(def.getStartCommand().contains("server.py"));
            // 중앙 벡터 저장소 규칙: vector/<워크스페이스 ID>/ — ID는 레지스트리가 발급하며
            // 같은 워크스페이스는 항상 같은 경로를 돌려받는다
            String dbPath = def.getArgValues().get("db-path");
            assertEquals(WikiVectorRepository.resolveDbFile(workspace).toString(), dbPath);
            assertTrue(dbPath.startsWith(tempDir.resolve("vector-base").toString()));
            assertTrue(dbPath.contains("workspace-"));
            assertFalse(def.getInstallCommands().isEmpty());
            assertTrue(def.getInstallCommands().get(0).contains("server.py"));
            assertTrue(def.getInstallCommands().get(0).contains("tools"));
            assertTrue(def.getInstallCommands().get(0).contains("cursor-agent-sidecar.js"));
            assertTrue(def.getInstallCommands().get(0).contains("plugin.json"));
            assertTrue(def.getStartCommand().contains("--cursor-sidecar"));
            assertTrue(def.getStartCommand().contains("--plugin-config"));
            assertTrue(def.getStartCommand().contains(
                    installBase.resolve("cursor-agent-sidecar.js").toString()));
            assertFalse(def.getInstallCommands().stream()
                    .anyMatch(command -> command.contains("@cursor/sdk")));
            if (PlatformUtil.isWindows()) {
                assertTrue(def.getInstallCommands().get(0).contains("Copy-Item"));
            } else {
                assertTrue(def.getInstallCommands().get(0).contains("cp -"));
            }
        } finally {
            restoreProperty("llm.pluginsDir", oldPluginsDir);
            restoreProperty("INSTALL_BASE", oldInstallBase);
            restoreProperty(WikiVectorRepository.VECTOR_DIR_PROP, oldVectorDir);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
