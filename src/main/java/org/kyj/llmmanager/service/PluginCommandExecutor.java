/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.model.plugin.PluginCommand;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.util.ToolLocator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 신뢰된 앱 내부 command handler를 호출하는 실행기.
 *
 * 외부 플러그인 코드는 직접 실행하지 않고, manifest command ID에 대응하는
 * 본체 handler만 호출한다.
 */
public class PluginCommandExecutor {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Duration SIDECAR_TIMEOUT = Duration.ofMinutes(10);

    private final PluginManager pluginManager;
    private final AppSettingsRepository settingsRepository;

    public PluginCommandExecutor(PluginManager pluginManager,
                                 AppSettingsRepository settingsRepository) {
        this.pluginManager = pluginManager;
        this.settingsRepository = settingsRepository;
    }

    /**
     * 플러그인 command 실행 요청을 처리한다.
     *
     * @param pluginId command를 제공한 플러그인 ID
     * @param command 실행할 command manifest
     * @param request 실행 입력값
     * @return 사용자에게 표시할 실행 결과
     */
    public PluginCommandResult execute(String pluginId, PluginCommand command,
                                       PluginCommandRequest request) {
        return executeStreaming(pluginId, command, request, ignored -> {});
    }

    /**
     * 플러그인 command를 실행하고 stdout 이벤트를 라인 단위로 전달한다.
     *
     * @param pluginId command를 제공한 플러그인 ID
     * @param command 실행할 command manifest
     * @param request 실행 입력값
     * @param onOutput 사용자에게 표시할 streaming 출력 콜백
     * @return 최종 실행 결과
     */
    public PluginCommandResult executeStreaming(String pluginId, PluginCommand command,
                                                PluginCommandRequest request,
                                                Consumer<String> onOutput) {
        return executeStreaming(pluginId, command, request, onOutput, null);
    }

    /**
     * 플러그인 command를 실행하고 stdout 이벤트를 라인 단위로 전달한다.
     * 시작된 sidecar Process를 콜백으로 노출해 호출자가 실행 취소(강제 종료)를 할 수 있다.
     *
     * @param pluginId command를 제공한 플러그인 ID
     * @param command 실행할 command manifest
     * @param request 실행 입력값
     * @param onOutput 사용자에게 표시할 streaming 출력 콜백
     * @param onProcessStart sidecar 프로세스 시작 직후 호출되는 콜백 (null 허용)
     * @return 최종 실행 결과
     */
    public PluginCommandResult executeStreaming(String pluginId, PluginCommand command,
                                                PluginCommandRequest request,
                                                Consumer<String> onOutput,
                                                Consumer<Process> onProcessStart) {
        if (command == null || command.getId() == null || command.getId().isBlank()) {
            return PluginCommandResult.error("실행할 command가 없습니다.");
        }
        if (command.getRequires().isCwd()) {
            String cwd = request.cwd();
            if (cwd == null || cwd.isBlank()) {
                return PluginCommandResult.error("작업 디렉토리를 입력해 주세요.");
            }
            Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
            if (!Files.isDirectory(cwdPath)) {
                return PluginCommandResult.error("작업 디렉토리가 존재하지 않습니다: " + cwdPath);
            }
        }
        for (String env : command.getRequires().getEnv()) {
            String value = System.getenv(env);
            if (value == null || value.isBlank()) {
                return PluginCommandResult.error("필수 환경변수가 없습니다: " + env);
            }
        }

        if ("cursor.runAgent".equals(command.getId())) {
            return runCursorSidecar(pluginId, command, request, onOutput, onProcessStart);
        }

        return PluginCommandResult.error("등록되지 않은 command handler입니다: " + command.getId());
    }

    private PluginCommandResult runCursorSidecar(String pluginId, PluginCommand command,
                                                 PluginCommandRequest request,
                                                 Consumer<String> onOutput,
                                                 Consumer<Process> onProcessStart) {
        LoadedPlugin plugin = pluginManager.findPlugin(pluginId);
        if (plugin == null) {
            return PluginCommandResult.error("플러그인 디렉토리를 찾을 수 없습니다: " + pluginId);
        }
        Path sidecar = plugin.getDirectory()
                .resolve("sidecar")
                .resolve("cursor-agent-sidecar.js")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(sidecar)) {
            return PluginCommandResult.error("Cursor sidecar 스크립트가 없습니다: " + sidecar);
        }

        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("commandId", command.getId());
            payloadMap.put("cwd", request.cwd());
            payloadMap.put("prompt", request.prompt() != null ? request.prompt() : "");
            payloadMap.put("model", request.model() != null ? request.model() : "");
            payloadMap.put("mode", request.mode() != null ? request.mode() : "sdk");
            if (request.agentMode() != null) payloadMap.put("agentMode", request.agentMode());
            if (request.settingSources() != null && !request.settingSources().isEmpty()) {
                payloadMap.put("settingSources", request.settingSources());
            }
            if (request.agents() != null && !request.agents().isEmpty()) {
                payloadMap.put("agents", request.agents());
            }
            String payload = mapper.writeValueAsString(payloadMap);
            String encoded = Base64.getEncoder()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

            AppSettings settings = settingsRepository.get();
            String nodeCommand = settings.getNodeCommand() == null || settings.getNodeCommand().isBlank()
                    ? "node" : settings.getNodeCommand().trim();
            // 직접 exec는 부모 PATH로 명령을 찾으므로, 기본값 "node"는 ToolLocator로 해석 —
            // 설치 직후 PATH 미반영 상태에서도 알려진 설치 경로의 node를 사용한다
            if ("node".equals(nodeCommand)) {
                String resolved = ToolLocator.resolveCommand("node");
                if (resolved != null) nodeCommand = resolved;
            }
            ProcessBuilder pb = new ProcessBuilder(nodeCommand, sidecar.toString(), encoded);
            pb.directory(Path.of(request.cwd()).toFile());
            pb.redirectErrorStream(false);
            applyAllowedEnvironment(pb.environment(), command.getRequires().getEnv());
            // sidecar가 하위 프로세스에서 node/npm을 다시 찾는 경우 대비
            ToolLocator.augmentPath(pb.environment());
            pb.environment().put("LLM_MANAGER_STREAM", "1");

            Process process = pb.start();
            if (onProcessStart != null) onProcessStart.accept(process);
            StringBuilder stdout = new StringBuilder();
            CompletableFuture<Void> stdoutFuture = readStdoutStreaming(process, stdout, onOutput);
            CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());
            boolean done = process.waitFor(SIDECAR_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                String stderr = stderrFuture.getNow("");
                return PluginCommandResult.error(
                        "Cursor sidecar 실행 시간이 초과되었습니다. 제한: "
                        + SIDECAR_TIMEOUT.toMinutes() + "분"
                        + (stderr.isBlank() ? "" : "\n\nstderr:\n" + stderr.trim()));
            }

            stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                return PluginCommandResult.error(stderr.isBlank()
                        ? "Cursor sidecar 실패(exit=" + process.exitValue() + ")"
                        : stderr.trim());
            }

            JsonNode response = lastJsonLine(stdout.toString());
            boolean success = response == null
                    || (!"error".equals(response.path("type").asText())
                    && response.path("success").asBoolean(true));
            String message = response != null
                    ? response.path("message").asText("")
                    : stdout.toString().trim();
            return new PluginCommandResult(success, message);
        } catch (Exception e) {
            return PluginCommandResult.error("Cursor sidecar 실행 오류: " + e.getMessage());
        }
    }

    private CompletableFuture<Void> readStdoutStreaming(Process process, StringBuilder raw,
                                                        Consumer<String> onOutput) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line).append('\n');
                    String message = displayMessage(line);
                    if (!message.isBlank()) onOutput.accept(message);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private String displayMessage(String line) {
        try {
            JsonNode node = mapper.readTree(line);
            String type = node.path("type").asText("");
            String message = node.path("message").asText("");
            if ("done".equals(type)) return finalText(message);
            if ("error".equals(type)) return message;
            // 진행 상태도 CLI처럼 노출 — 빈 메시지만 숨긴다
            if ("status".equals(type)) return message.isBlank() ? "" : "· " + message;
            return "";
        } catch (Exception e) {
            return line != null ? line : "";
        }
    }

    private String finalText(String message) {
        if (message == null || message.isBlank()) return "";
        String marker = "\n\nfinal:\n";
        int index = message.indexOf(marker);
        if (index >= 0) {
            return message.substring(index + marker.length()).trim();
        }
        return message.trim();
    }

    private JsonNode lastJsonLine(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] lines = raw.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].isBlank()) continue;
            try {
                return mapper.readTree(lines[i]);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private CompletableFuture<String> readStreamAsync(java.io.InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        });
    }

    private void applyAllowedEnvironment(Map<String, String> target, List<String> requiredEnv) {
        Map<String, String> source = System.getenv();
        target.clear();
        copyEnv(source, target, "PATH");
        copyEnv(source, target, "Path");
        copyEnv(source, target, "PATHEXT");
        copyEnv(source, target, "SystemRoot");
        copyEnv(source, target, "ComSpec");
        copyEnv(source, target, "TEMP");
        copyEnv(source, target, "TMP");
        copyEnv(source, target, "HOME");
        copyEnv(source, target, "USERPROFILE");
        for (String env : requiredEnv) {
            copyEnv(source, target, env);
        }
    }

    private void copyEnv(Map<String, String> source, Map<String, String> target, String key) {
        String value = source.get(key);
        if (value != null) target.put(key, value);
    }

    public record PluginCommandRequest(
            String cwd,
            String prompt,
            String model,
            String mode,
            Map<String, String> options,
            /** Cursor 대화 모드 (agent | plan). null이면 SDK 기본값. */
            String agentMode,
            /** SDK가 로드할 설정 레이어 (project, user 등). null이면 SDK 기본값. */
            List<String> settingSources,
            /** 커스텀 서브에이전트 정의: 이름 → {description, prompt}. */
            Map<String, Map<String, String>> agents
    ) {
        public PluginCommandRequest {
            options = options != null ? options : new LinkedHashMap<>();
        }

        /** 확장 옵션 없이 생성하는 기존 호환 생성자. */
        public PluginCommandRequest(String cwd, String prompt, String model, String mode,
                                    Map<String, String> options) {
            this(cwd, prompt, model, mode, options, null, null, null);
        }
    }

    public record PluginCommandResult(
            boolean success,
            String message
    ) {
        public static PluginCommandResult info(String message) {
            return new PluginCommandResult(true, message);
        }

        public static PluginCommandResult error(String message) {
            return new PluginCommandResult(false, message);
        }
    }
}
