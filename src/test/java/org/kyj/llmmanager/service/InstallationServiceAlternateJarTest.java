/*
 * 작성자 : kyj
 * 작성일 : 2026-09-17
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.RuntimeType;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 브라우저로 직접 받아 둔(버전 붙은) JAR을 설치된 것으로 인식하는
 * {@link InstallationService#findAlternateJar} 규칙을 검증한다.
 */
class InstallationServiceAlternateJarTest {

    private final InstallationService service = new InstallationService();

    private static ServiceDefinition sqlGenDef(Path installDir) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("SQL Gen MCP Server");
        def.setRuntimeType(RuntimeType.JAVA);
        def.setStartCommand("java -jar sql-gen-mcp.jar");
        def.setInstallDir(installDir.toString());
        def.setWorkingDir(installDir.toString());
        return def;
    }

    @Test
    void adoptsUniqueVersionedJarWhenConfiguredJarMissing(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("sql-gen-mcp-1.1.0.jar"));
        ServiceDefinition def = sqlGenDef(dir);

        assertFalse(service.isInstalled(def), "설정된 이름의 JAR이 없으므로 미설치여야 한다");
        assertEquals(Optional.of("sql-gen-mcp-1.1.0.jar"), service.findAlternateJar(def));
    }

    @Test
    void returnsEmptyWhenConfiguredJarExists(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("sql-gen-mcp.jar"));
        Files.createFile(dir.resolve("sql-gen-mcp-1.1.0.jar"));

        assertTrue(service.findAlternateJar(sqlGenDef(dir)).isEmpty());
    }

    @Test
    void returnsEmptyWhenMultipleCandidates(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("sql-gen-mcp-1.0.0.jar"));
        Files.createFile(dir.resolve("sql-gen-mcp-1.1.0.jar"));

        assertTrue(service.findAlternateJar(sqlGenDef(dir)).isEmpty(), "후보가 둘이면 채택하지 않는다");
    }

    @Test
    void ignoresJarsWithDifferentPrefixAndPartialDownloads(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("other-tool.jar"));
        Files.createFile(dir.resolve("sql-gen-mcp.jar.part"));

        assertTrue(service.findAlternateJar(sqlGenDef(dir)).isEmpty());
    }

    @Test
    void stripsVersionFromConfiguredNameBeforeMatching(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("sql-gen-mcp-1.2.0.jar"));
        ServiceDefinition def = sqlGenDef(dir);
        def.setStartCommand("java -jar sql-gen-mcp-1.1.0.jar");

        assertEquals(Optional.of("sql-gen-mcp-1.2.0.jar"), service.findAlternateJar(def));
    }

    @Test
    void returnsEmptyForMissingDirOrNonJarCommand(@TempDir Path dir) {
        ServiceDefinition def = sqlGenDef(dir.resolve("missing"));
        assertTrue(service.findAlternateJar(def).isEmpty());

        ServiceDefinition python = sqlGenDef(dir);
        python.setStartCommand("python server.py");
        assertTrue(service.findAlternateJar(python).isEmpty());
    }
}
