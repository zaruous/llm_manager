/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Command contribution declared by a plugin.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginCommand {
    private String id = "";
    private String title = "";
    private String description = "";
    private PluginCommandRequires requires = new PluginCommandRequires();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public PluginCommandRequires getRequires() { return requires; }
    public void setRequires(PluginCommandRequires requires) {
        this.requires = requires != null ? requires : new PluginCommandRequires();
    }
}
