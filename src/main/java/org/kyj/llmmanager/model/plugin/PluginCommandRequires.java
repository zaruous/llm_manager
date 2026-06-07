/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Inputs and environment required before a command can run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginCommandRequires {
    private boolean cwd = false;
    private List<String> env = new ArrayList<>();

    public boolean isCwd() { return cwd; }
    public void setCwd(boolean cwd) { this.cwd = cwd; }

    public List<String> getEnv() { return env; }
    public void setEnv(List<String> env) {
        this.env = env != null ? env : new ArrayList<>();
    }
}
