/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.model.plugin.PluginSettingsField;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wiki-agent 플러그인 manifest가 PluginManager 경로로 정상 로드되는지 검증한다.
 * 설정 탭의 실행 에이전트(select) 필드가 드롭다운으로 렌더링될 조건을 함께 확인.
 */
class PluginManagerWikiAgentTest {

    @Test
    void wikiAgentManifestLoadsWithSettingsDropdown() {
        PluginManager manager = new PluginManager();
        manager.load();

        LoadedPlugin wiki = manager.findPlugin("wiki-agent");
        assertNotNull(wiki, "wiki-agent 플러그인이 로드되어야 함 (plugins/ 디렉토리 해석 실패?)");
        assertTrue(wiki.isValid(), "manifest 검증 실패: " + wiki.getErrors());

        // 설정 탭 contribution 존재
        var tabs = manager.getSettingsTabs().stream()
                .filter(c -> "wiki-agent".equals(c.pluginId()))
                .toList();
        assertEquals(1, tabs.size(), "Wiki Agent 설정 탭이 1개 있어야 함");

        // 실행 에이전트 select 필드 — SettingsDialog가 ComboBox로 렌더링하는 조건
        Optional<PluginSettingsField> agentField = tabs.get(0).tab().getSections().stream()
                .flatMap(s -> s.getFields().stream())
                .filter(f -> "wiki.agent".equals(f.getKey()))
                .findFirst();
        assertTrue(agentField.isPresent(), "wiki.agent 필드가 있어야 함");
        assertEquals("select", agentField.get().getKind());
        assertEquals(List.of("claude", "cursor"), agentField.get().getOptions());
        assertEquals("claude", agentField.get().getDefaultValue());

        // 커맨드 7종 등록 확인
        long wikiCommands = manager.getCommands().stream()
                .filter(c -> "wiki-agent".equals(c.pluginId()))
                .count();
        assertEquals(7, wikiCommands, "wiki 커맨드 7종이 등록되어야 함");
    }
}
