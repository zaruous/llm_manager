/*
 * 작성자 : kyj
 * 작성일 : 2026-09-14
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.service.PidFileManager.PidCheck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PidFileManager.check()가 재배정된 PID를 살아있는 서비스로 오인하지 않는지 실제 프로세스로 검증한다.
 * Windows 전용 명령(ping/cmd)을 쓰므로 Windows에서만 실행한다.
 */
@EnabledOnOs(OS.WINDOWS)
class PidFileManagerCheckTest {

    private static ServiceDefinition def(Path dir) {
        ServiceDefinition def = new ServiceDefinition();
        def.setId("chk-svc");
        def.setName("Check Service");
        def.setInstallDir(dir.toString());
        return def;
    }

    /** 약 30초 살아있는 프로세스. 테스트 끝에 반드시 destroyForcibly() 한다. */
    private static Process sleeper() throws IOException {
        return new ProcessBuilder("ping", "-n", "30", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Test
    void check_noFile(@TempDir Path dir) {
        assertEquals(PidCheck.NO_FILE, PidFileManager.check(def(dir)));
    }

    @Test
    void check_exitedProcess_isDead(@TempDir Path dir) throws Exception {
        Process p = new ProcessBuilder("cmd.exe", "/c", "exit").start();
        p.waitFor();
        Files.writeString(PidFileManager.resolve(def(dir)), String.valueOf(p.pid()));
        assertEquals(PidCheck.DEAD, PidFileManager.check(def(dir)));
    }

    @Test
    void check_liveProcessRecordedByWrite_matches(@TempDir Path dir) throws Exception {
        Process p = sleeper();
        try {
            PidFileManager.write(def(dir), p.pid());
            String content = Files.readString(PidFileManager.resolve(def(dir)));
            assertTrue(content.contains("start="), "write()는 시작 시각을 2행에 기록해야 한다");
            assertEquals(PidCheck.LIVE_MATCH, PidFileManager.check(def(dir)));
            // 2행이 있어도 read()는 PID만 돌려준다
            assertEquals(p.pid(), PidFileManager.read(def(dir)).getAsLong());
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void check_legacyFileWrittenBeforeProcessStarted_isStale(@TempDir Path dir) throws Exception {
        // 구버전 파일(PID만 기록)이 프로세스 시작보다 훨씬 전에 쓰였다면, 그 PID는 재배정된 것이다
        Process p = sleeper();
        try {
            Path file = PidFileManager.resolve(def(dir));
            Files.writeString(file, String.valueOf(p.pid()));
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(300)));
            assertEquals(PidCheck.STALE, PidFileManager.check(def(dir)));
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void check_recordedStartDiffersFromActual_isStale(@TempDir Path dir) throws Exception {
        Process p = sleeper();
        try {
            long anHourAgo = Instant.now().minusSeconds(3600).toEpochMilli();
            Files.writeString(PidFileManager.resolve(def(dir)), p.pid() + "\nstart=" + anHourAgo);
            assertEquals(PidCheck.STALE, PidFileManager.check(def(dir)));
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void check_legacySharedFileName_isAlsoChecked(@TempDir Path dir) throws Exception {
        // 신버전 파일이 없으면 구버전 .llm-manager.pid 를 읽는다 (v1.0.5 설치본이 남긴 파일)
        Process p = sleeper();
        try {
            Path legacy = dir.resolve(".llm-manager.pid");
            Files.writeString(legacy, String.valueOf(p.pid()));
            Files.setLastModifiedTime(legacy, FileTime.from(Instant.now().minusSeconds(300)));
            assertEquals(PidCheck.STALE, PidFileManager.check(def(dir)));
        } finally {
            p.destroyForcibly();
        }
    }
}
