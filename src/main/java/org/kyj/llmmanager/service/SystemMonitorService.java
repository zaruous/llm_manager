/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OSHI 라이브러리를 사용해 시스템 CPU·메모리 사용량을 2초 간격으로 수집한다.
 * UI 스레드 블로킹을 방지하기 위해 전용 백그라운드 스레드에서 실행된다.
 * UI는 getter 메서드로 최신값을 읽어 표시한다.
 */
public class SystemMonitorService {
    private static final Logger log = LoggerFactory.getLogger(SystemMonitorService.class);

    /** OSHI CPU 정보 */
    private CentralProcessor processor;
    /** OSHI 메모리 정보 */
    private GlobalMemory memory;
    /** CPU 측정 간격 계산용 이전 틱 스냅샷 */
    private long[] prevTicks;

    /** 2초 간격 수집 스케줄러 */
    private ScheduledExecutorService scheduler;

    // ── 최신 측정값 (volatile: 백그라운드 → JavaFX 스레드 가시성 보장) ──
    /** 시스템 CPU 사용률 (0.0 ~ 1.0) */
    private volatile double cpuLoad    = 0.0;
    /** 사용 중인 물리 메모리 (bytes) */
    private volatile long   usedMem    = 0;
    /** 전체 물리 메모리 (bytes) */
    private volatile long   totalMem   = 0;
    /** 논리 코어 수 */
    private volatile int    logicalCores  = 0;
    /** 물리 코어 수 */
    private volatile int    physicalCores = 0;
    /** OS 이름 */
    private volatile String osName = "";

    /**
     * OSHI를 초기화하고 2초 간격 수집을 시작한다.
     * AppContext.init() 에서 호출한다.
     */
    public void start() {
        try {
            SystemInfo si = new SystemInfo();
            processor    = si.getHardware().getProcessor();
            memory       = si.getHardware().getMemory();
            prevTicks    = processor.getSystemCpuLoadTicks();
            logicalCores  = processor.getLogicalProcessorCount();
            physicalCores = processor.getPhysicalProcessorCount();
            osName = si.getOperatingSystem().toString();

            // 초기값 수집 (첫 번째 tick은 기준값 역할만 함)
            totalMem = memory.getTotal();
            usedMem  = totalMem - memory.getAvailable();

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "system-monitor");
                t.setDaemon(true);
                return t;
            });
            // 2초 후 첫 측정, 이후 2초 간격
            scheduler.scheduleAtFixedRate(this::collect, 2, 2, TimeUnit.SECONDS);
            log.info("[Monitor] 시스템 모니터 시작 — {}코어(논리) / 총 메모리 {}",
                    logicalCores, formatBytes(totalMem));

        } catch (Exception e) {
            log.warn("[Monitor] 시스템 모니터 초기화 실패: {}", e.getMessage());
        }
    }

    /** 수집 작업. 백그라운드 스레드에서만 호출된다. */
    private void collect() {
        try {
            // CPU: 이전 틱과 현재 틱 차이로 사용률 계산
            cpuLoad  = processor.getSystemCpuLoadBetweenTicks(prevTicks);
            prevTicks = processor.getSystemCpuLoadTicks();

            // 메모리
            totalMem = memory.getTotal();
            usedMem  = totalMem - memory.getAvailable();
        } catch (Exception e) {
            log.debug("[Monitor] 수집 오류: {}", e.getMessage());
        }
    }

    /** 스케줄러를 종료한다. AppContext.shutdown() 에서 호출한다. */
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ── Getters ─────────────────────────────────────────────────────

    /** 시스템 CPU 사용률 (0.0 ~ 1.0) */
    public double getCpuLoad()      { return cpuLoad; }
    /** 사용 중인 물리 메모리 (bytes) */
    public long   getUsedMemory()   { return usedMem; }
    /** 전체 물리 메모리 (bytes) */
    public long   getTotalMemory()  { return totalMem; }
    /** 논리 코어 수 */
    public int    getLogicalCores() { return logicalCores; }
    /** 물리 코어 수 */
    public int    getPhysicalCores(){ return physicalCores; }
    /** OS 이름 문자열 */
    public String getOsName()       { return osName; }

    /** bytes → 사람이 읽기 쉬운 단위 문자열 변환 */
    public static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)
            return String.format("%.0f MB", bytes / (1024.0 * 1024));
        return String.format("%.0f KB", bytes / 1024.0);
    }
}
