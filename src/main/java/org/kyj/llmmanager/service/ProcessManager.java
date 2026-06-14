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

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 서비스 프로세스의 생명주기(시작·중지·재시작)를 관리한다.
 * 각 서비스는 별도 스레드에서 실행되며, 상태 변경은 JavaFX 스레드에서 처리된다.
 */
public class ProcessManager {
    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    /**
     * 서비스 ID → ServiceInstance 맵.
     * 같은 서비스가 중복 생성되지 않도록 ConcurrentHashMap으로 관리.
     */
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    /** 프로세스 stdout/stderr를 ServiceInstance 로그로 연결하는 서비스 */
    private final LogService logService;

    /** 서비스 상태가 바뀔 때 호출할 콜백. 트레이 메뉴 갱신 등에 사용. */
    private Runnable onStatusChange;

    public ProcessManager(LogService logService) {
        this.logService = logService;
    }

    public void setOnStatusChange(Runnable callback) {
        this.onStatusChange = callback;
    }

    /**
     * 서비스 ID로 기존 인스턴스를 반환하거나 없으면 새로 생성한다.
     *
     * @param def 서비스 정의
     * @return 기존 또는 새로 생성된 ServiceInstance
     */
    public ServiceInstance getOrCreate(ServiceDefinition def) {
        return instances.computeIfAbsent(def.getId(), id -> new ServiceInstance(def));
    }

    public List<ServiceInstance> getAllInstances() {
        return new ArrayList<>(instances.values());
    }

    /**
     * 서비스 ID에 해당하는 인스턴스를 맵에서 제거한다.
     * 서비스 삭제 시 호출해 대시보드·상태 조회에서 제외되도록 한다.
     *
     * @param id 제거할 서비스 ID
     */
    public void remove(String id) {
        instances.remove(id);
    }

    /**
     * 서비스를 별도 스레드에서 시작한다. 이미 RUNNING/STARTING 이면 무시.
     *
     * @param instance 시작할 서비스 인스턴스
     */
    public void start(ServiceInstance instance) {
        if (instance.getStatus() == ServiceStatus.RUNNING
                || instance.getStatus() == ServiceStatus.STARTING) return;
        new Thread(() -> doStart(instance), "start-" + instance.getDefinition().getName()).start();
    }

    /**
     * 실제 프로세스를 생성하고 로그 스트림을 연결한다.
     * 프로세스 종료 코드에 따라 STOPPED/ERROR로 전환.
     *
     * @param instance 시작할 서비스 인스턴스
     */
    private void doStart(ServiceInstance instance) {
        ServiceDefinition def = instance.getDefinition();
        setStatus(instance, ServiceStatus.STARTING);
        logService.addSystemLog(instance, "Starting " + def.getName() + "...");

        try {
            String command = CommandBuilder.buildStartCommand(def);
            logService.addSystemLog(instance, "Command: " + command);

            String workDir = def.getWorkingDir() != null ? def.getWorkingDir()
                    : def.getInstallDir();

            List<String> tokens = CommandBuilder.splitCommand(command);
            // bare "java" / "java.exe"를 이 프로세스를 실행 중인 JVM 절대 경로로 치환한다.
            // jpackage 번들 JRE와 시스템 JAVA_HOME이 다를 때 버전 불일치를 방지한다.
            if (!tokens.isEmpty()) {
                String exe = tokens.get(0);
                if (exe.equalsIgnoreCase("java") || exe.equalsIgnoreCase("java.exe")) {
                    tokens.set(0, PlatformUtil.getCurrentJavaExecutable());
                }
            }

            ProcessBuilder pb = new ProcessBuilder(tokens);
            if (workDir != null && !workDir.isBlank()) {
                File dir = new File(workDir);
                if (!dir.exists() || !dir.isDirectory()) {
                    logService.addSystemLog(instance,
                            "오류: 설치 경로가 존재하지 않습니다 → " + workDir);
                    logService.addSystemLog(instance,
                            "서비스 수정(더블클릭 → 수정)에서 설치 경로를 JAR/실행파일이 있는 폴더로 변경해 주세요.");
                    setStatus(instance, ServiceStatus.ERROR);
                    return;
                }
                pb.directory(dir);
            }
            pb.environment().putAll(def.getEnvVars());
            pb.redirectErrorStream(false);

            Process process = pb.start();
            instance.setProcess(process);
            instance.setStartTime(Instant.now());

            // 이 앱이 실행시킨 프로세스임을 PID 파일로 마킹
            try {
                PidFileManager.write(def, process.pid());
            } catch (Exception e) {
                log.warn("Failed to write PID file for {}", def.getName(), e);
            }
            setStatus(instance, ServiceStatus.RUNNING);

            logService.attach(instance);

            int exit = process.waitFor();
            if (instance.getStatus() == ServiceStatus.RUNNING) {
                logService.addSystemLog(instance, "Process exited with code " + exit);
                setStatus(instance, exit == 0 ? ServiceStatus.STOPPED : ServiceStatus.ERROR);
            }
        } catch (Exception e) {
            log.error("Failed to start service: {}", def.getName(), e);
            logService.addSystemLog(instance, "Error: " + e.getMessage());
            setStatus(instance, ServiceStatus.ERROR);
        }
    }

    /**
     * 실행 중인 프로세스를 종료한다.
     * Windows는 taskkill /F, Unix는 destroy() 사용. 5초 대기 후 강제 종료.
     *
     * @param instance 중지할 서비스 인스턴스
     */
    public void stop(ServiceInstance instance) {
        if (instance.getStatus() != ServiceStatus.RUNNING
                && instance.getStatus() != ServiceStatus.STARTING) return;

        setStatus(instance, ServiceStatus.STOPPING);
        logService.addSystemLog(instance, "Stopping " + instance.getDefinition().getName() + "...");

        Process process = instance.getProcess();
        ServiceDefinition def = instance.getDefinition();

        if (process == null) {
            // process 객체 없음 — 고아 프로세스인 경우 PID 파일로 종료 시도
            OptionalLong savedPid = PidFileManager.read(def);
            if (savedPid.isPresent()) {
                long pid = savedPid.getAsLong();
                new Thread(() -> {
                    try {
                        logService.addSystemLog(instance, "고아 프로세스 종료 시도 (PID=" + pid + ")");
                        if (PlatformUtil.isWindows()) {
                            Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/PID", String.valueOf(pid)})
                                    .waitFor(3, TimeUnit.SECONDS);
                        } else {
                            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                        }
                        PidFileManager.delete(def);
                        setStatus(instance, ServiceStatus.STOPPED);
                        logService.addSystemLog(instance, "Stopped.");
                    } catch (Exception e) {
                        log.error("Error stopping orphan process", e);
                        setStatus(instance, ServiceStatus.ERROR);
                    }
                }, "stop-" + def.getName()).start();
            } else {
                setStatus(instance, ServiceStatus.STOPPED);
            }
            return;
        }

        // PID 파일과 현재 프로세스 PID 일치 검증
        OptionalLong savedPid = PidFileManager.read(def);
        savedPid.ifPresent(pid -> {
            if (pid != process.pid()) {
                log.warn("PID mismatch for {}: file={}, process={} — stopping anyway",
                        def.getName(), pid, process.pid());
                logService.addSystemLog(instance,
                        "경고: PID 불일치 (파일=" + pid + ", 프로세스=" + process.pid() + ")");
            }
        });

        new Thread(() -> {
            try {
                if (PlatformUtil.isWindows()) {
                    Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/PID",
                            String.valueOf(process.pid())});
                } else {
                    process.destroy();
                }
                boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                if (!terminated) process.destroyForcibly();
                PidFileManager.delete(def);
                setStatus(instance, ServiceStatus.STOPPED);
                logService.addSystemLog(instance, "Stopped.");
            } catch (Exception e) {
                log.error("Error stopping process", e);
                setStatus(instance, ServiceStatus.ERROR);
            }
        }, "stop-" + def.getName()).start();
    }

    /**
     * 서비스를 중지하고 완전히 종료된 후 다시 시작한다.
     * stop 후 최대 4초 대기.
     *
     * @param instance 재시작할 서비스 인스턴스
     */
    public void restart(ServiceInstance instance) {
        stop(instance);
        new Thread(() -> {
            try {
                // Wait for process to fully stop
                for (int i = 0; i < 20; i++) {
                    if (instance.getStatus() == ServiceStatus.STOPPED
                            || instance.getStatus() == ServiceStatus.ERROR) break;
                    Thread.sleep(200);
                }
                Thread.sleep(500);
                start(instance);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "restart-" + instance.getDefinition().getName()).start();
    }

    /**
     * JavaFX Application Thread에서 상태를 변경하고 onStatusChange 콜백을 호출한다.
     *
     * @param instance 상태를 변경할 서비스 인스턴스
     * @param status   새로운 서비스 상태
     */
    private void setStatus(ServiceInstance instance, ServiceStatus status) {
        Platform.runLater(() -> {
            instance.setStatus(status);
            if (onStatusChange != null) onStatusChange.run();
        });
    }

    /**
     * 실행 중인 모든 서비스를 중지한다. 앱 종료 시 호출.
     */
    public void stopAll() {
        instances.values().forEach(inst -> {
            if (inst.getStatus() == ServiceStatus.RUNNING) stop(inst);
        });
    }

    /**
     * 실행 중인 모든 서비스를 호출 스레드에서 동기적으로 종료한다.
     * JVM ShutdownHook에서 호출 — 새 스레드를 생성하면 종료 전에 완료가 보장되지 않으므로
     * 블로킹 방식으로 직접 처리한다. UI 업데이트(Platform.runLater)는 수행하지 않는다.
     */
    public void stopAllSync() {
        for (ServiceInstance inst : instances.values()) {
            if (inst.getStatus() != ServiceStatus.RUNNING
                    && inst.getStatus() != ServiceStatus.STARTING) continue;
            ServiceDefinition def = inst.getDefinition();
            Process process = inst.getProcess();
            try {
                long pid;
                if (process != null) {
                    // PID 파일과 현재 프로세스 PID 일치 검증
                    OptionalLong savedPid = PidFileManager.read(def);
                    if (savedPid.isPresent() && savedPid.getAsLong() != process.pid()) {
                        log.warn("ShutdownHook PID mismatch for {}: file={}, process={}",
                                def.getName(), savedPid.getAsLong(), process.pid());
                    }
                    pid = process.pid();
                } else {
                    // 고아 프로세스: PID 파일에서 PID 조회
                    OptionalLong savedPid = PidFileManager.read(def);
                    if (savedPid.isEmpty()) continue;
                    pid = savedPid.getAsLong();
                }

                if (PlatformUtil.isWindows()) {
                    new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid))
                            .start().waitFor(3, TimeUnit.SECONDS);
                } else {
                    if (process != null) process.destroy();
                    else ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
                if (process != null && !process.waitFor(5, TimeUnit.SECONDS))
                    process.destroyForcibly();

                PidFileManager.delete(def);
                log.info("ShutdownHook: stopped {} (PID={})", def.getName(), pid);
            } catch (Exception e) {
                log.warn("ShutdownHook: failed to stop {}", def.getName(), e);
            }
        }
    }
}
