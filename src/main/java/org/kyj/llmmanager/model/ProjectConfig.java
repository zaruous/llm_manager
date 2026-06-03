/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.*;

/**
 * LLM 스킬을 설치할 프로젝트 설정.
 * 어떤 도구와 팩을 설치했는지, 템플릿 변수는 무엇인지 기록한다.
 * projects.json 에 영속 저장.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectConfig {

    /** 프로젝트 고유 ID (UUID) */
    private String id;

    /** 프로젝트 이름 (화면 표시용) */
    private String name;

    /** 프로젝트 루트 디렉토리 절대 경로 */
    private String path;

    /** 설치 대상으로 선택된 LLM 도구 ID 목록 */
    private List<String> enabledToolIds = new ArrayList<>();

    /** 설치 대상으로 선택된 스킬 팩 ID 목록 */
    private List<String> enabledPackIds = new ArrayList<>();

    /** 스킬 파일 템플릿에 치환할 변수 맵 (예: projectName, language, author) */
    private Map<String, String> variables = new LinkedHashMap<>();

    /** 마지막 설치 수행 시각 */
    private LocalDateTime lastInstalled;

    public ProjectConfig() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public List<String> getEnabledToolIds() { return enabledToolIds; }
    public void setEnabledToolIds(List<String> ids) { this.enabledToolIds = ids; }
    public List<String> getEnabledPackIds() { return enabledPackIds; }
    public void setEnabledPackIds(List<String> ids) { this.enabledPackIds = ids; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
    public LocalDateTime getLastInstalled() { return lastInstalled; }
    public void setLastInstalled(LocalDateTime lastInstalled) { this.lastInstalled = lastInstalled; }
}
