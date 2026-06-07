/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.util.CommandBuilder;
import org.kyj.llmmanager.util.PlatformUtil;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 서비스의 설치(git clone + 의존성 설치)와 설치 여부 확인을 담당한다.
 */
public class InstallationService {
    private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

    /**
     * 설치 진행 상황을 UI에 실시간으로 전달하는 콜백 인터페이스.
     */
    public interface ProgressCallback {
        /**
         * 설치 로그 메시지를 전달한다.
         *
         * @param message 설치 중 발생한 로그 메시지
         */
        void onLog(String message);

        /**
         * 설치 완료를 알린다. success=false이면 실패.
         *
         * @param success 설치 성공 여부
         */
        void onDone(boolean success);
    }

    /**
     * 별도 스레드에서 설치를 실행하고 진행 상황을 콜백으로 전달한다.
     *
     * @param def      설치할 서비스 정의
     * @param instance 설치 대상 서비스 인스턴스
     * @param cb       진행 상황 콜백
     */
    public void install(ServiceDefinition def, ServiceInstance instance, ProgressCallback cb) {
        new Thread(() -> doInstall(def, instance, cb), "install-" + def.getName()).start();
    }

    /**
     * 1) repoUrl이 있으면 git clone, 2) installCommands를 순서대로 실행.
     * 실패 시 즉시 중단.
     *
     * @param def      설치할 서비스 정의
     * @param instance 설치 대상 서비스 인스턴스
     * @param cb       진행 상황 콜백
     */
    private void doInstall(ServiceDefinition def, ServiceInstance instance, ProgressCallback cb) {
        Platform.runLater(() -> instance.setStatus(ServiceStatus.INSTALLING));

        try {
            // Step 1: git clone if repoUrl is set
            if (def.getRepoUrl() != null && !def.getRepoUrl().isBlank()) {
                Path installPath = Path.of(def.getInstallDir());
                if (!Files.exists(installPath)) {
                    emit(cb, "Cloning " + def.getRepoUrl() + " ...");
                    runCmd("git clone " + def.getRepoUrl() + " \"" + installPath + "\"",
                            null, cb);
                } else {
                    emit(cb, "Directory already exists, skipping clone.");
                }
            }

            // Step 2: run install commands
            String workDir = def.getWorkingDir() != null ? def.getWorkingDir() : def.getInstallDir();
            for (String cmd : def.getInstallCommands()) {
                emit(cb, "$ " + cmd);
                boolean ok = runCmd(cmd, workDir, cb);
                if (!ok) {
                    emit(cb, "Command failed: " + cmd);
                    Platform.runLater(() -> instance.setStatus(ServiceStatus.ERROR));
                    cb.onDone(false);
                    return;
                }
            }

            Platform.runLater(() -> instance.setStatus(ServiceStatus.INSTALLED));
            emit(cb, "Installation complete.");
            cb.onDone(true);

        } catch (Exception e) {
            log.error("Installation failed", e);
            emit(cb, "Error: " + e.getMessage());
            Platform.runLater(() -> instance.setStatus(ServiceStatus.ERROR));
            cb.onDone(false);
        }
    }

    /**
     * 서비스가 설치되어 있는지 확인한다.
     *
     * <p>판단 순서:
     * <ol>
     *   <li>installDir 미설정 → 항상 true (경로 관리 불필요한 서비스)</li>
     *   <li>installDir 미존재 → false</li>
     *   <li>startCommand에서 실행 파일 추출 성공 → 해당 파일 존재 여부</li>
     *   <li>실행 파일 추출 불가 → installDir 존재 여부로 fallback</li>
     * </ol>
     *
     * @param def 확인할 서비스 정의
     * @return 실행 파일(또는 설치 디렉토리)이 실제로 존재하면 true
     */
    public boolean isInstalled(ServiceDefinition def) {
        if (def.getInstallDir() == null || def.getInstallDir().isBlank()) return true;

        Path installPath = Path.of(def.getInstallDir());
        if (!Files.exists(installPath)) return false;

        // startCommand에서 실행 파일을 추출해 존재 여부로 판단
        Path mainFile = resolveMainFile(def);
        if (mainFile != null) {
            return Files.exists(mainFile);
        }

        // 실행 파일을 특정할 수 없으면 디렉토리 존재 여부로 fallback
        return true;
    }

    /**
     * startCommand를 파싱해 실제 실행 파일 경로를 반환한다.
     *
     * <ul>
     *   <li>{@code java -jar app.jar} → installDir/app.jar</li>
     *   <li>{@code python server.py}  → workingDir/server.py</li>
     *   <li>{@code node index.js}     → workingDir/index.js</li>
     *   <li>{@code ./start.sh}        → workingDir/start.sh</li>
     *   <li>{@code python -m uvicorn} → null (모듈 실행, 파일 특정 불가)</li>
     * </ul>
     *
     * @param def 서비스 정의
     * @return 실행 파일의 절대 경로, 특정 불가이면 null
     */
    private Path resolveMainFile(ServiceDefinition def) {
        String cmd = def.getStartCommand();
        if (cmd == null || cmd.isBlank()) return null;

        List<String> tokens = CommandBuilder.splitCommand(cmd);
        String fileName = null;

        // Case 1: java -jar xxx.jar → -jar 다음 토큰
        for (int i = 0; i < tokens.size() - 1; i++) {
            if ("-jar".equalsIgnoreCase(tokens.get(i))) {
                fileName = tokens.get(i + 1);
                break;
            }
        }

        // Case 2: python/node/shell → 첫 번째 커맨드 이후 파일 확장자가 있는 토큰
        if (fileName == null) {
            for (int i = 1; i < tokens.size(); i++) {
                String t = tokens.get(i).replaceFirst("^\\.[\\\\/]", "");  // ./ 또는 .\ 제거
                // 플래그(-로 시작), 모듈(-m 다음 모듈명), 콜론 포함(uvicorn main:app) 제외
                if (!t.startsWith("-") && t.contains(".") && !t.contains(":")) {
                    fileName = t;
                    break;
                }
            }
        }

        if (fileName == null) return null;

        // workingDir 우선, 없으면 installDir 기준으로 경로 구성
        String baseDir = (def.getWorkingDir() != null && !def.getWorkingDir().isBlank())
                ? def.getWorkingDir()
                : def.getInstallDir();

        if (baseDir == null || baseDir.isBlank()) return null;
        return Path.of(baseDir).resolve(fileName);
    }

    /**
     * 명령어를 OS 셸(cmd.exe / bash)로 실행하고 출력 라인을 콜백으로 스트리밍한다.
     * 종료 코드 0이면 true 반환.
     *
     * @param command 실행할 명령어 문자열
     * @param workDir 작업 디렉토리 경로 (null이면 현재 디렉토리)
     * @param cb      진행 상황 콜백
     * @return 명령어 성공 여부 (종료 코드 0이면 true)
     */
    private boolean runCmd(String command, String workDir, ProgressCallback cb) {
        try {
            String[] cmd = PlatformUtil.isWindows()
                    ? new String[]{"cmd.exe", "/c", command}
                    : new String[]{"bash", "-c", command};

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(new File(workDir));
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    emit(cb, line);
                }
            }

            return process.waitFor() == 0;
        } catch (Exception e) {
            emit(cb, "Error running command: " + e.getMessage());
            return false;
        }
    }

    private void emit(ProgressCallback cb, String msg) {
        cb.onLog(msg);
    }
}
