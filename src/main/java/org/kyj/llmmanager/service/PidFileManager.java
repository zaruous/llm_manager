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
 *   <li>installDir이 있으면 {@code <installDir>/.llm-manager.pid}</li>
 *   <li>installDir이 없으면 {@code ~/.llm-manager/pids/<serviceId>.pid}</li>
 * </ol>
 */
public class PidFileManager {
    private static final Logger log = LoggerFactory.getLogger(PidFileManager.class);

    /** installDir 내 PID 파일 이름 */
    private static final String PID_FILE_NAME = ".llm-manager.pid";

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
        if (!Files.exists(pidFile)) return OptionalLong.empty();
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
            log.debug("PID file deleted for {}", def.getName());
        } catch (Exception e) {
            log.warn("Failed to delete PID file for {}", def.getName(), e);
        }
    }

    /**
     * 서비스 정의에서 PID 파일 경로를 결정한다.
     *
     * @param def 서비스 정의
     * @return PID 파일의 절대 경로
     */
    public static Path resolve(ServiceDefinition def) {
        if (def.getInstallDir() != null && !def.getInstallDir().isBlank()) {
            return Path.of(def.getInstallDir()).resolve(PID_FILE_NAME);
        }
        return FALLBACK_DIR.resolve(def.getId() + ".pid");
    }
}
