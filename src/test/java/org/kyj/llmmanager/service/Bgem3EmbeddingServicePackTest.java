package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bgem3-embedding 서비스 팩의 Groovy 커스터마이징 결과를 검증한다.
 */
class Bgem3EmbeddingServicePackTest {

    @Test
    void cudaInstallCommandIsStoredAsJavaString(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        String oldInstallBase = System.getProperty("INSTALL_BASE");
        try {
            System.setProperty("INSTALL_BASE", tempDir.toString());
            ServiceDefinition def = new ServicePackLoader()
                    .load(Path.of("service-packs", "bgem3-embedding.yml").toFile());
            def.getArgValues().put("install-type", "cuda");

            new ServiceCustomizer().apply(def, def.getGroovyScript());

            assertTrue(def.getInstallCommands().stream()
                    .allMatch(command -> command.getClass() == String.class));
        } finally {
            if (oldInstallBase == null) {
                System.clearProperty("INSTALL_BASE");
            } else {
                System.setProperty("INSTALL_BASE", oldInstallBase);
            }
        }
    }
}