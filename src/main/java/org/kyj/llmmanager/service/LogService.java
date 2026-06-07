/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.LogEntry;
import org.kyj.llmmanager.model.ServiceInstance;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 서비스 프로세스의 stdout/stderr를 백그라운드 스레드에서 읽어 ServiceInstance 로그 목록에 추가한다.
 * 로그는 큐에 모은 뒤 짧은 주기로 JavaFX 스레드에서 일괄 추가한다.
 */
public class LogService {
    /** JavaFX 로그 반영 주기. 로그 폭주 시 runLater 큐가 과도하게 쌓이는 것을 막는다. */
    private static final long FLUSH_INTERVAL_MS = 100;

    /** 한 번의 UI 반영에서 처리할 최대 로그 수. UI 스레드 장시간 점유를 방지한다. */
    private static final int MAX_BATCH_SIZE = 1000;

    /** UI 반영 대기 중인 로그 큐. 여러 reader 스레드가 동시에 추가할 수 있다. */
    private final Queue<QueuedLog> pendingLogs = new ConcurrentLinkedQueue<>();

    /** JavaFX runLater 중복 예약 방지 플래그. */
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    /**
     * 로그 스트림 리더 스레드 풀.
     * 서비스마다 stdout/stderr 각 1개씩 스레드를 할당.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "log-reader");
        t.setDaemon(true);
        return t;
    });

    /** pendingLogs를 주기적으로 JavaFX Application Thread에 반영하는 스케줄러. */
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "log-flusher");
        t.setDaemon(true);
        return t;
    });

    public LogService() {
        flusher.scheduleWithFixedDelay(this::scheduleFlush,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 프로세스의 stdout·stderr 스트림 읽기를 시작한다. 서비스 시작 직후 호출.
     *
     * @param instance 로그를 연결할 서비스 인스턴스
     */
    public void attach(ServiceInstance instance) {
        Process process = instance.getProcess();
        if (process == null) return;

        executor.submit(() -> readStream(instance, process, LogEntry.Level.STDOUT));
        executor.submit(() -> readStream(instance, process, LogEntry.Level.STDERR));
    }

    /**
     * 지정 스트림(stdout 또는 stderr)을 줄 단위로 읽어 LogEntry를 생성하고
     * UI 스레드에서 인스턴스에 추가한다.
     *
     * @param instance 로그를 추가할 서비스 인스턴스
     * @param process  로그를 읽을 프로세스
     * @param level    로그 레벨 (STDOUT 또는 STDERR)
     */
    private void readStream(ServiceInstance instance, Process process, LogEntry.Level level) {
        var stream = level == LogEntry.Level.STDOUT
                ? process.getInputStream()
                : process.getErrorStream();
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, resolveCharset(instance)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                enqueue(instance, new LogEntry(level, line));
            }
        } catch (Exception ignored) {}
    }

    /**
     * 앱 내부에서 생성한 시스템 메시지(시작·중지 알림 등)를 로그에 추가한다.
     *
     * @param instance 로그를 추가할 서비스 인스턴스
     * @param message  시스템 로그 메시지
     */
    public void addSystemLog(ServiceInstance instance, String message) {
        enqueue(instance, new LogEntry(LogEntry.Level.SYSTEM, message));
    }

    public void shutdown() {
        executor.shutdownNow();
        flusher.shutdownNow();
        if (Platform.isFxApplicationThread()) {
            flushNow();
        } else {
            Platform.runLater(this::flushNow);
        }
    }

    private void enqueue(ServiceInstance instance, LogEntry entry) {
        pendingLogs.add(new QueuedLog(instance, entry));
    }

    private void scheduleFlush() {
        if (pendingLogs.isEmpty()) return;
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                try {
                    flushNow();
                } finally {
                    flushScheduled.set(false);
                }
            });
        }
    }

    private void flushNow() {
        Map<ServiceInstance, List<LogEntry>> byInstance = new LinkedHashMap<>();
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
            QueuedLog queued = pendingLogs.poll();
            if (queued == null) break;
            byInstance.computeIfAbsent(queued.instance(), key -> new ArrayList<>())
                    .add(queued.entry());
        }
        byInstance.forEach((instance, entries) -> {
            trimBeforeAdd(instance, entries.size());
            instance.getLogs().addAll(entries);
        });
    }

    private void trimBeforeAdd(ServiceInstance instance, int incomingSize) {
        if (instance.getLogs().size() + incomingSize <= 5000) return;
        int removeCount = Math.min(1000, instance.getLogs().size());
        if (removeCount > 0) {
            instance.getLogs().remove(0, removeCount);
        }
    }

    private Charset resolveCharset(ServiceInstance instance) {
        String configured = instance.getDefinition().getLogCharset();
        if (configured == null || configured.isBlank()) {
            return Charset.defaultCharset();
        }
        try {
            return Charset.forName(configured.trim());
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }

    private record QueuedLog(ServiceInstance instance, LogEntry entry) {}
}
