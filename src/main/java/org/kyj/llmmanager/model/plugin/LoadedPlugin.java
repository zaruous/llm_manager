/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.model.plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loaded plugin with validation status.
 */
public class LoadedPlugin {
    private Path directory;
    private PluginManifest manifest;
    private boolean valid;
    private List<String> errors = new ArrayList<>();

    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }

    public PluginManifest getManifest() { return manifest; }
    public void setManifest(PluginManifest manifest) { this.manifest = manifest; }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) {
        this.errors = errors != null ? errors : new ArrayList<>();
    }
}
