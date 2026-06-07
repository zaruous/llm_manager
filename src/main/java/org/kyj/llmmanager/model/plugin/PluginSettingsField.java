/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema field rendered by the main settings dialog.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginSettingsField {
    private String key = "";
    private String label = "";
    private String kind = "string";
    private boolean required = false;
    private boolean secret = false;
    private String defaultValue = "";
    private List<String> options = new ArrayList<>();

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public boolean isSecret() { return secret; }
    public void setSecret(boolean secret) { this.secret = secret; }

    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }

    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) {
        this.options = options != null ? options : new ArrayList<>();
    }
}
