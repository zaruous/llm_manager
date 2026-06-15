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
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PidFileManager의 PID 파일 경로 결정·읽기·쓰기·삭제 로직을 검증한다.
 */
class PidFileManagerTest {

    private static ServiceDefinition defWithInstallDir(String installDir) {
        ServiceDefinition def = new ServiceDefinition();
        def.setId("test-svc");
        def.setName("Test Service");
        def.setInstallDir(installDir);
        return def;
    }

    private static ServiceDefinition defWithoutInstallDir() {
        ServiceDefinition def = new ServiceDefinition();
        def.setId("no-dir-svc");
        def.setName("No Dir Service");
        return def;
    }

    // ─────────────────────────────────────────────────────────────
    // resolve()
    // ─────────────────────────────────────────────────────────────

    @Test
    void resolve_withInstallDir_placedInsideInstallDir(@TempDir Path dir) {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        Path resolved = PidFileManager.resolve(def);
        assertEquals(dir.resolve(".llm-manager.pid"), resolved);
    }

    @Test
    void resolve_withoutInstallDir_usesFallbackDirWithServiceId() {
        ServiceDefinition def = defWithoutInstallDir();
        Path resolved = PidFileManager.resolve(def);
        assertTrue(resolved.toString().contains("no-dir-svc"),
                "서비스 ID가 fallback 경로에 포함되어야 한다");
        assertTrue(resolved.toString().endsWith(".pid"));
    }

    @Test
    void resolve_blankInstallDir_usesFallback() {
        ServiceDefinition def = new ServiceDefinition();
        def.setId("blank-svc");
        def.setInstallDir("   ");
        Path resolved = PidFileManager.resolve(def);
        // 공백 installDir → fallback 경로 사용
        assertTrue(resolved.toString().endsWith(".pid"));
    }

    // ─────────────────────────────────────────────────────────────
    // write / read 왕복
    // ─────────────────────────────────────────────────────────────

    @Test
    void writeAndRead_roundTrip(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        PidFileManager.write(def, 12345L);
        OptionalLong result = PidFileManager.read(def);
        assertTrue(result.isPresent());
        assertEquals(12345L, result.getAsLong());
    }

    @Test
    void write_createsParentDirs(@TempDir Path dir) throws IOException {
        // installDir의 하위 경로가 아직 없어도 write가 디렉토리를 만들어야 한다
        Path nested = dir.resolve("a/b/c");
        ServiceDefinition def = defWithInstallDir(nested.toString());
        PidFileManager.write(def, 777L);
        assertTrue(Files.isRegularFile(PidFileManager.resolve(def)));
    }

    @Test
    void write_overwritesPreviousPid(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        PidFileManager.write(def, 100L);
        PidFileManager.write(def, 200L);
        assertEquals(200L, PidFileManager.read(def).getAsLong());
    }

    // ─────────────────────────────────────────────────────────────
    // read 예외 경로
    // ─────────────────────────────────────────────────────────────

    @Test
    void read_noFile_returnsEmpty(@TempDir Path dir) {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        assertTrue(PidFileManager.read(def).isEmpty(), "PID 파일 없으면 empty여야 한다");
    }

    @Test
    void read_corruptContent_returnsEmpty(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        Files.writeString(PidFileManager.resolve(def), "not-a-number");
        assertTrue(PidFileManager.read(def).isEmpty(), "파싱 불가 내용이면 empty여야 한다");
    }

    @Test
    void read_emptyFile_returnsEmpty(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        Files.writeString(PidFileManager.resolve(def), "");
        assertTrue(PidFileManager.read(def).isEmpty());
    }

    @Test
    void read_whitespaceContent_parsesCorrectly(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        // trim()을 통해 공백이 제거되고 정상 파싱되어야 한다
        Files.writeString(PidFileManager.resolve(def), "  9876  \n");
        OptionalLong result = PidFileManager.read(def);
        assertTrue(result.isPresent());
        assertEquals(9876L, result.getAsLong());
    }

    // ─────────────────────────────────────────────────────────────
    // delete
    // ─────────────────────────────────────────────────────────────

    @Test
    void delete_removesExistingFile(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        PidFileManager.write(def, 999L);
        PidFileManager.delete(def);
        assertTrue(PidFileManager.read(def).isEmpty(), "삭제 후 read는 empty여야 한다");
    }

    @Test
    void delete_nonExistentFile_doesNotThrow(@TempDir Path dir) {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        assertDoesNotThrow(() -> PidFileManager.delete(def));
    }

    @Test
    void delete_afterDelete_fileGone(@TempDir Path dir) throws IOException {
        ServiceDefinition def = defWithInstallDir(dir.toString());
        PidFileManager.write(def, 42L);
        PidFileManager.delete(def);
        assertFalse(Files.exists(PidFileManager.resolve(def)));
    }
}
