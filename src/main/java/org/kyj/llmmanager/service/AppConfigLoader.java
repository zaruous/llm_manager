/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.kyj.llmmanager.model.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LLM Manager 앱 설정 로더.
 *
 * <p>설정 우선순위 (높을수록 우선):
 * <ol>
 *   <li>CLI 인수 ({@code --key=value} 형식)</li>
 *   <li>GUI 저장 설정 ({@code ~/.llm-manager/settings.json})</li>
 *   <li>{@code application.yml} 기본값</li>
 * </ol>
 *
 * <p>CLI 오버라이드 예시:
 * <pre>
 *   start.bat --api.server.port=9090 --api.server.enabled=true
 *   gradlew run --args="--api.server.port=9090"
 * </pre>
 */
public final class AppConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(AppConfigLoader.class);

    /** main() 에서 파싱한 CLI 인수 맵. key=api.server.port, value=8185 */
    private static final Map<String, String> cliArgs = new LinkedHashMap<>();

    private AppConfigLoader() {}

    // =========================================================
    // CLI 파싱
    // =========================================================

    /**
     * main() 진입 시 CLI 인수를 파싱해 저장한다.
     * {@code --key=value} 형식만 인식한다.
     * AppContext.init() 보다 반드시 먼저 호출해야 한다.
     *
     * @param args main(String[] args) 로 전달된 인수 배열
     */
    public static void parseArgs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 2) {  // "--" 다음에 key가 있어야 함
                    String key = arg.substring(2, eq);
                    String val = arg.substring(eq + 1);
                    cliArgs.put(key, val);
                    log.info("[Config] CLI 오버라이드: {}={}", key, val);
                }
            }
        }
        if (!cliArgs.isEmpty()) {
            log.info("[Config] CLI 인수 {}개 적용됨", cliArgs.size());
        }
    }

    // =========================================================
    // YAML 로드
    // =========================================================

    /**
     * 클래스패스의 application.yml을 읽어 AppSettings 기본값을 반환한다.
     * 파일이 없거나 파싱 오류 시 AppSettings 생성자 기본값을 사용한다.
     *
     * @return application.yml 기반 AppSettings (CLI 오버라이드 미포함)
     */
    public static AppSettings loadDefaults() {
        AppSettings settings = new AppSettings();
        try (InputStream is = AppConfigLoader.class.getResourceAsStream("/application.yml")) {
            if (is == null) {
                log.debug("[Config] application.yml 없음 — 기본값 사용");
                return settings;
            }
            ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            YamlConfig cfg = yaml.readValue(is, YamlConfig.class);
            applyYaml(settings, cfg);
            log.info("[Config] application.yml 로드 완료");
        } catch (Exception e) {
            log.error("[Config] application.yml 파싱 오류 — 기본값 사용", e);
        }
        return settings;
    }

    /** YAML POJO → AppSettings 필드 적용 (null 필드는 기존 값 유지) */
    private static void applyYaml(AppSettings s, YamlConfig cfg) {
        if (cfg.api != null && cfg.api.server != null) {
            s.setApiServerEnabled(cfg.api.server.enabled);
            s.setApiServerPort(cfg.api.server.port);
            if (cfg.api.server.host != null) s.setApiServerHost(cfg.api.server.host);
            if (cfg.api.server.token != null) s.setApiServerToken(cfg.api.server.token);
            if (cfg.api.server.allowUnauthenticatedControl != null) {
                s.setApiServerAllowUnauthenticatedControl(cfg.api.server.allowUnauthenticatedControl);
            }
        }
        if (cfg.runtime != null) {
            if (cfg.runtime.python     != null) s.setPythonCommand(cfg.runtime.python);
            if (cfg.runtime.pythonHome != null) s.setPythonHome(cfg.runtime.pythonHome);
            if (cfg.runtime.node       != null) s.setNodeCommand(cfg.runtime.node);
            if (cfg.runtime.nodeHome   != null) s.setNodeHome(cfg.runtime.nodeHome);
            if (cfg.runtime.java       != null) s.setJavaCommand(cfg.runtime.java);
            if (cfg.runtime.javaHome   != null) s.setJavaHome(cfg.runtime.javaHome);
        }
        if (cfg.install != null
                && cfg.install.base != null
                && !cfg.install.base.isBlank()) {
            s.setInstallBase(cfg.install.base);
        }
        if (cfg.monitor != null && cfg.monitor.healthCheckInterval != null) {
            s.setHealthCheckInterval(cfg.monitor.healthCheckInterval);
        }
        if (cfg.skillLibrary != null && cfg.skillLibrary.db != null) {
            YamlConfig.SkillLibrarySection.DbSection db = cfg.skillLibrary.db;
            if (db.provider != null) s.setSkillLibraryDbProvider(db.provider);
            if (db.url != null) s.setSkillLibraryDbUrl(db.url);
            if (db.driverClass != null) s.setSkillLibraryDbDriverClass(db.driverClass);
            if (db.schema != null) s.setSkillLibraryDbSchema(db.schema);
            if (db.username != null) s.setSkillLibraryDbUsername(db.username);
            if (db.password != null) s.setSkillLibraryDbPassword(db.password);
            if (db.maximumPoolSize != null) s.setSkillLibraryDbMaximumPoolSize(db.maximumPoolSize);
            if (db.minimumIdle != null) s.setSkillLibraryDbMinimumIdle(db.minimumIdle);
            if (db.connectionTimeoutMs != null) s.setSkillLibraryDbConnectionTimeoutMs(db.connectionTimeoutMs);
            if (db.idleTimeoutMs != null) s.setSkillLibraryDbIdleTimeoutMs(db.idleTimeoutMs);
            if (db.maxLifetimeMs != null) s.setSkillLibraryDbMaxLifetimeMs(db.maxLifetimeMs);
        }
    }

    // =========================================================
    // CLI 오버라이드 적용
    // =========================================================

    /**
     * 저장된 CLI 인수를 AppSettings에 적용한다.
     * settings.json 로드 후 마지막에 호출해 CLI 가 최우선 순위를 갖게 한다.
     *
     * @param s 적용 대상 AppSettings (in-place 수정)
     */
    public static void applyCli(AppSettings s) {
        getCli("api.server.enabled").ifPresent(v -> s.setApiServerEnabled(Boolean.parseBoolean(v)));
        getCli("api.server.port")   .ifPresent(v -> s.setApiServerPort(parseInt(v, s.getApiServerPort())));
        getCli("api.server.host")   .ifPresent(s::setApiServerHost);
        getCli("api.server.token")  .ifPresent(s::setApiServerToken);
        getCli("api.server.allow-unauthenticated-control")
                .ifPresent(v -> s.setApiServerAllowUnauthenticatedControl(Boolean.parseBoolean(v)));
        getCli("runtime.python")    .ifPresent(s::setPythonCommand);
        getCli("runtime.python-home").ifPresent(s::setPythonHome);
        getCli("runtime.node")      .ifPresent(s::setNodeCommand);
        getCli("runtime.node-home") .ifPresent(s::setNodeHome);
        getCli("runtime.java")      .ifPresent(s::setJavaCommand);
        getCli("runtime.java-home") .ifPresent(s::setJavaHome);
        getCli("install.base")               .ifPresent(s::setInstallBase);
        getCli("monitor.health-check-interval")
                .ifPresent(v -> s.setHealthCheckInterval(parseInt(v, s.getHealthCheckInterval())));
        getCli("skill-library.db.provider").ifPresent(s::setSkillLibraryDbProvider);
        getCli("skill-library.db.url").ifPresent(s::setSkillLibraryDbUrl);
        getCli("skill-library.db.driver-class").ifPresent(s::setSkillLibraryDbDriverClass);
        getCli("skill-library.db.schema").ifPresent(s::setSkillLibraryDbSchema);
        getCli("skill-library.db.username").ifPresent(s::setSkillLibraryDbUsername);
        getCli("skill-library.db.password").ifPresent(s::setSkillLibraryDbPassword);
        getCli("skill-library.db.maximum-pool-size")
                .ifPresent(v -> s.setSkillLibraryDbMaximumPoolSize(parseInt(v, s.getSkillLibraryDbMaximumPoolSize())));
        getCli("skill-library.db.minimum-idle")
                .ifPresent(v -> s.setSkillLibraryDbMinimumIdle(parseInt(v, s.getSkillLibraryDbMinimumIdle())));
        getCli("skill-library.db.connection-timeout-ms")
                .ifPresent(v -> s.setSkillLibraryDbConnectionTimeoutMs(parseLong(v, s.getSkillLibraryDbConnectionTimeoutMs())));
        getCli("skill-library.db.idle-timeout-ms")
                .ifPresent(v -> s.setSkillLibraryDbIdleTimeoutMs(parseLong(v, s.getSkillLibraryDbIdleTimeoutMs())));
        getCli("skill-library.db.max-lifetime-ms")
                .ifPresent(v -> s.setSkillLibraryDbMaxLifetimeMs(parseLong(v, s.getSkillLibraryDbMaxLifetimeMs())));
    }

    private static Optional<String> getCli(String key) {
        return Optional.ofNullable(cliArgs.get(key));
    }

    private static int parseInt(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static long parseLong(String s, long defaultVal) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    /** 현재 적용된 CLI 인수 맵을 반환한다 (읽기 전용). */
    public static Map<String, String> getCliArgs() {
        return Map.copyOf(cliArgs);
    }

    // =========================================================
    // YAML POJO
    // =========================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class YamlConfig {
        public ApiSection     api;
        public RuntimeSection runtime;
        public InstallSection install;
        public MonitorSection monitor;
        @JsonProperty("skill-library") public SkillLibrarySection skillLibrary;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class ApiSection {
            public ServerSection server;
            @JsonIgnoreProperties(ignoreUnknown = true)
            static class ServerSection {
                public Boolean enabled;
                public Integer port;
                public String host;
                public String token;
                @JsonProperty("allow-unauthenticated-control") public Boolean allowUnauthenticatedControl;
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class RuntimeSection {
            public String python;
            @JsonProperty("python-home") public String pythonHome;
            public String node;
            @JsonProperty("node-home")   public String nodeHome;
            @JsonProperty("java")        public String java;
            @JsonProperty("java-home")   public String javaHome;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class InstallSection {
            public String base;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class MonitorSection {
            @JsonProperty("health-check-interval") public Integer healthCheckInterval;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class SkillLibrarySection {
            public DbSection db;

            @JsonIgnoreProperties(ignoreUnknown = true)
            static class DbSection {
                public String provider;
                public String url;
                @JsonProperty("driver-class") public String driverClass;
                public String schema;
                public String username;
                public String password;
                @JsonProperty("maximum-pool-size") public Integer maximumPoolSize;
                @JsonProperty("minimum-idle") public Integer minimumIdle;
                @JsonProperty("connection-timeout-ms") public Long connectionTimeoutMs;
                @JsonProperty("idle-timeout-ms") public Long idleTimeoutMs;
                @JsonProperty("max-lifetime-ms") public Long maxLifetimeMs;
            }
        }
    }
}
