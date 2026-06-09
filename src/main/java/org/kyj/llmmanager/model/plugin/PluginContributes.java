/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Manifest contribution block.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginContributes {
    private List<PluginCommand> commands = new ArrayList<>();
    private List<PluginSettingsTab> settingsTabs = new ArrayList<>();

    public List<PluginCommand> getCommands() { return commands; }
    public void setCommands(List<PluginCommand> commands) {
        this.commands = commands != null ? commands : new ArrayList<>();
    }

    public List<PluginSettingsTab> getSettingsTabs() { return settingsTabs; }
    public void setSettingsTabs(List<PluginSettingsTab> settingsTabs) {
        this.settingsTabs = settingsTabs != null ? settingsTabs : new ArrayList<>();
    }
}
