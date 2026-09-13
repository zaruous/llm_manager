/*
 * 작성자 : kyj
 * 작성일 : 2026-09-14
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpdateInstaller의 교체 스크립트 생성 규칙과 zip 해제·검증 로직을 검증한다.
 * 스크립트 규칙은 2026-09-13 업데이트 실패 RCA에서 확인된 결함이 재발하지 않도록 고정한다.
 */
class UpdateInstallerTest {

    private static UpdateInstaller.ScriptParams params(Path installDir, boolean relaunch) {
        Path tmp = Path.of("C:\\tmp\\llm-manager-update");
        return new UpdateInstaller.ScriptParams(
                installDir, tmp.resolve("extracted\\LLMManager"), tmp.resolve("LLMManager-1.2.1.zip"),
                tmp.resolve("extracted"), tmp.resolve("update.log"), tmp.resolve("result.properties"),
                4242L, "abc12345", "1.2.1", relaunch);
    }

    private static String script(boolean relaunch) {
        return UpdateInstaller.buildScript(params(Path.of("C:\\Apps\\LLMManager"), relaunch));
    }

    // ─── buildScript() ────────────────────────────────────────────

    @Test
    void buildScript_waitsForAppPidInsteadOfTimeout() {
        String s = script(true);
        // timeout은 콘솔 없는 프로세스에서 즉시 실패한다 — 절대 다시 쓰지 않는다
        assertFalse(s.contains("timeout "), "timeout 명령을 사용하면 안 된다");
        assertTrue(s.contains("tasklist.exe\" /FI \"PID eq %APP_PID%\""));
        assertTrue(s.contains("findstr.exe\" /C:\" %APP_PID% \""), "PID 컬럼은 공백 패딩으로 매칭한다");
        assertTrue(s.contains("set \"APP_PID=4242\""));
        assertTrue(s.contains("ping.exe\" -n 2 127.0.0.1"));
        // 앱(JVM 자식) 종료 직후 jpackage 런처(부모)가 exe 를 놓을 시간 — 복사 전 2초 유예
        assertTrue(s.contains(":appgone\r\necho [%TIME%] app exited after %WAITED%s >> \"%LOG%\"\r\n"
                + "\"%SYS%\\ping.exe\" -n 3 127.0.0.1 >nul\r\n"), "appgone 직후 2초 유예가 있어야 한다");
    }

    @Test
    void buildScript_callsSystemToolsByAbsolutePath() {
        // Git for Windows 가 PATH 앞에 usr\bin 을 두면 find/timeout 이 GNU 버전으로 바뀐다.
        // E2E 에서 find 가 GNU find 로 잡혀 앱 종료 대기가 0초로 끝난 사례 — 시스템 도구는 절대 경로로만 부른다
        String s = script(true);
        assertTrue(s.contains("set \"SYS=%SystemRoot%\\System32\""));
        for (String tool : new String[]{"tasklist.exe", "findstr.exe", "ping.exe", "attrib.exe", "robocopy.exe"}) {
            assertTrue(s.contains("\"%SYS%\\" + tool + "\""), tool + " 은 %SYS% 절대 경로로 호출해야 한다");
        }
        assertFalse(Pattern.compile("(?m)^(tasklist|find|findstr|ping|attrib|robocopy)\\b").matcher(s).find(),
                "줄 시작에서 시스템 도구를 이름만으로 호출하면 안 된다");
        assertFalse(s.contains("| find "), "GNU find 에 가려질 수 있는 find 를 쓰면 안 된다");
    }

    @Test
    void buildScript_failsOnRobocopyExitCode8OrAbove() {
        String s = script(true);
        assertTrue(s.contains("if %RC% GEQ 8 goto :fail_copy"));
        assertTrue(s.contains("step=robocopy"));
        assertTrue(s.contains("status=failed"));
        assertTrue(s.contains("status=success"));
    }

    @Test
    void buildScript_clearsReadOnlyBeforeCopy() {
        String s = script(true);
        int attrib = s.indexOf("attrib.exe\" -R");
        int robocopy = s.indexOf("robocopy.exe\" \"%SRC_DIR%\"");
        assertTrue(attrib > 0 && robocopy > attrib, "attrib -R 이 robocopy 앞에 와야 한다");
    }

    @Test
    void buildScript_neverUsesDp0Fallback() {
        assertFalse(script(true).contains("%~dp0"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdateInstaller.buildScript(params(null, true)));
    }

    @Test
    void buildScript_relaunchOnlyWhenRequested() {
        String relaunch = "start \"\" \"%INSTALL_DIR%\\LLMManager.exe\"";
        assertTrue(script(true).contains(relaunch));
        assertFalse(script(false).contains(relaunch));
    }

    @Test
    void buildScript_noDigitDirectlyBeforeAppendRedirect() {
        // "exit=1>> log" 처럼 숫자 뒤에 바로 >> 가 오면 cmd가 핸들 리다이렉트로 해석해 숫자를 잃는다
        assertFalse(Pattern.compile("[0-9%]>>").matcher(script(true)).find(),
                "숫자나 변수 확장 바로 뒤에 >> 를 붙이면 안 된다");
    }

    @Test
    void buildScript_writesMarkersWithAttemptAndVersion() {
        String s = script(true);
        assertTrue(s.contains("set \"ATTEMPT=abc12345\""));
        assertTrue(s.contains("set \"EXPECTED=1.2.1\""));
        assertTrue(s.contains("echo attemptId=%ATTEMPT%"));
        assertTrue(s.contains("echo expectedVersion=%EXPECTED%"));
        assertTrue(s.contains(")> \"%RESULT%\""));
    }

    @Test
    void buildScript_everyCommandLogsToFile() {
        String s = script(true);
        long redirects = s.lines().filter(l -> l.contains(">> \"%LOG%\"")).count();
        assertTrue(redirects >= 8, "단계별 로그 리다이렉트가 충분해야 한다: " + redirects);
        assertTrue(s.contains("chcp 65001 > nul"));
    }

    @Test
    void buildScript_usesCrlfOnly() {
        String s = script(true);
        assertTrue(s.startsWith("@echo off\r\n"));
        assertFalse(s.replace("\r\n", "").contains("\n"), "LF 단독 줄바꿈이 있으면 안 된다");
    }

    // ─── extract() ────────────────────────────────────────────────

    @Test
    void extract_returnsWrapperDirAndVerifiesFileCount(@TempDir Path dir) throws IOException {
        Path zip = makeZip(dir.resolve("u.zip"),
                "LLMManager/", "LLMManager/LLMManager.exe", "LLMManager/app/LLMManager.cfg");
        int[] last = new int[2];
        Path src = UpdateInstaller.extract(zip, dir.resolve("out"), (d, t) -> { last[0] = d; last[1] = t; });

        assertEquals("LLMManager", src.getFileName().toString());
        assertTrue(Files.isRegularFile(src.resolve("LLMManager.exe")));
        assertTrue(Files.isRegularFile(src.resolve("app/LLMManager.cfg")));
        assertArrayEquals(new int[]{2, 2}, last, "진행 콜백은 (완료, 전체) 파일 수를 전달한다");
    }

    @Test
    void extract_withoutWrapperDir_returnsDestItself(@TempDir Path dir) throws IOException {
        Path zip = makeZip(dir.resolve("u.zip"), "a.txt", "b/c.txt");
        Path src = UpdateInstaller.extract(zip, dir.resolve("out"), null);
        assertEquals(dir.resolve("out").toAbsolutePath().normalize(), src);
    }

    @Test
    void extract_replacesPreviousPartialExtraction(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out");
        Files.createDirectories(out.resolve("LLMManager"));
        Files.writeString(out.resolve("LLMManager/stale.jar"), "old");
        Path zip = makeZip(dir.resolve("u.zip"), "LLMManager/", "LLMManager/new.jar");

        UpdateInstaller.extract(zip, out, null);

        assertFalse(Files.exists(out.resolve("LLMManager/stale.jar")), "이전 시도의 잔여물은 지워져야 한다");
        assertTrue(Files.exists(out.resolve("LLMManager/new.jar")));
    }

    @Test
    void extract_rejectsZipSlipEntries(@TempDir Path dir) throws IOException {
        Path zip = makeZip(dir.resolve("evil.zip"), "../evil.txt");
        IOException ex = assertThrows(IOException.class,
                () -> UpdateInstaller.extract(zip, dir.resolve("out"), null));
        assertTrue(ex.getMessage().contains("벗어납니다"));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    /** 이름이 '/'로 끝나면 디렉토리 엔트리, 아니면 짧은 내용을 가진 파일 엔트리로 zip을 만든다. */
    private static Path makeZip(Path zip, String... names) throws IOException {
        try (OutputStream os = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            for (String name : names) {
                zos.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zos.write(("content of " + name).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return zip;
    }
}
