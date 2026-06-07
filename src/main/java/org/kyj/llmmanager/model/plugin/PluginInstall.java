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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginInstall {
    private String scope = "local";
    private List<String> npmPackages = new ArrayList<>();

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public List<String> getNpmPackages() { return npmPackages; }
    public void setNpmPackages(List<String> npmPackages) {
        this.npmPackages = npmPackages != null ? npmPackages : new ArrayList<>();
    }
}
