/*
 * 작성자 : kyj
 * 작성일 : 2026-07-11
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kyj.llmmanager.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 워크스페이스 경로 → 벡터 저장소 ID 매핑을 관리하는 레지스트리.
 *
 * 중앙 벡터 저장소({@code ~/llm-services/wiki-mcp-server/vector/})의 workspaces.json에
 * 매핑을 영속화한다. 폴더명이 같은 워크스페이스끼리 색인 디렉토리가 겹치지 않도록
 * ID는 {@code <폴더명>-<랜덤 8자리 hex>} 형식으로 발급한다.
 * server.py의 폴백 로직과 파일 포맷을 공유하므로 스키마 변경 시 함께 수정해야 한다.
 */
public final class WikiVectorWorkspaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(WikiVectorWorkspaceRegistry.class);

    /** 워크스페이스 매핑 메타파일명. 중앙 벡터 저장소 루트에 위치한다. */
    static final String REGISTRY_FILE_NAME = "workspaces.json";

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private WikiVectorWorkspaceRegistry() {
    }

    /**
     * 중앙 벡터 저장소 루트 디렉토리를 반환한다.
     * 시스템 프로퍼티 {@code llm.wikiVectorDir}로 재지정할 수 있다 (테스트 격리용).
     *
     * @return 벡터 저장소 루트 절대 경로
     */
    public static Path baseDir() {
        String override = System.getProperty(WikiVectorRepository.VECTOR_DIR_PROP);
        Path base = (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"),
                        "llm-services", "wiki-mcp-server", "vector");
        return base.toAbsolutePath().normalize();
    }

    /**
     * 워크스페이스의 벡터 저장소 ID를 조회하고, 없으면 새로 발급해 영속화한다.
     * 동일 워크스페이스는 항상 같은 ID를 돌려받는다.
     *
     * @param workspace 위키 워크스페이스 루트 경로
     * @return 벡터 저장소 디렉토리명으로 쓰이는 ID
     */
    public static synchronized String resolveId(Path workspace) {
        Path ws = workspace.toAbsolutePath().normalize();
        String wsKey = ws.toString();
        Path registryFile = baseDir().resolve(REGISTRY_FILE_NAME);

        ObjectNode root = readRegistry(registryFile);
        ObjectNode workspaces = (ObjectNode) root.get("workspaces");

        String existing = findId(workspaces, wsKey);
        if (existing != null) return existing;

        String id = newUniqueId(ws, workspaces);
        ObjectNode entry = workspaces.putObject(wsKey);
        entry.put("id", id);
        entry.put("created", LocalDate.now().toString());
        writeRegistry(registryFile, root);
        return id;
    }

    /** 레지스트리에서 워크스페이스 키에 매핑된 ID를 찾는다. 없으면 null. */
    private static String findId(ObjectNode workspaces, String wsKey) {
        Iterator<Map.Entry<String, JsonNode>> it = workspaces.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            // Windows 파일 시스템은 대소문자를 구분하지 않으므로 키 비교도 무시한다
            boolean match = PlatformUtil.isWindows()
                    ? e.getKey().equalsIgnoreCase(wsKey)
                    : e.getKey().equals(wsKey);
            if (match && e.getValue().hasNonNull("id")) {
                return e.getValue().get("id").asText();
            }
        }
        return null;
    }

    /** {@code <폴더명>-<랜덤 8자리 hex>} 형식의 ID를 기존 ID와 겹치지 않게 발급한다. */
    private static String newUniqueId(Path workspace, ObjectNode workspaces) {
        String name = workspace.getFileName() != null
                ? workspace.getFileName().toString() : "workspace";
        while (true) {
            String candidate = name + "-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 8);
            boolean taken = false;
            Iterator<JsonNode> values = workspaces.elements();
            while (values.hasNext()) {
                if (candidate.equals(values.next().path("id").asText(null))) {
                    taken = true;
                    break;
                }
            }
            if (!taken) return candidate;
        }
    }

    /** 레지스트리 파일을 읽는다. 없거나 손상되면 빈 레지스트리를 반환한다. */
    private static ObjectNode readRegistry(Path registryFile) {
        if (Files.isRegularFile(registryFile)) {
            try {
                JsonNode node = MAPPER.readTree(registryFile.toFile());
                if (node instanceof ObjectNode obj && obj.get("workspaces") instanceof ObjectNode) {
                    return obj;
                }
            } catch (IOException e) {
                log.warn("워크스페이스 레지스트리 읽기 실패, 새로 생성: {}", registryFile, e);
            }
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.putObject("workspaces");
        return root;
    }

    /** 레지스트리를 임시 파일 + 원자적 이동으로 기록해 부분 기록 파일이 남지 않게 한다. */
    private static void writeRegistry(Path registryFile, ObjectNode root) {
        try {
            Files.createDirectories(registryFile.getParent());
            Path tmp = registryFile.resolveSibling(REGISTRY_FILE_NAME + ".tmp");
            MAPPER.writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("워크스페이스 레지스트리 기록 실패: " + registryFile, e);
        }
    }
}
