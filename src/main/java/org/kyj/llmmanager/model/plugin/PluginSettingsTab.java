/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings dialog tab contributed by a plugin.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginSettingsTab {
    private String id = "";
    private String title = "";
    private int order = 1000;
    private String type = "schema";
    private List<PluginSettingsSection> sections = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<PluginSettingsSection> getSections() { return sections; }
    public void setSections(List<PluginSettingsSection> sections) {
        this.sections = sections != null ? sections : new ArrayList<>();
    }
}
