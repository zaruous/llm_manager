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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 서비스 프로세스의 stdout/stderr를 백그라운드 스레드에서 읽어 ServiceInstance 로그 목록에 추가한다.
 * JavaFX Platform.runLater로 UI 스레드에서만 로그를 추가한다.
 */
public class LogService {
    /**
     * 로그 스트림 리더 스레드 풀.
     * 서비스마다 stdout/stderr 각 1개씩 스레드를 할당.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "log-reader");
        t.setDaemon(true);
        return t;
    });

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
        try (var reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String msg = line;
                final LogEntry entry = new LogEntry(level, msg);
                Platform.runLater(() -> instance.addLog(entry));
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
        Platform.runLater(() -> instance.addLog(new LogEntry(LogEntry.Level.SYSTEM, message)));
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
