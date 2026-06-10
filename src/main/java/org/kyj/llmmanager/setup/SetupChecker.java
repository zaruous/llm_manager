/*
 * 작성자 : kyj
 * 작성일 : 2026-06-09
 */
package org.kyj.llmmanager.setup;

import org.kyj.llmmanager.util.ToolLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 앱 구동 전 환경 항목(SetupItem) 설치 여부를 확인하는 체커.
 * 각 항목은 프로세스 실행 또는 파일시스템 검사로 판별한다.
 * 검사는 짧은 타임아웃(5초) 내에 완료되도록 설계되어 있다.
 */
public class SetupChecker {

    private static final Logger log = LoggerFactory.getLogger(SetupChecker.class);

    /** 각 명령 실행의 최대 대기 시간 (초) */
    private static final int TIMEOUT_SEC = 5;

    /**
     * 테스트 시 특정 항목을 강제로 실패시키는 오버라이드 맵.
     * 예: overrides.put(SetupItem.PYTHON, false)
     * 프로덕션에서는 비워둔다.
     */
    private final Map<SetupItem, Boolean> overrides = new EnumMap<>(SetupItem.class);

    /** 테스트 전용 — 특정 항목의 결과를 강제 지정한다. */
    public SetupChecker forceResult(SetupItem item, boolean result) {
        overrides.put(item, result);
        return this;
    }

    /**
     * 지정한 항목의 설치 여부를 확인한다.
     *
     * @param item 확인할 환경 항목
     * @return 설치됐으면 true
     */
    public boolean check(SetupItem item) {
        if (overrides.containsKey(item)) return overrides.get(item);
        return switch (item) {
            case PYTHON        -> checkPython();
            case NVIDIA_DRIVER -> checkNvidiaDriver();
            case CUDA          -> checkCuda();
            case NODEJS        -> checkNodeJs();
        };
    }

    /**
     * 모든 항목을 순차 검사해 결과 맵을 반환한다.
     * 다이얼로그 표시 여부를 미리 판단할 때 사용한다.
     *
     * @return 항목별 설치 여부 맵
     */
    public Map<SetupItem, Boolean> checkAll() {
        Map<SetupItem, Boolean> results = new EnumMap<>(SetupItem.class);
        for (SetupItem item : SetupItem.values()) {
            results.put(item, check(item));
        }
        return results;
    }

    // ─────────────────────────────────────────────
    // 개별 항목 검사
    // ─────────────────────────────────────────────

    /**
     * python --version 실행 결과로 Python 3.x 설치 여부를 판별한다.
     * ToolLocator가 PATH 이름과 알려진 설치 경로(설치 직후 PATH 미반영 케이스)를
     * 모두 후보로 제공한다.
     */
    private boolean checkPython() {
        for (String cmd : ToolLocator.candidateCommands("python")) {
            if (isPython3(List.of(cmd, "--version"))) {
                log.debug("[setup] python found: {}", cmd);
                return true;
            }
        }
        return false;
    }

    /**
     * 명령 출력에 "Python 3"이 포함되는지 확인한다.
     * exit 0이어도 Python 2이면 false — BGE-M3는 Python 3 전용.
     */
    private boolean isPython3(List<String> command) {
        String out = runCommandCapture("Python 3", command);
        return out != null && out.contains("Python 3");
    }

    /**
     * nvidia-smi 실행 성공 여부로 드라이버 설치 여부를 판별한다.
     * System32 경로도 직접 탐색해 PATH 미등록 케이스를 처리한다.
     */
    private boolean checkNvidiaDriver() {
        if (runCommand("nvidia-smi", List.of("nvidia-smi"))) return true;

        // nvidia-smi가 PATH에 없더라도 System32에 있으면 설치된 것으로 간주
        Path sys32 = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "nvidia-smi.exe");
        if (Files.exists(sys32)) {
            log.info("[setup] nvidia-smi found at {}", sys32);
            return true;
        }
        return false;
    }

    /**
     * CUDA_PATH 환경변수, nvcc 실행, 기본 설치 디렉토리 순으로 CUDA 설치 여부를 판별한다.
     */
    private boolean checkCuda() {
        // CUDA_PATH 환경변수로 먼저 확인 — 설치 직후 PATH 미반영 상태에서도 탐지
        String cudaPath = System.getenv("CUDA_PATH");
        if (cudaPath != null && !cudaPath.isBlank() && Files.isDirectory(Path.of(cudaPath))) {
            log.info("[setup] CUDA_PATH found: {}", cudaPath);
            return true;
        }

        if (runCommand("CUDA", List.of("nvcc", "--version"))) return true;

        // 설치 스크립트가 CUDA_PATH를 Machine 레벨에 설정해도 실행 중인 이 프로세스는
        // 못 보므로, CUDA Toolkit 기본 설치 디렉토리(v* 하위)를 직접 탐색한다
        Path cudaRoot = Path.of(
                System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                "NVIDIA GPU Computing Toolkit", "CUDA");
        if (Files.isDirectory(cudaRoot)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(cudaRoot, "v*")) {
                for (Path dir : dirs) {
                    if (Files.isDirectory(dir)) {
                        log.info("[setup] CUDA found at {}", dir);
                        return true;
                    }
                }
            } catch (IOException e) {
                log.debug("[setup] CUDA path scan failed: {}", e.getMessage());
            }
        }
        return false;
    }

    /** Cursor 플러그인 sidecar(@cursor/sdk)가 요구하는 Node.js 최소 메이저 버전 */
    private static final int MIN_NODE_MAJOR = 22;

    /**
     * node --version의 메이저 버전이 22 이상인지 확인한다.
     * ToolLocator가 PATH 이름과 MSI 기본 설치 경로(설치 직후 PATH 미반영 케이스)를
     * 모두 후보로 제공한다.
     */
    private boolean checkNodeJs() {
        for (String cmd : ToolLocator.candidateCommands("node")) {
            if (isNodeVersionOk(List.of(cmd, "--version"))) {
                log.debug("[setup] node found: {}", cmd);
                return true;
            }
        }
        return false;
    }

    /**
     * 명령 출력("v22.14.0" 형식)에서 메이저 버전을 파싱해 최소 요구 버전을 검사한다.
     * Node가 있어도 구버전이면 false — sidecar가 22 미만에서 실행을 거부한다.
     */
    private boolean isNodeVersionOk(List<String> command) {
        String out = runCommandCapture("Node.js", command);
        if (out == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("v(\\d+)").matcher(out);
        return m.find() && Integer.parseInt(m.group(1)) >= MIN_NODE_MAJOR;
    }

    // ─────────────────────────────────────────────
    // 공통 유틸
    // ─────────────────────────────────────────────

    /**
     * 명령을 실행하고 TIMEOUT_SEC 내에 종료 코드 0으로 완료되면 true를 반환한다.
     *
     * @param label   로그 식별용 이름
     * @param command 실행할 명령과 인수 목록
     * @return 정상 종료 여부
     */
    private boolean runCommand(String label, List<String> command) {
        return runCommandCapture(label, command) != null;
    }

    /**
     * 명령을 실행하고 정상 종료(exit 0)하면 표준 출력을, 아니면 null을 반환한다.
     *
     * @param label   로그 식별용 이름
     * @param command 실행할 명령과 인수 목록
     * @return 정상 종료 시 stdout+stderr 출력, 실패·타임아웃·미존재 시 null
     */
    private String runCommandCapture(String label, List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // 출력을 소비하지 않으면 버퍼가 가득 찼을 때 프로세스가 블로킹될 수 있고,
            // 현재 스레드에서 직접 읽으면 행(hang)된 프로세스가 타임아웃을 무력화한다
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try {
                    proc.getInputStream().transferTo(buf);
                } catch (IOException ignored) {
                    // 프로세스 강제 종료 시 스트림이 닫히며 발생 — 무시
                }
            }, "setup-output-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[setup] {} timed out", label);
                return null;
            }
            reader.join(1000);
            log.debug("[setup] {} → exit={}", label, proc.exitValue());
            if (proc.exitValue() != 0) return null;
            // python --version 등 콘솔 출력은 플랫폼 기본 인코딩
            return buf.toString(Charset.defaultCharset());
        } catch (IOException e) {
            // 명령 자체가 없으면 IOException 발생 → 미설치로 간주
            log.debug("[setup] {} not found: {}", label, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
