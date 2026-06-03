/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/**
 * 사용자가 등록한 서비스 하나의 정의 정보.
 * services.json 에 직렬화되어 영속 저장된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceDefinition {

    /** 서비스 고유 식별자 (UUID). ServiceRegistry에서 키로 사용. */
    private String id;

    /** 화면에 표시되는 서비스 이름 */
    private String name;

    /** 서비스 설명 (선택) */
    private String description;

    /** 서비스 실행 런타임 (PYTHON / NODE / JAVA / SHELL) */
    private RuntimeType runtimeType = RuntimeType.PYTHON;

    /** Git 클론 URL. 설치 시 git clone 에 사용. */
    private String repoUrl;

    /** 소스 코드를 설치(클론)할 로컬 경로 */
    private String installDir;

    /** 설치 시 순서대로 실행할 명령어 목록 (pip install 등) */
    private List<String> installCommands = new ArrayList<>();

    /** 서비스 시작 명령어 (예: python server.py) */
    private String startCommand;

    /** 프로세스 실행 작업 디렉토리. null이면 installDir 사용. */
    private String workingDir;

    /** 실행 인수 명세 목록. 각 인수의 타입·기본값·플래그 정보를 담는다. */
    private List<ArgSpec> argSpecs = new ArrayList<>();

    /** 사용자가 설정한 실행 인수 값 맵 (argSpec.name → 값) */
    private Map<String, String> argValues = new LinkedHashMap<>();

    /** 서비스 프로세스에 주입할 환경변수 맵 */
    private Map<String, String> envVars = new LinkedHashMap<>();

    /** 서비스 포트. null이면 포트 없음. */
    private Integer port;

    /** 헬스체크 HTTP 경로 (기본값: /health) */
    private String healthCheckPath = "/health";

    /** 서비스 정의를 동적으로 수정하는 Groovy 스크립트 (선택) */
    private String groovyScript;

    /** true이면 lib/def/ 에서 로드된 기본 제공 서비스 정의 */
    private boolean builtin = false;

    public ServiceDefinition() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public RuntimeType getRuntimeType() { return runtimeType; }
    public void setRuntimeType(RuntimeType runtimeType) { this.runtimeType = runtimeType; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getInstallDir() { return installDir; }
    public void setInstallDir(String installDir) { this.installDir = installDir; }
    public List<String> getInstallCommands() { return installCommands; }
    public void setInstallCommands(List<String> installCommands) { this.installCommands = installCommands; }
    public String getStartCommand() { return startCommand; }
    public void setStartCommand(String startCommand) { this.startCommand = startCommand; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
    public List<ArgSpec> getArgSpecs() { return argSpecs; }
    public void setArgSpecs(List<ArgSpec> argSpecs) { this.argSpecs = argSpecs; }
    public Map<String, String> getArgValues() { return argValues; }
    public void setArgValues(Map<String, String> argValues) { this.argValues = argValues; }
    public Map<String, String> getEnvVars() { return envVars; }
    public void setEnvVars(Map<String, String> envVars) { this.envVars = envVars; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getHealthCheckPath() { return healthCheckPath; }
    public void setHealthCheckPath(String healthCheckPath) { this.healthCheckPath = healthCheckPath; }
    public String getGroovyScript() { return groovyScript; }
    public void setGroovyScript(String groovyScript) { this.groovyScript = groovyScript; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
}
