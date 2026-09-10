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
import java.util.OptionalLong;

/**
 * 서비스 프로세스의 PID를 파일로 기록·조회·삭제하는 유틸리티.
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
 */
public class PidFileManager {
    private static final Logger log = LoggerFactory.getLogger(PidFileManager.class);

    /** 구버전(서비스 ID 미포함) PID 파일 이름 — 업그레이드 시 읽기 fallback 용도로만 유지 */
    private static final String LEGACY_PID_FILE_NAME = ".llm-manager.pid";

    /** installDir이 없는 서비스의 fallback PID 저장 디렉토리 */
    private static final Path FALLBACK_DIR =
            Path.of(System.getProperty("user.home"), ".llm-manager", "pids");

    private PidFileManager() {}

    /**
     * PID 파일에 PID를 기록한다. 서비스 프로세스 시작 직후 호출.
     *
     * @param def 서비스 정의
     * @param pid 기록할 PID
     * @throws IOException 파일 쓰기 실패 시
     */
    public static void write(ServiceDefinition def, long pid) throws IOException {
        Path pidFile = resolve(def);
        Files.createDirectories(pidFile.getParent());
        Files.writeString(pidFile, String.valueOf(pid));
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
        Path pidFile = resolve(def);
        // 구버전(공유 파일명)에서 업그레이드한 직후를 위한 읽기 fallback
        if (!Files.exists(pidFile)) {
            Path legacy = resolveLegacy(def);
            if (legacy == null || !Files.exists(legacy)) return OptionalLong.empty();
            pidFile = legacy;
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            return OptionalLong.of(pid);
        } catch (Exception e) {
            log.warn("Failed to read PID file: {}", pidFile, e);
            return OptionalLong.empty();
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
