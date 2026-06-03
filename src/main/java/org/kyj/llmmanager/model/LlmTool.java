/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmTool {
    private String id;
    private String name;
    private String emoji;
    private String description;
    private List<SkillPack> packs;

    public LlmTool() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<SkillPack> getPacks() { return packs; }
    public void setPacks(List<SkillPack> packs) { this.packs = packs; }

    public String getDisplayName() {
        return (emoji != null ? emoji + " " : "") + name;
    }
}
