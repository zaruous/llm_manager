/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A visual section inside a contributed settings tab.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginSettingsSection {
    private String title = "";
    private List<PluginSettingsField> fields = new ArrayList<>();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<PluginSettingsField> getFields() { return fields; }
    public void setFields(List<PluginSettingsField> fields) {
        this.fields = fields != null ? fields : new ArrayList<>();
    }
}
