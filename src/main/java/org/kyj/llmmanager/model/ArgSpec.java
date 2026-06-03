/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 실행 인수 하나의 명세.
 * 인수 타입(STRING/INTEGER/BOOLEAN/SELECT)과 기본값, CLI 플래그를 정의한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArgSpec {

    /** 인수 식별자 이름 (예: port, device) */
    private String name;

    /** CLI에 전달되는 플래그 문자열 (예: --port, --device) */
    private String flag;

    /** 인수 타입: STRING | INTEGER | BOOLEAN | SELECT */
    private String type;       // STRING, INTEGER, BOOLEAN, SELECT

    /** 사용자가 값을 입력하지 않았을 때 사용할 기본값 */
    @JsonAlias("default")
    private String defaultValue;

    /** UI에 표시되는 인수 설명 */
    private String description;

    /** SELECT 타입일 때 선택 가능한 값 목록 */
    private List<String> options; // for SELECT type

    /** false이면 CommandBuilder가 이 인수를 명령어에서 제외한다 */
    private boolean enabled = true;

    public ArgSpec() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFlag() { return flag; }
    public void setFlag(String flag) { this.flag = flag; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
