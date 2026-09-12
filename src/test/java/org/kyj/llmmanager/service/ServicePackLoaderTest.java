/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ServicePackLoader의 YAML 로드·다중 팩 로딩·디렉토리 탐색 로직을 검증한다.
 */
class ServicePackLoaderTest {

    // ─────────────────────────────────────────────────────────────
    // load(File) — 단일 YAML
    // ─────────────────────────────────────────────────────────────

    @Test
    void load_parsesNameAndPort(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("test.yml");
        Files.writeString(yaml, """
                name: Test Service
                runtimeType: PYTHON
                startCommand: python server.py
                port: 9090
                """);
        ServiceDefinition def = new ServicePackLoader().load(yaml.toFile());
        assertEquals("Test Service", def.getName());
        assertEquals(9090, def.getPort());
    }

    @Test
    void load_parsesStartCommand(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("svc.yml");
        Files.writeString(yaml, """
                name: Worker
                runtimeType: NODE
                startCommand: node index.js
                """);
        ServiceDefinition def = new ServicePackLoader().load(yaml.toFile());
        assertEquals("node index.js", def.getStartCommand());
    }

    @Test
    void load_parsesInstallCommands(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("svc.yml");
        Files.writeString(yaml, """
                name: With Deps
                runtimeType: PYTHON
                startCommand: python app.py
                installCommands:
                  - pip install fastmcp
                  - pip install numpy
                """);
        ServiceDefinition def = new ServicePackLoader().load(yaml.toFile());
        assertEquals(2, def.getInstallCommands().size());
        assertEquals("pip install fastmcp", def.getInstallCommands().get(0));
    }

    @Test
    void load_parsesArgSpecs(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("svc.yml");
        Files.writeString(yaml, """
                name: ArgSpec Service
                runtimeType: PYTHON
                startCommand: python server.py
                argSpecs:
                  - name: port
                    flag: --port
                    type: NUMBER
                    defaultValue: "8080"
                """);
        ServiceDefinition def = new ServicePackLoader().load(yaml.toFile());
        assertFalse(def.getArgSpecs().isEmpty());
        assertEquals("--port", def.getArgSpecs().get(0).getFlag());
        assertEquals("8080", def.getArgSpecs().get(0).getDefaultValue());
    }

    @Test
    void load_unknownFields_ignoredGracefully(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("svc.yml");
        Files.writeString(yaml, """
                name: Future Service
                runtimeType: PYTHON
                startCommand: python app.py
                unknownFieldInFuture: value
                """);
        // @JsonIgnoreProperties(ignoreUnknown=true) — 예외 없이 통과해야 한다
        assertDoesNotThrow(() -> new ServicePackLoader().load(yaml.toFile()));
    }

    @Test
    void load_missingFile_throwsIOException(@TempDir Path dir) {
        Path missing = dir.resolve("missing.yml");
        assertThrows(IOException.class, () -> new ServicePackLoader().load(missing.toFile()));
    }

    // ─────────────────────────────────────────────────────────────
    // loadAll()
    // ─────────────────────────────────────────────────────────────

    @Test
    void loadAll_returnsNonNull() {
        // service-packs/ 디렉토리 유무와 무관하게 null은 반환하지 않아야 한다
        List<ServiceDefinition> defs = new ServicePackLoader().loadAll();
        assertNotNull(defs);
    }

    @Test
    void loadAll_setsBuiltinFlag() {
        List<ServiceDefinition> defs = new ServicePackLoader().loadAll();
        // 개발 환경(service-packs/ 존재)에서는 모든 정의가 builtin=true
        assertTrue(defs.stream().allMatch(ServiceDefinition::isBuiltin),
                "서비스 팩에서 로드한 정의는 builtin=true여야 한다");
    }

    @Test
    void loadAll_allDefinitionsHaveName() {
        // null name 은 templateDefs에 등록되지 않아야 하므로 name이 있어야 한다
        List<ServiceDefinition> defs = new ServicePackLoader().loadAll();
        assertTrue(defs.stream().allMatch(d -> d.getName() != null && !d.getName().isBlank()),
                "모든 서비스 팩 정의에 이름이 있어야 한다");
    }

    // ─────────────────────────────────────────────────────────────
    // resolvePacksDir()
    // ─────────────────────────────────────────────────────────────

    @Test
    void resolvePacksDir_notNullInDevMode() {
        // 테스트는 프로젝트 루트에서 실행 → service-packs/ 이 존재한다
        Path dir = ServicePackLoader.resolvePacksDir();
        assertNotNull(dir, "개발 환경에서는 service-packs/ 디렉토리를 찾아야 한다");
    }

    @Test
    void resolvePacksDir_returnsAbsolutePath() {
        Path dir = ServicePackLoader.resolvePacksDir();
        if (dir != null) {
            assertTrue(dir.isAbsolute(), "절대 경로를 반환해야 한다");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // resolveDownloadUrl — 정의에 없으면 같은 이름의 팩에서 찾는다
    // ─────────────────────────────────────────────────────────────

    @Test
    void resolveDownloadUrl_usesOwnValueWhenPresent() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("SQL Gen MCP Server");
        def.setDownloadUrl("  https://example.com/a.jar  ");

        // 정의에 값이 있으면 팩을 보지 않고 그대로(trim) 쓴다
        assertEquals("https://example.com/a.jar",
                new ServicePackLoader().resolveDownloadUrl(def));
    }

    @Test
    void resolveDownloadUrl_returnsNullForUnknownName() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("존재하지 않는 서비스 " + System.nanoTime());

        assertNull(new ServicePackLoader().resolveDownloadUrl(def));
    }

    @Test
    void resolveDownloadUrl_returnsNullForNullInput() {
        assertNull(new ServicePackLoader().resolveDownloadUrl(null));
    }

    @Test
    void resolveDownloadUrl_fallsBackToPackByName() {
        // service-packs/sql-gen-mcp.yml의 downloadUrl을 이름으로 찾아온다.
        // downloadUrl이 생기기 전에 등록된 서비스가 이 경로로 구제된다.
        ServicePackLoader loader = new ServicePackLoader();
        String fromPack = loader.loadAll().stream()
                .filter(p -> "SQL Gen MCP Server".equals(p.getName()))
                .map(ServiceDefinition::getDownloadUrl)
                .findFirst().orElse(null);
        assumeTrue(fromPack != null, "service-packs/sql-gen-mcp.yml이 있어야 하는 테스트");

        ServiceDefinition stored = new ServiceDefinition();
        stored.setName("SQL Gen MCP Server");
        stored.setDownloadUrl(null);          // 옛 방식으로 저장된 서비스

        assertEquals(fromPack.trim(), loader.resolveDownloadUrl(stored));
    }
}
