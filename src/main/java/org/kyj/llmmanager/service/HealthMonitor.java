/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.*;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    /** 10초 간격으로 checkAll을 실행하는 단일 스레드 스케줄러 */
    private ScheduledExecutorService scheduler;

    /** 첫 번째 체크 여부 — 최초 1회만 고아 프로세스 스캔을 수행한다. */
    private final AtomicBoolean firstCheck = new AtomicBoolean(true);

    public HealthMonitor(ProcessManager processManager) {
        this.processManager = processManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
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
        scheduler.scheduleAtFixedRate(this::checkAll, 5, 10, TimeUnit.SECONDS);
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

            // 정기 체크: RUNNING 상태이고 port가 있는 서비스만
            if (instance.getStatus() != ServiceStatus.RUNNING) continue;
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
     * 스케줄러를 즉시 종료한다. 앱 종료 시 호출.
     */
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
