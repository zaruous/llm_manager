/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 앱 환경 설정을 ~/llm-services/settings.json 에 영속 저장·로드한다.
 */
public class AppSettingsRepository {
    private static final Logger log = LoggerFactory.getLogger(AppSettingsRepository.class);

    /** 설정 파일 경로: ~/llm-services/settings.json */
    private final Path configFile = PlatformUtil.getAppHome().resolve("settings.json");

    /** JSON 직렬화/역직렬화용 ObjectMapper */
    private final ObjectMapper mapper;

    /** 메모리에 올라온 현재 설정 */
    private AppSettings current = new AppSettings();

    public AppSettingsRepository() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 설정을 우선순위 순서대로 로드한다.
     * <ol>
     *   <li>application.yml — 기본값</li>
     *   <li>settings.json  — GUI 저장 설정 (있으면 YAML을 덮어씀)</li>
     *   <li>CLI 인수       — 런타임 오버라이드 (최우선)</li>
     * </ol>
     */
    public void load() {
        // 1. application.yml 기본값
        current = AppConfigLoader.loadDefaults();

        // 2. settings.json 사용자 설정 (존재하면 YAML 기본값보다 우선)
        if (Files.exists(configFile)) {
            try {
                current = mapper.readValue(configFile.toFile(), AppSettings.class);
                log.info("settings.json 로드: {}", configFile);
            } catch (IOException e) {
                log.error("settings.json 로드 실패 — application.yml 기본값 사용", e);
            }
        } else {
            log.info("settings.json 없음 — application.yml 기본값 사용");
        }

        // 3. CLI 인수 최우선 적용
        AppConfigLoader.applyCli(current);

        log.info("[Config] 최종 설정: apiServerPort={}, apiServerEnabled={}",
                current.getApiServerPort(), current.isApiServerEnabled());
    }

    /**
     * 현재 설정을 파일에 저장한다.
     *
     * @param settings 저장할 설정 객체
     */
    public void save(AppSettings settings) {
        try {
            Files.createDirectories(configFile.getParent());
            mapper.writeValue(configFile.toFile(), settings);
            this.current = settings;
            log.info("설정 저장 완료: {}", configFile);
        } catch (IOException e) {
            log.error("설정 저장 실패", e);
        }
    }

    /** 현재 설정을 반환한다. */
    public AppSettings get() { return current; }
}
