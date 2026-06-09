/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin dependency installation policy.
 * npm 및 pip 패키지를 모두 선언할 수 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginInstall {
    private String scope = "local";
    private List<String> npmPackages = new ArrayList<>();

    /** pip install 대상 패키지 목록. requirements.txt 대신 직접 지정할 때 사용. */
    private List<String> pipPackages = new ArrayList<>();

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public List<String> getNpmPackages() { return npmPackages; }
    public void setNpmPackages(List<String> npmPackages) {
        this.npmPackages = npmPackages != null ? npmPackages : new ArrayList<>();
    }

    public List<String> getPipPackages() { return pipPackages; }
    public void setPipPackages(List<String> pipPackages) {
        this.pipPackages = pipPackages != null ? pipPackages : new ArrayList<>();
    }
}
