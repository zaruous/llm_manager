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
}
