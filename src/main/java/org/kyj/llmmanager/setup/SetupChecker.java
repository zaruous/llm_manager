/*
 * 작성자 : kyj
 * 작성일 : 2026-06-09
 */
package org.kyj.llmmanager.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
     * 지정한 항목의 설치 여부를 확인한다.
     *
     * @param item 확인할 환경 항목
     * @return 설치됐으면 true
     */
    /**
     * 테스트 시 특정 항목을 강제로 실패시키는 오버라이드 맵.
     * 예: overrides.put(SetupItem.PYTHON, false)
     * 프로덕션에서는 비워둔다.
     */
    private final java.util.Map<SetupItem, Boolean> overrides = new java.util.EnumMap<>(SetupItem.class);

    /** 테스트 전용 — 특정 항목의 결과를 강제 지정한다. */
    public SetupChecker forceResult(SetupItem item, boolean result) {
        overrides.put(item, result);
        return this;
    }

    public boolean check(SetupItem item) {
        if (overrides.containsKey(item)) return overrides.get(item);
        return switch (item) {
            case PYTHON        -> checkPython();
            case NVIDIA_DRIVER -> checkNvidiaDriver();
            case CUDA          -> checkCuda();
        };
    }

    // ─────────────────────────────────────────────
    // 개별 항목 검사
    // ─────────────────────────────────────────────

    /**
     * python --version 실행 결과로 Python 3.x 설치 여부를 판별한다.
     * python 명령이 없으면 python3으로 재시도한다.
     */
    private boolean checkPython() {
        return runCommand("Python 3", List.of("python", "--version"))
                || runCommand("Python 3", List.of("python3", "--version"));
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
     * CUDA_PATH 환경변수 존재 또는 nvcc 실행 성공으로 CUDA 설치 여부를 판별한다.
     */
    private boolean checkCuda() {
        // CUDA_PATH 환경변수로 먼저 확인 — 설치 직후 PATH 미반영 상태에서도 탐지
        String cudaPath = System.getenv("CUDA_PATH");
        if (cudaPath != null && !cudaPath.isBlank() && Files.isDirectory(Path.of(cudaPath))) {
            log.info("[setup] CUDA_PATH found: {}", cudaPath);
            return true;
        }

        // nvcc 명령으로 최종 확인
        return runCommand("CUDA", List.of("nvcc", "--version"));
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
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // 출력을 소비하지 않으면 프로세스가 버퍼 가득 찼을 때 블로킹될 수 있다
            proc.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());

            boolean finished = proc.waitFor(TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[setup] {} timed out", label);
                return false;
            }
            boolean ok = proc.exitValue() == 0;
            log.debug("[setup] {} → exit={}", label, proc.exitValue());
            return ok;
        } catch (IOException e) {
            // 명령 자체가 없으면 IOException 발생 → 미설치로 간주
            log.debug("[setup] {} not found: {}", label, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
