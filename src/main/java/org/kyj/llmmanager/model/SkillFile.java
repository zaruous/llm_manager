/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillFile {
    private String resourcePath;  // classpath 기준 경로
    private String targetPath;    // 프로젝트 루트 기준 상대 경로
    private boolean template;     // {{변수}} 치환 여부

    public SkillFile() {}

    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }
    public String getTargetPath() { return targetPath; }
    public void setTargetPath(String targetPath) { this.targetPath = targetPath; }
    public boolean isTemplate() { return template; }
    public void setTemplate(boolean template) { this.template = template; }
}
