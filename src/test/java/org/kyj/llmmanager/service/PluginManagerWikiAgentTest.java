/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.model.plugin.PluginSettingsField;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wiki-agent 플러그인 manifest가 PluginManager 경로로 정상 로드되는지 검증한다.
 * cursor 전용 축소(v0.2.0) 이후의 manifest 구성을 함께 확인한다.
 * Cursor 위임 실행 커맨드는 CURSOR_API_KEY를 요구하고, 앱 내부 커맨드는 env 요구가 없어야 한다.
 */
class PluginManagerWikiAgentTest {

    @Test
    void wikiAgentManifestLoadsCursorOnly() {
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

        var fields = tabs.get(0).tab().getSections().stream()
                .flatMap(s -> s.getFields().stream())
                .toList();

        // cursor 전용 축소로 실행 에이전트 드롭다운(wiki.agent)은 제거됨
        assertTrue(fields.stream().noneMatch(f -> "wiki.agent".equals(f.getKey())),
                "wiki.agent 필드는 제거되어야 함");
        assertTrue(fields.stream().noneMatch(f -> f.getKey().startsWith("LLM_MODEL")),
                "claude 전용 모델 필드는 제거되어야 함");

        // CURSOR_API_KEY env 필드 — SettingsDialog가 env 안내로 렌더링하는 조건
        Optional<PluginSettingsField> keyField = fields.stream()
                .filter(f -> "CURSOR_API_KEY".equals(f.getKey()))
                .findFirst();
        assertTrue(keyField.isPresent(), "CURSOR_API_KEY 필드가 있어야 함");
        assertEquals("env", keyField.get().getKind());

        // 커맨드 8종 등록 확인 (wiki.reindex는 앱 내부 Java 경로로 처리)
        var wikiCommands = manager.getCommands().stream()
                .filter(c -> "wiki-agent".equals(c.pluginId()))
                .toList();
        assertEquals(8, wikiCommands.size(), "wiki 커맨드 8종이 등록되어야 함");

        // Cursor 위임 실행형 커맨드만 CURSOR_API_KEY를 요구해야 함
        for (var contribution : wikiCommands) {
            var command = contribution.command();
            String id = command.getId();
            if ("wiki.browse".equals(id)
                    || "wiki.openGraph".equals(id)
                    || "wiki.reindex".equals(id)) {
                assertTrue(command.getRequires().getEnv().isEmpty(),
                        id + "는 env 요구가 없어야 함");
            } else {
                assertTrue(command.getRequires().getEnv().contains("CURSOR_API_KEY"),
                        id + "는 CURSOR_API_KEY를 요구해야 함");
            }
        }
    }
}
