/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 서비스 프로세스의 PID를 파일로 기록·조회·삭제하고, 기록된 PID가 아직 같은 프로세스인지 검증하는 유틸리티.
 *
 * <p>PID 파일 위치 결정 규칙:
 * <ol>
 *   <li>installDir이 있으면 {@code <installDir>/.llm-manager-<serviceId>.pid}</li>
 *   <li>installDir이 없으면 {@code ~/.llm-manager/pids/<serviceId>.pid}</li>
 * </ol>
 *
 * <p>파일명에 서비스 ID를 포함하는 이유: 같은 서비스 팩을 두 번 등록해
 * installDir이 겹치더라도 서로의 PID 파일을 덮어쓰거나 오인 복원하지 않도록
 * 서비스 정의별로 파일을 분리한다.
 *
 * <p>파일 형식: 1행 PID, 2행(선택) {@code start=<epochMillis>} — 프로세스 시작 시각.
 * Windows는 PID를 빠르게 재사용하므로(실측: 300회 spawn 중 10%가 재사용) PID만으로는
 * "기록한 그 프로세스"임을 보장할 수 없다. {@link #check}가 시작 시각(없으면 파일 mtime)과
 * 비교해 재배정된 PID를 걸러낸다 — 2026-09-13 업데이트 실패의 유력 원인이 낡은 PID로의
 * {@code taskkill /F /T}였다.
 */
public class PidFileManager {
    private static final Logger log = LoggerFactory.getLogger(PidFileManager.class);

    /** 구버전(서비스 ID 미포함) PID 파일 이름 — 업그레이드 시 읽기 fallback 용도로만 유지 */
    private static final String LEGACY_PID_FILE_NAME = ".llm-manager.pid";

    /** installDir이 없는 서비스의 fallback PID 저장 디렉토리 */
    private static final Path FALLBACK_DIR =
            Path.of(System.getProperty("user.home"), ".llm-manager", "pids");

    /** 2행 시작 시각 키 */
    private static final String START_KEY = "start=";

    /**
     * 기록된 시작 시각과 실제 시작 시각의 허용 오차.
     * 기록은 프로세스 시작 직후에 이뤄지므로 정상이면 수 ms 차이지만, 시계 정밀도를 감안해 넉넉히 둔다.
     */
    private static final Duration START_TOLERANCE = Duration.ofSeconds(2);

    /** PID 파일에 기록된 프로세스의 현재 상태 판정. */
    public enum PidCheck {
        /** 파일이 없거나 PID를 읽을 수 없다 */
        NO_FILE,
        /** 그 PID의 프로세스가 없다 — 이미 종료 */
        DEAD,
        /** 살아 있고 시작 시각이 기록과 일치 — 기록한 그 프로세스다 */
        LIVE_MATCH,
        /** 살아 있지만 기록보다 나중에 시작됐다 — PID가 다른 프로세스에 재배정됨 */
        STALE,
        /** 살아 있지만 시작 시각을 조회할 수 없어 동일성을 확인할 수 없다 */
        UNVERIFIABLE
    }

    private PidFileManager() {}

    /**
     * PID 파일에 PID를 기록한다. 서비스 프로세스 시작 직후 호출.
     * 프로세스 시작 시각을 조회할 수 있으면 2행에 함께 기록해 {@link #check}의 정밀도를 높인다.
     *
     * @param def 서비스 정의
     * @param pid 기록할 PID
     * @throws IOException 파일 쓰기 실패 시
     */
    public static void write(ServiceDefinition def, long pid) throws IOException {
        Path pidFile = resolve(def);
        Files.createDirectories(pidFile.getParent());
        StringBuilder content = new StringBuilder(String.valueOf(pid));
        ProcessHandle.of(pid).flatMap(h -> h.info().startInstant())
                .ifPresent(start -> content.append('\n').append(START_KEY).append(start.toEpochMilli()));
        Files.writeString(pidFile, content.toString());
        // 구버전 파일이 남아 있으면 이후 read()가 다른 서비스 것으로 오인하지 않도록 정리
        deleteLegacy(def);
        log.debug("PID file written: {} → pid={}", pidFile, pid);
    }

    /**
     * PID 파일에서 PID를 읽는다.
     *
     * @param def 서비스 정의
     * @return 저장된 PID. 파일 없거나 파싱 실패 시 empty.
     */
    public static OptionalLong read(ServiceDefinition def) {
        Path pidFile = existingFile(def);
        if (pidFile == null) return OptionalLong.empty();
        try {
            return OptionalLong.of(parsePid(Files.readString(pidFile)));
        } catch (Exception e) {
            log.warn("Failed to read PID file: {}", pidFile, e);
            return OptionalLong.empty();
        }
    }

    /**
     * PID 파일에 기록된 프로세스가 지금도 "그 프로세스"인지 판정한다.
     * 살아 있는지만 보지 않고 시작 시각을 대조한다 — 재배정된 PID를 종료하면 무관한 프로그램을 죽인다.
     *
     * <p>비교 기준: 2행 {@code start=}가 있으면 그 값과 ±{@link #START_TOLERANCE}, 없으면(구버전 파일)
     * 파일 mtime — 파일은 프로세스 시작 뒤에 쓰이므로 시작 시각이 mtime보다 뒤면 재배정된 것이다.
     *
     * @param def 서비스 정의
     * @return 판정. 호출 측은 {@link PidCheck#LIVE_MATCH}일 때만 종료·복원 대상으로 삼아야 한다
     */
    public static PidCheck check(ServiceDefinition def) {
        Path pidFile = existingFile(def);
        if (pidFile == null) return PidCheck.NO_FILE;

        String text;
        long pid;
        try {
            text = Files.readString(pidFile);
            pid = parsePid(text);
        } catch (Exception e) {
            log.warn("Failed to read PID file: {}", pidFile, e);
            return PidCheck.NO_FILE;
        }

        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) return PidCheck.DEAD;

        Optional<Instant> actualStart = handle.get().info().startInstant();
        if (actualStart.isEmpty()) return PidCheck.UNVERIFIABLE;

        Optional<Instant> recordedStart = parseStart(text);
        if (recordedStart.isPresent()) {
            Duration diff = Duration.between(recordedStart.get(), actualStart.get()).abs();
            return diff.compareTo(START_TOLERANCE) <= 0 ? PidCheck.LIVE_MATCH : PidCheck.STALE;
        }
        try {
            Instant written = Files.getLastModifiedTime(pidFile).toInstant();
            return actualStart.get().isAfter(written.plus(START_TOLERANCE)) ? PidCheck.STALE : PidCheck.LIVE_MATCH;
        } catch (IOException e) {
            return PidCheck.UNVERIFIABLE;
        }
    }

    /**
     * PID 파일을 삭제한다. 서비스 프로세스 종료 후 호출.
     *
     * @param def 서비스 정의
     */
    public static void delete(ServiceDefinition def) {
        try {
            Files.deleteIfExists(resolve(def));
            deleteLegacy(def);
            log.debug("PID file deleted for {}", def.getName());
        } catch (Exception e) {
            log.warn("Failed to delete PID file for {}", def.getName(), e);
        }
    }

    /**
     * 서비스 정의에서 PID 파일 경로를 결정한다.
     * 파일명에 서비스 ID를 포함해 installDir을 공유하는 서비스끼리 충돌하지 않는다.
     *
     * @param def 서비스 정의
     * @return PID 파일의 절대 경로
     */
    public static Path resolve(ServiceDefinition def) {
        if (def.getInstallDir() != null && !def.getInstallDir().isBlank()) {
            return Path.of(def.getInstallDir())
                    .resolve(".llm-manager-" + def.getId() + ".pid");
        }
        return FALLBACK_DIR.resolve(def.getId() + ".pid");
    }

    /**
     * 실제로 존재하는 PID 파일을 찾는다. 신버전 경로가 없으면 구버전(공유 파일명)으로 fallback.
     *
     * @param def 서비스 정의
     * @return 존재하는 PID 파일 경로, 둘 다 없으면 null
     */
    private static Path existingFile(ServiceDefinition def) {
        Path pidFile = resolve(def);
        if (Files.exists(pidFile)) return pidFile;
        Path legacy = resolveLegacy(def);
        return legacy != null && Files.exists(legacy) ? legacy : null;
    }

    /** 첫 줄만 PID로 파싱한다. 2행 이후(start=)는 무시. */
    private static long parsePid(String text) {
        String first = text.strip().split("\\R", 2)[0].trim();
        return Long.parseLong(first);
    }

    /** {@code start=<epochMillis>} 행이 있으면 시작 시각으로 파싱한다. */
    private static Optional<Instant> parseStart(String text) {
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (!s.startsWith(START_KEY)) continue;
            try {
                return Optional.of(Instant.ofEpochMilli(Long.parseLong(s.substring(START_KEY.length()))));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 구버전 PID 파일 경로를 반환한다.
     *
     * @param def 서비스 정의
     * @return installDir이 있으면 구버전 경로, 없으면 null
     */
    private static Path resolveLegacy(ServiceDefinition def) {
        if (def.getInstallDir() == null || def.getInstallDir().isBlank()) return null;
        return Path.of(def.getInstallDir()).resolve(LEGACY_PID_FILE_NAME);
    }

    /** 구버전 PID 파일이 남아 있으면 삭제한다. */
    private static void deleteLegacy(ServiceDefinition def) {
        Path legacy = resolveLegacy(def);
        if (legacy == null) return;
        try {
            Files.deleteIfExists(legacy);
        } catch (IOException e) {
            log.debug("Failed to delete legacy PID file: {}", legacy, e);
        }
    }
}
