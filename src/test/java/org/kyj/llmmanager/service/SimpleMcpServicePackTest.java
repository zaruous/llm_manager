/*
 * 작성자 : kyj
 * 작성일 : 2026-09-28
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.RuntimeType;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.util.CommandBuilder;
import org.kyj.llmmanager.util.PlatformUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * simple-mcp 서비스 팩(YAML + Groovy)이 읽기 전용 MCP 서버를 설치·실행하도록 구성되는지 검증한다.
 */
class SimpleMcpServicePackTest {

    @Test
    void simpleMcpPackLoadsWithoutEmbeddingArgs() throws Exception {
        ServiceDefinition def = new ServicePackLoader()
                .load(Path.of("service-packs", "simple-mcp.yml").toFile());

        assertEquals("simple-mcp", def.getId());
        assertEquals(RuntimeType.PYTHON, def.getRuntimeType());
        assertEquals(7071, def.getPort());
        assertEquals("/health", def.getHealthCheckPath());

        List<String> argNames = def.getArgSpecs().stream().map(ArgSpec::getName).toList();
        assertTrue(argNames.containsAll(List.of("port", "db-url", "db-user", "db-pw", "db-schema", "max-rows")));
        // 임베딩·벡터 스토어 관련 인수가 없어야 sql-gen-mcp보다 가벼운 조회 전용 서버가 된다
        assertTrue(argNames.stream().noneMatch(n -> n.contains("tei") || n.contains("embedding")
                || n.contains("chroma") || n.contains("vector")));
    }

    @Test
    void simpleMcpGroovyCopiesServerIntoUserChosenInstallDir(@TempDir Path tempDir) throws Exception {
        Path pluginDir = tempDir.resolve("plugins").resolve("simple-mcp");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("server.py"), "print('simple-mcp')\n");
        Path installDir = tempDir.resolve("install-2");

        String oldPluginsDir = System.getProperty("llm.pluginsDir");
        try {
            System.setProperty("llm.pluginsDir", tempDir.resolve("plugins").toString());

            ServiceDefinition def = new ServicePackLoader()
                    .load(Path.of("service-packs", "simple-mcp.yml").toFile());
            // 설정 화면에서 입력한(중복 등록 시 -2 접미사가 붙은) 경로를 Groovy가 덮어쓰지 않아야 한다
            def.setInstallDir(installDir.toString());
            def.getArgValues().put("db-url", "jdbc:postgresql://db-host:5433/dbmes");

            new ServiceCustomizer().apply(def, def.getGroovyScript());

            assertEquals(installDir.toString(), def.getInstallDir());
            assertEquals(installDir.toString(), def.getWorkingDir());
            assertTrue(def.getStartCommand().endsWith("server.py"));

            assertEquals(2, def.getInstallCommands().size());
            String copy = def.getInstallCommands().get(0);
            assertTrue(copy.contains(pluginDir.resolve("server.py").toString()));
            assertTrue(copy.contains(installDir.resolve("server.py").toString()));
            if (PlatformUtil.isWindows()) {
                assertTrue(copy.contains("Copy-Item"));
            } else {
                assertTrue(copy.contains("cp -f"));
            }
            assertTrue(def.getInstallCommands().get(1).contains("psycopg"));

            String command = CommandBuilder.buildStartCommand(def);
            assertTrue(command.contains("--db-url jdbc:postgresql://db-host:5433/dbmes"));
            assertTrue(command.contains("--port 7071"));
            assertTrue(command.contains("--max-rows 200"));
        } finally {
            if (oldPluginsDir == null) {
                System.clearProperty("llm.pluginsDir");
            } else {
                System.setProperty("llm.pluginsDir", oldPluginsDir);
            }
        }
    }
}
