/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 추가한 서비스 정의 목록을 ~/llm-services/services.json 에 영속 저장·로드한다.
 */
public class ServiceRegistry {
    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

    /** 서비스 목록 저장 파일 경로: ~/llm-services/services.json */
    private final Path configFile;

    /** JSON 직렬화/역직렬화에 사용하는 ObjectMapper (들여쓰기 포맷) */
    private final ObjectMapper mapper;

    /** 메모리에 올라온 서비스 정의 목록 */
    private final List<ServiceDefinition> definitions = new ArrayList<>();

    public ServiceRegistry() {
        configFile = PlatformUtil.getAppHome().resolve("services.json");
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 파일이 있으면 JSON을 읽어 definitions에 로드한다. 파일 없으면 빈 목록으로 시작.
     */
    public void load() {
        if (!Files.exists(configFile)) {
            log.info("No config file found, starting fresh");
            return;
        }
        try {
            ServiceDefinition[] loaded = mapper.readValue(configFile.toFile(), ServiceDefinition[].class);
            definitions.clear();
            for (ServiceDefinition d : loaded) definitions.add(d);
            log.info("Loaded {} service definitions", definitions.size());
        } catch (IOException e) {
            log.error("Failed to load config", e);
        }
    }

    /**
     * 현재 definitions를 JSON 파일에 저장한다. 디렉토리가 없으면 자동 생성.
     */
    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            mapper.writeValue(configFile.toFile(), definitions);
        } catch (IOException e) {
            log.error("Failed to save config", e);
        }
    }

    public List<ServiceDefinition> getAll() {
        return new ArrayList<>(definitions);
    }

    /**
     * 서비스를 추가하거나 같은 ID가 있으면 교체한다. 즉시 파일에 저장.
     *
     * @param def 추가 또는 교체할 서비스 정의
     */
    public void add(ServiceDefinition def) {
        definitions.removeIf(d -> d.getId().equals(def.getId()));
        definitions.add(def);
        save();
    }

    /**
     * ID로 서비스를 제거하고 파일을 저장한다.
     *
     * @param id 제거할 서비스 ID
     */
    public void remove(String id) {
        definitions.removeIf(d -> d.getId().equals(id));
        save();
    }

    /**
     * add와 동일. 명시적 업데이트 의도를 표현하기 위해 별도 메서드로 제공.
     *
     * @param def 업데이트할 서비스 정의
     */
    public void update(ServiceDefinition def) {
        add(def);
    }
}
