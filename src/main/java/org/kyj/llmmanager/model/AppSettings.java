/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.kyj.llmmanager.util.PlatformUtil;

/**
 * 사용자가 설정한 앱 환경 설정 값.
 * ~/.llm-manager/settings.json 에 영속 저장된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    /** Python 실행 명령어 (예: python, python3, C:\Python311\python.exe) */
    private String pythonCommand;

    /** Python 설치 기본 경로 (예: C:\Python311, /usr/local). 비어 있으면 PATH 기본값 사용. */
    private String pythonHome = "";

    /** Node.js 실행 명령어 (예: node) */
    private String nodeCommand = "node";

    /** Node.js 설치 기본 경로 (예: C:\Program Files\nodejs, ~/.nvm/versions/node/v20). 비어 있으면 PATH 기본값 사용. */
    private String nodeHome = "";

    /** Java 실행 명령어 (예: java, javaw) */
    private String javaCommand = "java";

    /** JAVA_HOME 경로. 비어 있으면 JAVA_HOME 환경변수 또는 PATH 기본값 사용. */
    private String javaHome = "";

    /** 서비스 기본 설치 루트 경로 */
    private String installBase;

    /** 내장 REST API 서버 활성화 여부 (기본값: false) */
    private boolean apiServerEnabled = false;

    /** 내장 REST API 서버 포트 (기본값: 8185) */
    private int apiServerPort = 8185;

    /** 헬스체크 및 메모리 수집 주기 (초, 1~10, 기본값: 10) */
    private int healthCheckInterval = 10;

    /** LLM 스킬 라이브러리 DB provider (sqlite, postgresql, oracle, mssql) */
    private String skillLibraryDbProvider = "sqlite";

    /** LLM 스킬 라이브러리 JDBC URL. SQLite는 비어 있으면 앱 프로젝트 lib/cursor를 사용. */
    private String skillLibraryDbUrl = "";

    /** LLM 스킬 라이브러리 DB schema. SQLite는 사용하지 않음. */
    private String skillLibraryDbSchema = "";

    /** LLM 스킬 라이브러리 DB 사용자명 */
    private String skillLibraryDbUsername = "";

    /** LLM 스킬 라이브러리 DB 비밀번호 */
    private String skillLibraryDbPassword = "";

    /** LLM 스킬 라이브러리 JDBC driver class. 비어 있으면 provider 기본값 사용. */
    private String skillLibraryDbDriverClass = "";

    /** LLM 스킬 라이브러리 DB 커넥션 풀 최대 크기 */
    private int skillLibraryDbMaximumPoolSize = 5;

    /** LLM 스킬 라이브러리 DB 커넥션 풀 최소 idle 수 */
    private int skillLibraryDbMinimumIdle = 1;

    /** LLM 스킬 라이브러리 DB 커넥션 획득 timeout(ms) */
    private long skillLibraryDbConnectionTimeoutMs = 30000;

    /** LLM 스킬 라이브러리 DB idle timeout(ms) */
    private long skillLibraryDbIdleTimeoutMs = 600000;

    /** LLM 스킬 라이브러리 DB max lifetime(ms) */
    private long skillLibraryDbMaxLifetimeMs = 1800000;

    public AppSettings() {
        // 플랫폼 기본값으로 초기화
        this.pythonCommand = PlatformUtil.getPythonCommand();
        this.installBase   = PlatformUtil.getDefaultInstallBase().toString();
        String envJavaHome = System.getenv("JAVA_HOME");
        this.javaHome = envJavaHome != null ? envJavaHome : "";
    }

    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }

    public String getPythonHome() { return pythonHome; }
    public void setPythonHome(String pythonHome) { this.pythonHome = pythonHome; }

    public String getNodeCommand() { return nodeCommand; }
    public void setNodeCommand(String nodeCommand) { this.nodeCommand = nodeCommand; }

    public String getNodeHome() { return nodeHome; }
    public void setNodeHome(String nodeHome) { this.nodeHome = nodeHome; }

    public String getJavaCommand() { return javaCommand; }
    public void setJavaCommand(String javaCommand) { this.javaCommand = javaCommand; }

    public String getJavaHome() { return javaHome; }
    public void setJavaHome(String javaHome) { this.javaHome = javaHome; }

    public String getInstallBase() { return installBase; }
    public void setInstallBase(String installBase) { this.installBase = installBase; }

    public boolean isApiServerEnabled() { return apiServerEnabled; }
    public void setApiServerEnabled(boolean apiServerEnabled) { this.apiServerEnabled = apiServerEnabled; }

    public int getApiServerPort() { return apiServerPort; }
    public void setApiServerPort(int apiServerPort) { this.apiServerPort = apiServerPort; }

    /**
     * 헬스체크 주기를 반환한다. 1~10 범위를 벗어나면 기본값 10으로 클램핑.
     *
     * @return 1~10 사이의 헬스체크 주기 (초)
     */
    public int getHealthCheckInterval() {
        return Math.max(1, Math.min(10, healthCheckInterval));
    }
    public void setHealthCheckInterval(int healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    public String getSkillLibraryDbProvider() { return skillLibraryDbProvider; }
    public void setSkillLibraryDbProvider(String skillLibraryDbProvider) {
        this.skillLibraryDbProvider = skillLibraryDbProvider;
    }

    public String getSkillLibraryDbUrl() { return skillLibraryDbUrl; }
    public void setSkillLibraryDbUrl(String skillLibraryDbUrl) {
        this.skillLibraryDbUrl = skillLibraryDbUrl;
    }

    public String getSkillLibraryDbSchema() { return skillLibraryDbSchema; }
    public void setSkillLibraryDbSchema(String skillLibraryDbSchema) {
        this.skillLibraryDbSchema = skillLibraryDbSchema;
    }

    public String getSkillLibraryDbUsername() { return skillLibraryDbUsername; }
    public void setSkillLibraryDbUsername(String skillLibraryDbUsername) {
        this.skillLibraryDbUsername = skillLibraryDbUsername;
    }

    public String getSkillLibraryDbPassword() { return skillLibraryDbPassword; }
    public void setSkillLibraryDbPassword(String skillLibraryDbPassword) {
        this.skillLibraryDbPassword = skillLibraryDbPassword;
    }

    public String getSkillLibraryDbDriverClass() { return skillLibraryDbDriverClass; }
    public void setSkillLibraryDbDriverClass(String skillLibraryDbDriverClass) {
        this.skillLibraryDbDriverClass = skillLibraryDbDriverClass;
    }

    public int getSkillLibraryDbMaximumPoolSize() { return skillLibraryDbMaximumPoolSize; }
    public void setSkillLibraryDbMaximumPoolSize(int skillLibraryDbMaximumPoolSize) {
        this.skillLibraryDbMaximumPoolSize = skillLibraryDbMaximumPoolSize;
    }

    public int getSkillLibraryDbMinimumIdle() { return skillLibraryDbMinimumIdle; }
    public void setSkillLibraryDbMinimumIdle(int skillLibraryDbMinimumIdle) {
        this.skillLibraryDbMinimumIdle = skillLibraryDbMinimumIdle;
    }

    public long getSkillLibraryDbConnectionTimeoutMs() { return skillLibraryDbConnectionTimeoutMs; }
    public void setSkillLibraryDbConnectionTimeoutMs(long skillLibraryDbConnectionTimeoutMs) {
        this.skillLibraryDbConnectionTimeoutMs = skillLibraryDbConnectionTimeoutMs;
    }

    public long getSkillLibraryDbIdleTimeoutMs() { return skillLibraryDbIdleTimeoutMs; }
    public void setSkillLibraryDbIdleTimeoutMs(long skillLibraryDbIdleTimeoutMs) {
        this.skillLibraryDbIdleTimeoutMs = skillLibraryDbIdleTimeoutMs;
    }

    public long getSkillLibraryDbMaxLifetimeMs() { return skillLibraryDbMaxLifetimeMs; }
    public void setSkillLibraryDbMaxLifetimeMs(long skillLibraryDbMaxLifetimeMs) {
        this.skillLibraryDbMaxLifetimeMs = skillLibraryDbMaxLifetimeMs;
    }
}
