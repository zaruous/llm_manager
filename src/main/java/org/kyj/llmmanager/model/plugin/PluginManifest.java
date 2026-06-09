/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Plugin manifest loaded from a plugin directory.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginManifest {
    private String id = "";
    private String name = "";
    private String version = "";
    private String type = "local";
    private List<String> permissions = new ArrayList<>();
    private PluginInstall install = new PluginInstall();
    private PluginContributes contributes = new PluginContributes();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) {
        this.permissions = permissions != null ? permissions : new ArrayList<>();
    }

    public PluginContributes getContributes() { return contributes; }
    public void setContributes(PluginContributes contributes) {
        this.contributes = contributes != null ? contributes : new PluginContributes();
    }

    public PluginInstall getInstall() { return install; }
    public void setInstall(PluginInstall install) {
        this.install = install != null ? install : new PluginInstall();
    }
}
