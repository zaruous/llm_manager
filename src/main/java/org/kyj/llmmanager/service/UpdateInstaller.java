/*
 * 작성자 : kyj
 * 작성일 : 2026-09-14
 */
package org.kyj.llmmanager.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 다운로드된 업데이트 zip 을 임시 디렉토리에 풀고, 앱 종료 후 설치 디렉토리를 교체·재기동하는
 * 배치 스크립트를 생성해 앱 프로세스와 분리된 상태로 실행한다.
 *
 * <p>2026-09-13 업데이트 실패 RCA 를 반영한 설계 제약:
 * <ul>
 *   <li>압축 해제는 앱이 살아있는 동안 Java 에서 수행하고 엔트리 수를 검증한다 —
 *       부분 해제본이 설치본을 덮어쓰는 사고를 막는다.</li>
 *   <li>스크립트는 {@code timeout} 대신 앱 PID 소멸을 폴링한다. 콘솔 없는 프로세스가 띄운
 *       cmd 에서는 stdin 이 파이프라 {@code timeout} 이 즉시 실패한다.</li>
 *   <li>스크립트는 상속받은 stdout 에 쓰지 않고 자기 로그 파일에만 기록한다 —
 *       부모 JVM 종료 후 끊어진 파이프에 쓰면 cmd 가 즉사한다.</li>
 *   <li>호출 측은 서비스 프로세스 정리(taskkill)를 모두 끝낸 뒤 {@link #launchDetached} 를
 *       호출해야 한다. PID 재사용으로 업데이터가 taskkill 대상이 되는 경합을 순서로 차단한다.</li>
 * </ul>
 * JavaFX 나 다른 앱 클래스에 의존하지 않으므로 헤드리스 통합 테스트가 가능하다.
 */
public final class UpdateInstaller {

    /** 앱 종료 대기 상한(초). 넘으면 교체를 포기하고 실패 마커를 남긴다. */
    static final int WAIT_APP_EXIT_SECONDS = 60;

    /** robocopy 종료 코드 8 이상은 복사 실패를 뜻한다 (0~7 은 성공 비트마스크). */
    static final int ROBOCOPY_FAILURE_THRESHOLD = 8;

    private UpdateInstaller() {}

    /**
     * 교체 스크립트 생성에 필요한 경로·식별자 묶음.
     *
     * @param installDir      현재 설치 디렉토리 (LLMManager.exe 가 있는 곳). null 불가 — %~dp0 폴백은 후행
     *                        백슬래시가 따옴표를 깨뜨리는 버그가 있어 제거했다
     * @param sourceDir       압축 해제된 새 버전의 루트 (LLMManager.exe 가 있는 곳)
     * @param zipPath         다운로드한 zip — 성공 시 삭제
     * @param extractDir      압축 해제 작업 디렉토리 — 성공 시 삭제
     * @param logPath         스크립트 로그 파일
     * @param resultPath      결과 마커 파일 (properties 형식)
     * @param appPid          종료를 기다릴 현재 앱 PID
     * @param attemptId       이번 시도 식별자 — pending/result 마커 짝짓기용
     * @param expectedVersion 설치 후 기대 버전 (v 접두사 없이)
     * @param relaunch        교체 후 LLMManager.exe 재기동 여부. 헤드리스 테스트에서 false
     */
    public record ScriptParams(Path installDir, Path sourceDir, Path zipPath, Path extractDir,
                               Path logPath, Path resultPath, long appPid, String attemptId,
                               String expectedVersion, boolean relaunch) {}

    /**
     * 업데이트 작업 디렉토리를 반환한다.
     *
     * @return {@code %TEMP%/llm-manager-update}
     */
    public static Path workDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "llm-manager-update");
    }

    /**
     * zip 을 dest 에 풀고, zip 의 파일 엔트리 수와 실제 생성된 파일 수가 일치하는지 검증한다.
     * dest 가 이미 있으면 이전 시도의 부분 해제본이므로 먼저 지운다.
     *
     * @param zip      다운로드된 zip
     * @param dest     해제 대상 디렉토리
     * @param progress (완료 파일 수, 전체 파일 수) 콜백. null 허용
     * @return robocopy 소스로 쓸 디렉토리 — zip 이 단일 최상위 폴더로 감싸져 있으면 그 폴더, 아니면 dest
     * @throws IOException 해제 실패, 엔트리 수 불일치, 대상 디렉토리 밖으로 나가는 엔트리(Zip Slip) 발견 시
     */
    public static Path extract(Path zip, Path dest, BiConsumer<Integer, Integer> progress) throws IOException {
        deleteTree(dest);
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zf.entries());
            int totalFiles = (int) entries.stream().filter(e -> !e.isDirectory()).count();
            int done = 0;
            for (ZipEntry e : entries) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("zip 엔트리가 대상 디렉토리를 벗어납니다: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (e.getLastModifiedTime() != null) {
                    Files.setLastModifiedTime(target, e.getLastModifiedTime());
                }
                done++;
                if (progress != null) progress.accept(done, totalFiles);
            }

            long onDisk;
            try (Stream<Path> s = Files.walk(root)) {
                onDisk = s.filter(Files::isRegularFile).count();
            }
            if (onDisk != totalFiles) {
                throw new IOException("압축 해제 검증 실패: zip 파일 " + totalFiles + "개, 생성 " + onDisk + "개");
            }
        }
        return singleTopLevelDir(root);
    }

    /**
     * 앱 종료 대기 → 읽기 전용 해제 → robocopy → 결과 마커 → 재기동 순으로 동작하는 배치 스크립트를 생성한다.
     * 모든 명령의 출력은 로그 파일로만 보내고, 종료 코드를 단계마다 판정한다.
     *
     * @param p 스크립트 파라미터
     * @return CRLF 배치 스크립트 텍스트. UTF-8 (BOM 없이) 로 저장해야 첫 줄 {@code @echo off} 가 깨지지 않는다
     * @throws IllegalArgumentException installDir 이 null 이면
     */
    public static String buildScript(ScriptParams p) {
        if (p.installDir() == null) {
            throw new IllegalArgumentException("설치 디렉토리를 알 수 없어 업데이트 스크립트를 만들 수 없습니다");
        }
        String relaunch = p.relaunch() ? "start \"\" \"%INSTALL_DIR%\\LLMManager.exe\"\r\n" : "";
        StringBuilder sb = new StringBuilder(4096);
        sb.append("@echo off\r\n")
          .append("chcp 65001 > nul\r\n")
          .append("setlocal\r\n")
          .append("set \"LOG=").append(p.logPath()).append("\"\r\n")
          .append("set \"RESULT=").append(p.resultPath()).append("\"\r\n")
          .append("set \"INSTALL_DIR=").append(p.installDir()).append("\"\r\n")
          .append("set \"SRC_DIR=").append(p.sourceDir()).append("\"\r\n")
          .append("set \"EXTRACT_DIR=").append(p.extractDir()).append("\"\r\n")
          .append("set \"ZIP_PATH=").append(p.zipPath()).append("\"\r\n")
          .append("set \"APP_PID=").append(p.appPid()).append("\"\r\n")
          .append("set \"ATTEMPT=").append(p.attemptId()).append("\"\r\n")
          .append("set \"EXPECTED=").append(p.expectedVersion()).append("\"\r\n")
          // Git for Windows 등이 PATH 앞에 usr\bin 을 두면 find/timeout 이 GNU 버전으로 바뀌어 조용히 실패한다.
          // 시스템 도구는 전부 절대 경로로 호출한다 (E2E 에서 find 가 GNU find 로 잡혀 대기가 0초로 끝난 사례)
          .append("set \"SYS=%SystemRoot%\\System32\"\r\n")
          .append("set /a WAITED=0\r\n")
          .append("echo [%DATE% %TIME%] update start attempt=%ATTEMPT% app_pid=%APP_PID% expected=%EXPECTED% >> \"%LOG%\"\r\n")
          .append("echo [%TIME%] install_dir=%INSTALL_DIR% >> \"%LOG%\"\r\n")
          .append("echo [%TIME%] src_dir=%SRC_DIR% >> \"%LOG%\"\r\n")
          .append("\r\n")
          // timeout 은 콘솔 없는 프로세스에서 즉시 실패하므로 tasklist 폴링 + ping 1초 대기로 앱 종료를 기다린다.
          // PID 컬럼은 양쪽이 공백으로 패딩되므로 " PID " 로 매칭해 메모리 컬럼 등의 숫자와 혼동하지 않는다
          .append(":waitapp\r\n")
          .append("\"%SYS%\\tasklist.exe\" /FI \"PID eq %APP_PID%\" /NH 2>nul | \"%SYS%\\findstr.exe\" /C:\" %APP_PID% \" >nul\r\n")
          .append("if errorlevel 1 goto :appgone\r\n")
          .append("if %WAITED% GEQ ").append(WAIT_APP_EXIT_SECONDS).append(" goto :fail_wait\r\n")
          .append("set /a WAITED+=1\r\n")
          .append("\"%SYS%\\ping.exe\" -n 2 127.0.0.1 >nul\r\n")
          .append("goto :waitapp\r\n")
          .append("\r\n")
          .append(":appgone\r\n")
          .append("echo [%TIME%] app exited after %WAITED%s >> \"%LOG%\"\r\n")
          // jpackage 가 LLMManager.exe 를 읽기 전용으로 만들어 덮어쓰기가 막힐 수 있다
          .append("\"%SYS%\\attrib.exe\" -R \"%INSTALL_DIR%\\*\" /S /D >> \"%LOG%\" 2>&1\r\n")
          // /IS /IT: 시간·크기가 같거나 속성만 다른 파일도 덮어쓴다. /MIR·/PURGE 금지 — app/service-packs 사용자 데이터 보존
          .append("\"%SYS%\\robocopy.exe\" \"%SRC_DIR%\" \"%INSTALL_DIR%\" /E /IS /IT /R:3 /W:2 /NP /NFL /NDL /NJH >> \"%LOG%\" 2>&1\r\n")
          .append("set \"RC=%ERRORLEVEL%\"\r\n")
          .append("echo [%TIME%] robocopy exit=%RC% >> \"%LOG%\"\r\n")
          .append("if %RC% GEQ ").append(ROBOCOPY_FAILURE_THRESHOLD).append(" goto :fail_copy\r\n")
          .append(resultBlock("success", "done", true))
          .append("echo [%TIME%] SUCCESS >> \"%LOG%\"\r\n")
          .append(relaunch)
          .append("rmdir /S /Q \"%EXTRACT_DIR%\" >> \"%LOG%\" 2>&1\r\n")
          .append("del /Q \"%ZIP_PATH%\" >> \"%LOG%\" 2>&1\r\n")
          .append("exit /b 0\r\n")
          .append("\r\n")
          .append(":fail_wait\r\n")
          .append(resultBlock("failed", "wait-app-exit", false))
          .append("echo [%TIME%] FAILED: app pid %APP_PID% still running after %WAITED%s >> \"%LOG%\"\r\n")
          .append("exit /b 1\r\n")
          .append("\r\n")
          .append(":fail_copy\r\n")
          .append(resultBlock("failed", "robocopy", true))
          .append("echo [%TIME%] FAILED: robocopy exit %RC% >> \"%LOG%\"\r\n")
          // 반쯤 덮인 설치본이라도 재기동을 시도해 사용자에게 실패 알림이 뜨게 한다
          .append(relaunch)
          .append("exit /b 1\r\n");
        return sb.toString();
    }

    /**
     * 스크립트 파일을 쓰고, 앱과 분리된 새 콘솔에서 실행한다.
     * {@code start /B} 가 아닌 새 콘솔(/MIN)을 쓰고 표준 입출력을 모두 끊어, 부모 JVM 이 종료된 뒤
     * 상속 파이프에 쓰다가 죽는 경로를 배제한다. 중간 cmd 는 즉시 종료되므로 스크립트는 앱 프로세스
     * 트리에서 고아가 되어 {@code taskkill /T} 로 앱 트리를 죽여도 따라 죽지 않는다.
     *
     * @param batPath 스크립트를 저장할 경로
     * @param script  {@link #buildScript} 결과
     * @throws IOException 파일 쓰기 또는 프로세스 기동 실패 시
     */
    public static void launchDetached(Path batPath, String script) throws IOException {
        Files.createDirectories(batPath.getParent());
        Files.writeString(batPath, script, StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe", "/c", "start", "\"\"", "/MIN", "cmd.exe", "/c", batPath.toString());
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    /**
     * 현재 프로세스의 실행 파일에서 위로 올라가며 LLMManager.exe 가 있는 디렉토리를 찾는다.
     * jpackage 런처는 JVM 을 in-process 로 올리므로 {@code command()} 가 런처 exe 경로를 그대로 반환한다.
     *
     * @return 설치 디렉토리. 런처가 아닌 방식(예: gradle run)으로 실행됐으면 null
     */
    public static Path detectInstallDir() {
        return ProcessHandle.current().info().command()
                .map(cmd -> {
                    Path p = Path.of(cmd).getParent();
                    while (p != null) {
                        if (Files.exists(p.resolve("LLMManager.exe"))) return p;
                        p = p.getParent();
                    }
                    return null;
                })
                .orElse(null);
    }

    /** 결과 마커를 쓰는 배치 블록. 괄호 블록 리다이렉트라 각 echo 줄에는 리다이렉트를 붙이지 않는다. */
    private static String resultBlock(String status, String step, boolean withRobocopyExit) {
        StringBuilder sb = new StringBuilder();
        sb.append("(\r\n")
          .append("echo status=").append(status).append("\r\n")
          .append("echo step=").append(step).append("\r\n")
          .append("echo attemptId=%ATTEMPT%\r\n")
          .append("echo expectedVersion=%EXPECTED%\r\n");
        if (withRobocopyExit) sb.append("echo robocopyExit=%RC%\r\n");
        sb.append("echo finishedAt=%DATE% %TIME%\r\n")
          .append(")> \"%RESULT%\"\r\n");
        return sb.toString();
    }

    /** dest 바로 아래에 디렉토리 하나만 있으면 그 디렉토리를, 아니면 dest 자체를 반환한다. */
    private static Path singleTopLevelDir(Path dest) throws IOException {
        try (Stream<Path> s = Files.list(dest)) {
            List<Path> children = s.toList();
            if (children.size() == 1 && Files.isDirectory(children.get(0))) return children.get(0);
        }
        return dest;
    }

    /** 디렉토리 트리를 지운다. 없으면 no-op. */
    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            // 하위 먼저 지워야 하므로 역순
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
