/*
 * 작성자 : kyj
 * 작성일 : 2026-09-14
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpdateInstaller.launchDetached 가 실제로 스크립트를 실행하는지 확인한다.
 * WMI 경로가 깨지면(권한·서비스 중지·인용부호) 업데이트가 조용히 시작조차 안 되므로 회귀를 막는다.
 */
@EnabledOnOs(OS.WINDOWS)
class UpdateInstallerLaunchTest {

    /** 마커 파일이 생길 때까지 최대 timeoutMs 기다린다. */
    private static boolean waitFor(Path marker, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(marker)) return true;
            Thread.sleep(200);
        }
        return Files.exists(marker);
    }

    @Test
    void launchViaWmi_runsScript(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran-wmi.txt");
        Path bat = dir.resolve("probe.bat");
        Files.writeString(bat, "@echo off\r\necho ok> \"" + marker + "\"\r\n");

        int rc = UpdateInstaller.launchViaWmi(bat);

        assertEquals(0, rc, "Win32_Process.Create ReturnValue 는 0 이어야 한다");
        assertTrue(waitFor(marker, 15_000), "WMI 로 띄운 스크립트가 15초 안에 실행돼야 한다");
    }

    @Test
    void launchDetached_writesScriptAndRunsIt(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("ran-detached.txt");
        Path bat = dir.resolve("nested").resolve("update.bat");
        String script = "@echo off\r\nchcp 65001 > nul\r\necho ok> \"" + marker + "\"\r\n";

        UpdateInstaller.launchDetached(bat, script);

        assertTrue(Files.isRegularFile(bat), "스크립트 파일이 생성돼야 한다 (상위 디렉토리 포함)");
        assertEquals(script, Files.readString(bat));
        assertTrue(waitFor(marker, 15_000), "스크립트가 15초 안에 실행돼야 한다");
    }
}
