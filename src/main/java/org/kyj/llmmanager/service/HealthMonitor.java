/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.*;
import javafx.application.Platform;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 실행 중인 서비스의 HTTP 헬스체크를 주기적으로 수행한다.
 * 포트가 있는 서비스만 체크.
 */
public class HealthMonitor {
    /** 헬스체크 대상 인스턴스 목록을 가져오는 ProcessManager */
    private final ProcessManager processManager;

    /** 타임아웃 2초로 설정된 HTTP 클라이언트 */
    private final HttpClient httpClient;

    /** 헬스체크 및 메모리 수집 주기 (초, 1~10). application.yml health-check-interval 값. */
    private final int intervalSeconds;

    /** intervalSeconds 간격으로 checkAll을 실행하는 단일 스레드 스케줄러 */
    private ScheduledExecutorService scheduler;

    /** 첫 번째 체크 여부 — 최초 1회만 고아 프로세스 스캔을 수행한다. */
    private final AtomicBoolean firstCheck = new AtomicBoolean(true);

    /** OSHI OS 인터페이스 — 프로세스별 메모리 조회에 사용. 초기화 실패 시 null. */
    private final OperatingSystem os;

    public HealthMonitor(ProcessManager processManager, int intervalSeconds) {
        this.processManager  = processManager;
        this.intervalSeconds = Math.max(1, Math.min(10, intervalSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        OperatingSystem tmpOs = null;
        try {
            tmpOs = new SystemInfo().getOperatingSystem();
        } catch (Exception ignored) {}
        this.os = tmpOs;
    }

    /**
     * 앱 시작 후 5초 뒤부터 10초 간격으로 헬스체크를 실행한다.
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::checkAll, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * RUNNING 상태이고 port가 있는 모든 서비스에 GET 요청을 보낸다.
     * 최초 1회는 PID 파일 기반으로 고아 프로세스를 감지해 RUNNING으로 마킹한다.
     */
    private void checkAll() {
        boolean isFirst = firstCheck.compareAndSet(true, false);

        for (ServiceInstance instance : processManager.getAllInstances()) {
            ServiceDefinition def = instance.getDefinition();

            if (isFirst && instance.getStatus() != ServiceStatus.RUNNING) {
                // PID 파일 읽기 → 해당 PID의 프로세스가 살아있으면 고아로 간주
                OptionalLong savedPid = PidFileManager.read(def);
                if (savedPid.isPresent()) {
                    long pid = savedPid.getAsLong();
                    boolean alive = ProcessHandle.of(pid)
                            .map(ProcessHandle::isAlive).orElse(false);
                    if (alive) {
                        Platform.runLater(() -> instance.setStatus(ServiceStatus.RUNNING));
                    } else {
                        // 프로세스 이미 죽었으면 PID 파일 정리
                        PidFileManager.delete(def);
                    }
                }
                continue;
            }

            // 정기 체크: RUNNING 상태인 서비스만
            if (instance.getStatus() != ServiceStatus.RUNNING) continue;

            // 프로세스 메모리(RSS) 수집
            updateMemory(instance);

            // HTTP 헬스체크 (port가 있는 서비스만)
            if (def.getPort() == null) continue;
            String url = "http://localhost:" + def.getPort() + def.getHealthCheckPath();
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(2))
                        .GET().build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // 헬스체크 실패 — 아직 기동 중일 수 있으므로 상태 변경 없음
            }
        }
    }

    /**
     * OSHI로 프로세스 RSS 메모리를 읽어 ServiceInstance에 반영한다.
     * PID를 특정할 수 없거나 OSHI 초기화 실패 시 조용히 무시한다.
     *
     * @param instance 메모리를 갱신할 서비스 인스턴스
     */
    private void updateMemory(ServiceInstance instance) {
        if (os == null) return;
        long pid = instance.getPid();
        if (pid < 0) return;
        try {
            OSProcess proc = os.getProcess((int) pid);
            if (proc == null) return;
            long rss     = proc.getResidentSetSize();
            long virtual = proc.getVirtualSize();
            Platform.runLater(() -> {
                instance.setMemoryBytes(rss);
                instance.setVirtualMemoryBytes(virtual);
            });
        } catch (Exception ignored) {}
    }

    /**
     * 스케줄러를 즉시 종료한다. 앱 종료 시 호출.
     */
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
