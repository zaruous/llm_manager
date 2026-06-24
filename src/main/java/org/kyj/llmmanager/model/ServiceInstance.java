/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 런타임에 실행 중인 서비스 인스턴스.
 * ServiceDefinition(정의) + Process(프로세스) + 로그 + 상태를 묶는다.
 * JavaFX Observable을 사용해 UI가 상태 변경을 자동으로 감지한다.
 */
public class ServiceInstance {

    /** 이 인스턴스의 서비스 정의. 수정 다이얼로그 저장 시 updateDefinition()으로 교체 가능. */
    private ServiceDefinition definition;

    /** 현재 서비스 상태. JavaFX Property로 UI 바인딩 가능. */
    private final ObjectProperty<ServiceStatus> status =
            new SimpleObjectProperty<>(ServiceStatus.NOT_INSTALLED);

    /** 서비스 stdout/stderr/시스템 로그. UI에서 ObservableList로 실시간 반영. */
    private final ObservableList<LogEntry> logs = FXCollections.observableArrayList();

    /** 실행 중인 OS 프로세스 객체. null이면 미실행. */
    private Process process;

    /** OS 프로세스 ID. 미실행 시 -1. */
    private long pid = -1;

    /** 프로세스 시작 시각. 업타임 계산에 사용. */
    private Instant startTime;

    /** 프로세스 RSS(Resident Set Size) 메모리 사용량 (bytes). 미실행 시 0. */
    private final LongProperty memoryBytes = new SimpleLongProperty(0);

    /** 프로세스 가상 메모리 총 할당량 (bytes). RSS의 분모로 사용. 미실행 시 0. */
    private final LongProperty virtualMemoryBytes = new SimpleLongProperty(0);

    /** 최근 20개 RSS 샘플. 스파크라인 그래프에 사용. JavaFX 스레드에서만 접근. */
    private final Deque<Long> memoryHistory = new ArrayDeque<>();

    public ServiceInstance(ServiceDefinition definition) {
        this.definition = definition;
    }

    public ServiceDefinition getDefinition() { return definition; }

    /**
     * 서비스 정의를 교체한다. 설정 수정 후 인스턴스를 재생성하지 않고 정의만 갱신할 때 사용.
     * 실행 중인 프로세스에는 영향을 주지 않으며, 다음 시작 시 새 정의가 적용된다.
     *
     * @param definition 새 서비스 정의
     */
    public void updateDefinition(ServiceDefinition definition) {
        this.definition = definition;
    }

    public ServiceStatus getStatus() { return status.get(); }
    public void setStatus(ServiceStatus s) { status.set(s); }
    public ObjectProperty<ServiceStatus> statusProperty() { return status; }

    public ObservableList<LogEntry> getLogs() { return logs; }

    public Process getProcess() { return process; }
    public void setProcess(Process process) {
        this.process = process;
        this.pid = process != null ? process.pid() : -1;
    }

    public long getPid() { return pid; }

    /**
     * PID 파일로 발견한 외부/고아 프로세스를 표시한다.
     * Process 객체는 없지만 stop()은 PID 파일을 통해 종료할 수 있다.
     *
     * @param pid 감지한 OS 프로세스 ID
     */
    public void attachExternalPid(long pid) {
        this.process = null;
        this.pid = pid;
        this.startTime = Instant.now();
    }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public long getMemoryBytes() { return memoryBytes.get(); }

    /**
     * RSS 메모리를 갱신하고 스파크라인용 이력에 추가한다.
     * 최대 20개 샘플만 유지한다.
     *
     * @param bytes 최신 RSS 바이트 값
     */
    public void setMemoryBytes(long bytes) {
        memoryBytes.set(bytes);
        if (memoryHistory.size() >= 20) memoryHistory.pollFirst();
        memoryHistory.addLast(bytes);
    }

    public LongProperty memoryBytesProperty() { return memoryBytes; }

    public long getVirtualMemoryBytes() { return virtualMemoryBytes.get(); }
    public void setVirtualMemoryBytes(long bytes) { virtualMemoryBytes.set(bytes); }

    /** 스파크라인용 RSS 이력 스냅샷을 반환한다. */
    public List<Long> getMemoryHistory() { return new ArrayList<>(memoryHistory); }

    /**
     * 시작 시각 기준 경과 시간을 HH:mm:ss 형식으로 반환. 미시작이면 '-'.
     *
     * @return 업타임 문자열
     */
    public String getUptimeString() {
        if (startTime == null) return "-";
        long secs = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    /**
     * 로그를 추가한다.
     * 5000줄 초과 시 앞 1000줄을 제거해 메모리 과다 사용을 방지한다.
     *
     * @param entry 추가할 로그 항목
     */
    public void addLog(LogEntry entry) {
        if (logs.size() > 5000) logs.remove(0, 1000);
        logs.add(entry);
    }
}
