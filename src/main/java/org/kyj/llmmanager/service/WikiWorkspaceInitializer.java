/*
 * 작성자 : kyj
 * 작성일 : 2026-06-12
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 위키 워크스페이스 골격(raw/wiki/graph)을 생성하는 유틸.
 *
 * 스킬 설치 화면의 '위키 워크스페이스 골격' 팩과 동일한 내장 리소스를 사용한다 —
 * 수집·질의 다이얼로그에서 빈 디렉토리를 즉석 초기화할 때 호출된다.
 */
public final class WikiWorkspaceInitializer {

    /** 내장 리소스 경로 → 워크스페이스 상대 경로 (wiki-skeleton 팩과 동일 구성) */
    private static final Map<String, String> SKELETON = Map.of(
            "/llm-skills/wiki-agent/AGENTS.md", "AGENTS.md",
            "/llm-skills/wiki-agent/wiki-index-template.md", "wiki/index.md",
            "/llm-skills/wiki-agent/wiki-log-template.md", "wiki/log.md",
            "/llm-skills/wiki-agent/graph-gitignore", "graph/.gitignore",
            "/llm-skills/wiki-agent/gitkeep", "raw/.gitkeep");

    private WikiWorkspaceInitializer() {
    }

    /** 워크스페이스가 위키 골격을 갖췄는지 확인한다 (wiki/index.md 기준). */
    public static boolean isInitialized(Path workspace) {
        return Files.isRegularFile(workspace.resolve("wiki").resolve("index.md"));
    }

    /**
     * 사용자가 선택한 워크스페이스를 플러그인 설정(wiki.defaultCwd)에 저장한다.
     * 다음에 위키 다이얼로그를 열 때 기본값으로 복원된다.
     *
     * @param repository 앱 설정 저장소
     * @param workspace 기억할 워크스페이스 경로 (빈 값이면 무시)
     */
    public static void rememberWorkspace(AppSettingsRepository repository, String workspace) {
        if (workspace == null || workspace.isBlank()) return;
        var settings = repository.get();
        String current = settings.getPluginSetting("wiki-agent", "wiki.defaultCwd", "");
        if (workspace.equals(current)) return;
        settings.setPluginSetting("wiki-agent", "wiki.defaultCwd", workspace);
        repository.save(settings);
    }

    /**
     * 워크스페이스를 서비스 정의의 argValues["workspace"]에 저장한다.
     * 서비스 연동 다이얼로그에서 {@link #rememberWorkspace(AppSettingsRepository, String)} 대신 호출한다.
     *
     * @param serviceRegistry 서비스 레지스트리
     * @param serviceId       업데이트할 서비스 UUID
     * @param workspace       기억할 워크스페이스 경로 (빈 값이면 무시)
     */
    public static void rememberWorkspace(ServiceRegistry serviceRegistry,
                                         String serviceId, String workspace) {
        if (workspace == null || workspace.isBlank() || serviceId == null) return;
        serviceRegistry.findById(serviceId).ifPresent(def -> {
            String current = def.getArgValues().getOrDefault("workspace", "");
            if (!workspace.equals(current)) {
                def.getArgValues().put("workspace", workspace);
                serviceRegistry.update(def);
            }
        });
    }

    /**
     * 위키 골격 파일을 생성한다. 이미 있는 파일은 덮어쓰지 않는다.
     *
     * @param workspace 초기화할 워크스페이스 루트
     * @throws IOException 리소스 읽기·파일 생성 실패 시
     */
    public static void initialize(Path workspace) throws IOException {
        for (Map.Entry<String, String> entry : SKELETON.entrySet()) {
            Path target = workspace.resolve(entry.getValue());
            if (Files.exists(target)) continue;
            Files.createDirectories(target.getParent());
            try (InputStream is = WikiWorkspaceInitializer.class
                    .getResourceAsStream(entry.getKey())) {
                if (is == null) throw new IOException("내장 리소스 없음: " + entry.getKey());
                Files.write(target, is.readAllBytes());
            }
        }
    }
}
