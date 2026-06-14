/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.model.ProcessRecord;
import org.kyj.llmmanager.model.plugin.PluginCommand;
import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.model.AppSettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 신뢰된 앱 내부 command handler를 호출하는 실행기.
 *
 * 외부 플러그인 코드는 직접 실행하지 않고, manifest command ID에 대응하는
 * 본체 handler만 호출한다.
 */
public class PluginCommandExecutor {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final PluginManager pluginManager;
    private final AppSettingsRepository settingsRepository;

    /** 실행 중인 프로세스 추적 (trackKey → 항목). 사용자 중지·레코드 갱신에 사용. */
    private final Map<String, ActiveProcess> activeProcesses =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 실행 중인 프로세스와 히스토리 레코드 파일을 묶어 추적하는 항목.
     *
     * @param process node 프로세스
     * @param recordFile 히스토리 JSON 파일 경로
     * @param startTime 프로세스 시작 시각
     */
    private record ActiveProcess(Process process, Path recordFile, Instant startTime) {}

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
            return runCursorSidecar(pluginId, command, request, onOutput, command.getId());
        }
        if (command.getId().startsWith("wiki.")) {
            return runWikiTool(pluginId, command, request, onOutput);
        }

        return PluginCommandResult.error("등록되지 않은 command handler입니다: " + command.getId());
    }

    /**
     * 실행 중인 command를 자식 프로세스까지 포함해 강제 종료한다.
     *
     * @param commandId 중지할 command id (실행 시 추적 키로 등록됨)
     * @return 실행 중인 프로세스를 찾아 종료했으면 true
     */
    public boolean cancel(String commandId) {
        ActiveProcess active = activeProcesses.remove(commandId);
        if (active == null || !active.process().isAlive()) return false;
        destroyProcessTree(active.process());
        finalizeRecord(active.recordFile(), active.startTime(),
                ProcessRecord.Status.CANCELLED, -1, "사용자 중지");
        deletePidFile(commandId);
        return true;
    }

    // ─────────────────────────────────────────────
    // Wiki Agent (llm-wiki-agent Python 도구 래퍼)
    // ─────────────────────────────────────────────

    /**
     * wiki.* command를 처리한다. 위키 워크스페이스(cwd)를 검증한 뒤
     * 작업을 Cursor Agent에 위임하고 출력을 스트리밍한다.
     *
     * @param pluginId wiki-agent 플러그인 ID
     * @param command 실행할 command manifest
     * @param request cwd=워크스페이스, prompt=질문(query) 또는 수집 경로 목록(ingest)
     * @param onOutput 라인 단위 출력 콜백
     * @return 최종 실행 결과
     */
    private PluginCommandResult runWikiTool(String pluginId, PluginCommand command,
                                            PluginCommandRequest request,
                                            Consumer<String> onOutput) {
        // UI 전용 command — 프로세스 실행 대상이 아니므로 메뉴 사용을 안내
        if ("wiki.browse".equals(command.getId())) {
            return PluginCommandResult.info(
                    "위키 브라우저는 플러그인 메뉴의 '위키 브라우저' 항목에서 열 수 있습니다.");
        }

        Path workspace = Path.of(request.cwd()).toAbsolutePath().normalize();

        if ("wiki.openGraph".equals(command.getId())) {
            return openGraphHtml(workspace);
        }

        LoadedPlugin plugin = pluginManager.findPlugin(pluginId);
        if (plugin == null) {
            return PluginCommandResult.error("플러그인 디렉토리를 찾을 수 없습니다: " + pluginId);
        }

        // 워크스페이스에 위키 골격이 없으면 도구가 실패하므로 먼저 안내
        if (!Files.isRegularFile(workspace.resolve("wiki").resolve("index.md"))) {
            return PluginCommandResult.error(
                    "위키 워크스페이스가 아닙니다 (wiki/index.md 없음): " + workspace
                    + "\nLLM 스킬 설치에서 'LLM Wiki Agent > 위키 워크스페이스 골격' 팩을 먼저 설치해 주세요.");
        }

        // 그래프 빌드 등에서 에이전트가 활용할 수 있도록 플러그인 tools/를
        // 워크스페이스로 동기화한다 (업스트림 도구는 스크립트 위치 기준으로 wiki 경로를 해석)
        try {
            syncToolsIntoWorkspace(plugin.getDirectory().resolve("tools"), workspace);
        } catch (Exception e) {
            return PluginCommandResult.error("도구 동기화 실패: " + e.getMessage());
        }

        return runWikiViaCursorAgent(command, request, workspace, onOutput);
    }

    /**
     * 위키 작업을 Cursor Agent에 자연어 프롬프트로 위임한다.
     * 에이전트는 워크스페이스의 AGENTS.md 워크플로우를 따라 작업한다
     * (AGENTS.md가 없으면 내장 리소스에서 자동 설치).
     *
     * @param command 위임할 wiki command
     * @param request cwd=워크스페이스, prompt=질문 또는 수집 경로 목록
     * @param workspace 검증된 워크스페이스 경로
     * @param onOutput 스트리밍 출력 콜백
     */
    private PluginCommandResult runWikiViaCursorAgent(PluginCommand command,
                                                      PluginCommandRequest request,
                                                      Path workspace,
                                                      Consumer<String> onOutput) {
        // 에이전트가 위키 워크플로우를 알 수 있도록 AGENTS.md 보장
        try {
            ensureAgentsSchema(workspace, onOutput);
        } catch (Exception e) {
            return PluginCommandResult.error("AGENTS.md 설치 실패: " + e.getMessage());
        }

        PluginCommand cursorCommand = findCursorRunAgentCommand();
        if (cursorCommand == null) {
            return PluginCommandResult.error(
                    "Cursor Agent Runner 플러그인이 없습니다. cursor-agent-runner 플러그인을 설치해 주세요.");
        }

        String prompt = buildWikiAgentPrompt(command.getId(), request);
        if (prompt == null) {
            return PluginCommandResult.error("입력값을 확인해 주세요 (질문 또는 수집 경로가 비어 있음).");
        }

        AppSettings settings = settingsRepository.get();
        String model = settings.getPluginSetting("cursor-agent-runner", "cursor.defaultModel", "auto");
        String mode = settings.getPluginSetting("cursor-agent-runner", "cursor.mode", "sdk");

        onOutput.accept("· Cursor Agent로 위임: " + command.getId());
        // 추적 키는 원래 wiki command id — 위키 다이얼로그의 중지 요청이 이 실행을 찾도록
        return runCursorSidecar("cursor-agent-runner", cursorCommand,
                new PluginCommandRequest(workspace.toString(), prompt, model, mode,
                        new LinkedHashMap<>()),
                onOutput, command.getId());
    }

    /**
     * ingest 작업 모드별 위임 프롬프트를 조립한다. 모드는 WikiIngestPlanner가
     * 작업 분해 시 결정한다 — batch(기본)=파일 목록 일괄 수집,
     * section/merge=큰 텍스트 파일의 섹션 노트·병합 패스,
     * pages=전처리된 PDF 페이지 이미지를 일차 소스로 판독하는 노트 패스,
     * large=전처리 불가 바이너리를 에이전트가 내부 단계(변환→노트→병합)로 처리.
     *
     * @param payload 모드별 본문 (배치=파일 목록, 섹션=섹션 파일, 병합=노트 파일 경로)
     * @param options ingestMode와 모드별 파라미터 (source, notes, staging 등)
     */
    private String buildIngestPrompt(String payload, Map<String, String> options) {
        String mode = options.getOrDefault("ingestMode", "batch");
        switch (mode) {
            case "section":
                return "Large-document ingest — note-taking pass " + options.get("sectionIndex")
                        + " of " + options.get("sectionTotal")
                        + " for source '" + options.get("source") + "'. "
                        + "Read ONLY this section file: " + payload + " — then append structured "
                        + "notes (key claims, entities, concepts, notable quotes, contradictions "
                        + "with earlier sections' notes) under a heading '## Section "
                        + options.get("sectionIndex") + "/" + options.get("sectionTotal")
                        + "' to '" + options.get("notes") + "' (create the file if missing). "
                        + "Do NOT create or update any wiki pages in this pass.";
            case "pages":
                return "Large-document ingest — page-reading pass for pages "
                        + options.get("pageRange") + " of " + options.get("pageTotal")
                        + " from source '" + options.get("source") + "'. "
                        + "For EACH page listed below: open the page image file and read it "
                        + "visually — the image is the primary source of truth; the extracted "
                        + "text file is a supplement and may be incomplete or empty for "
                        + "scanned/graphic pages (marked image-only). Then append structured "
                        + "notes (key claims, entities, concepts, figures/tables described, "
                        + "notable quotes, contradictions with earlier notes) under a heading "
                        + "'## Pages " + options.get("pageRange") + "' to '" + options.get("notes")
                        + "' (create the file if missing). If you cannot view images, rely on "
                        + "the text files and mark unreadable pages as [unread image page]. "
                        + "Do NOT create or update any wiki pages in this pass.\n" + payload;
            case "merge":
                return "Large-document ingest — final merge pass for source '"
                        + options.get("source") + "'. The document was read in sections and the "
                        + "accumulated notes are in: " + payload + ". Using those notes as the "
                        + "content basis, perform the full ingest workflow from AGENTS.md "
                        + "(source page, index, overview, entity/concept pages, contradictions, "
                        + "log, post-ingest validation). Set source_file to '"
                        + options.get("source") + "'.";
            case "large":
                return "Follow the wiki workflow defined in AGENTS.md to ingest this large file: "
                        + payload + " — it is too large for a single reading pass, so work in "
                        + "stages: (1) if it is not markdown, convert it first — try "
                        + "`python tools/file_to_md.py <directory>` (markitdown wrapper, no LLM "
                        + "calls) — placing results under '" + options.get("staging") + "'; "
                        + "(2) read the markdown section by section, appending structured notes "
                        + "per section to '" + options.get("notes") + "'; "
                        + "(3) after all sections are noted, perform the full AGENTS.md ingest "
                        + "workflow from the notes with source_file set to '" + payload + "'. "
                        + "If conversion fails, ingest what you can extract and record the "
                        + "limitation on the source page.";
            default:
                // 멱등 스킵: 작업 재시도 시 이미 수집된 파일을 다시 수집하지 않도록 지시.
                // 변환 힌트: PDF 등 바이너리를 직접 못 읽을 때 동기화된 도구를 쓸 수 있게 안내.
                return "Follow the wiki workflow defined in AGENTS.md. "
                        + "Ingest the following source files into the wiki. "
                        + "If a file was already ingested (its wiki/sources/ page exists "
                        + "and is up to date), skip it and report it as skipped. "
                        + "If you cannot read a non-markdown file directly, convert it first "
                        + "by running `python tools/file_to_md.py <directory>` "
                        + "(markitdown wrapper, no LLM calls):\n" + payload;
        }
    }

    /** 로드된 cursor-agent-runner 플러그인에서 cursor.runAgent command를 찾는다. */
    private PluginCommand findCursorRunAgentCommand() {
        for (PluginManager.PluginCommandContribution contribution : pluginManager.getCommands()) {
            if ("cursor.runAgent".equals(contribution.command().getId())) {
                return contribution.command();
            }
        }
        return null;
    }

    /** 워크스페이스에 AGENTS.md가 없으면 내장 위키 스키마 리소스를 복사한다. */
    private void ensureAgentsSchema(Path workspace, Consumer<String> onOutput) throws java.io.IOException {
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (Files.isRegularFile(agentsMd)) return;
        try (java.io.InputStream is = getClass()
                .getResourceAsStream("/llm-skills/wiki-agent/AGENTS.md")) {
            if (is == null) throw new java.io.IOException("내장 AGENTS.md 리소스를 찾을 수 없습니다.");
            Files.write(agentsMd, is.readAllBytes());
        }
        onOutput.accept("· AGENTS.md(위키 워크플로우 스키마)를 워크스페이스에 설치했습니다.");
    }

    /**
     * wiki command를 Cursor Agent용 자연어 프롬프트로 변환한다.
     * AGENTS.md는 영어 스키마이므로 프롬프트도 영어로 작성한다.
     *
     * @return 프롬프트 문자열, 입력 검증 실패 시 null
     */
    private String buildWikiAgentPrompt(String commandId, PluginCommandRequest request) {
        String prompt = request.prompt() != null ? request.prompt().trim() : "";
        switch (commandId) {
            case "wiki.ingest":
                if (prompt.isBlank()) return null;
                return buildIngestPrompt(prompt, request.options());
            case "wiki.query": {
                if (prompt.isBlank()) return null;
                String save = "true".equalsIgnoreCase(request.options().get("save"))
                        ? " Save the answer as a new page under wiki/syntheses/." : "";
                return "Follow the wiki query workflow in AGENTS.md and answer this question "
                        + "using only the wiki contents:\n" + prompt + save;
            }
            case "wiki.health":
                return "Run the health check workflow from AGENTS.md (deterministic structural "
                        + "checks only) and save the report to wiki/health-report.md.";
            case "wiki.lint":
                return "Run the lint workflow from AGENTS.md (orphan pages, broken links, "
                        + "contradictions, stale content) and save the report to wiki/lint-report.md.";
            case "wiki.graph":
                return "Build the knowledge graph following the wiki-graph workflow in AGENTS.md. "
                        + "If Python is available you may run `python tools/build_graph.py`; "
                        + "otherwise construct graph/graph.json and graph/graph.html from the "
                        + "[[wikilinks]] across wiki pages.";
            default:
                return null;
        }
    }

    /**
     * 플러그인의 tools/*.py를 워크스페이스 tools/로 복사한다. 매 실행마다 덮어써서
     * 플러그인 갱신이 즉시 반영되도록 한다 (워크스페이스 사본의 수동 수정은 유실됨).
     *
     * @return 워크스페이스의 tools 디렉토리 경로
     */
    private Path syncToolsIntoWorkspace(Path pluginTools, Path workspace) throws java.io.IOException {
        Path target = workspace.resolve("tools");
        Files.createDirectories(target);
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(pluginTools, "*.py")) {
            for (Path script : stream) {
                Files.copy(script, target.resolve(script.getFileName().toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    /** graph/graph.html을 OS 기본 브라우저로 연다. WebView 대신 브라우저를 쓰는 이유: 통합계획 Phase 3 참고. */
    private PluginCommandResult openGraphHtml(Path workspace) {
        Path graphHtml = workspace.resolve("graph").resolve("graph.html");
        if (!Files.isRegularFile(graphHtml)) {
            return PluginCommandResult.error(
                    "그래프 파일이 없습니다: " + graphHtml + "\n먼저 '그래프 빌드 (Graph)'를 실행해 주세요.");
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(graphHtml.toUri());
                return PluginCommandResult.info("브라우저에서 그래프를 열었습니다: " + graphHtml);
            }
            return PluginCommandResult.error("이 환경에서는 브라우저 열기를 지원하지 않습니다.");
        } catch (Exception e) {
            return PluginCommandResult.error("그래프 열기 실패: " + e.getMessage());
        }
    }

    private PluginCommandResult runCursorSidecar(String pluginId, PluginCommand command,
                                                 PluginCommandRequest request,
                                                 Consumer<String> onOutput,
                                                 String trackKey) {
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
            payloadMap.put("prompt",
                    languageDirective() + (request.prompt() != null ? request.prompt() : ""));
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
            ProcessBuilder pb = new ProcessBuilder(nodeCommand, sidecar.toString(), encoded);
            pb.directory(Path.of(request.cwd()).toFile());
            pb.redirectErrorStream(false);
            applyAllowedEnvironment(pb.environment(), command.getRequires().getEnv());
            pb.environment().put("LLM_MANAGER_STREAM", "1");

            Duration timeout = executionTimeout(pluginId);
            Instant startTime = Instant.now();
            Process process = pb.start();
            Path recordFile = createRecord(trackKey, process, command.getId(),
                    request.cwd(), request.prompt());
            activeProcesses.put(trackKey, new ActiveProcess(process, recordFile, startTime));
            writePidFile(trackKey, process, command.getId(), request.cwd());

            // 종료 정보 — finally 블록에서 레코드를 갱신하기 위해 스코프 밖에 선언
            ProcessRecord.Status finalStatus = ProcessRecord.Status.FAILED;
            int finalExitCode = -1;
            String finalResult = null;

            try {
                StringBuilder stdout = new StringBuilder();
                CompletableFuture<Void> stdoutFuture = readStdoutStreaming(process, stdout, onOutput);
                CompletableFuture<String> stderrFuture = readStreamAsync(process.getErrorStream());
                boolean done = awaitProcess(process, timeout);
                if (!done) {
                    destroyProcessTree(process);
                    finalStatus = ProcessRecord.Status.TIMEOUT;
                    finalResult = "실행 시간 초과: " + timeout.toMinutes() + "분";
                    String stderr = stderrFuture.getNow("");
                    return PluginCommandResult.error(
                            "Cursor sidecar 실행 시간이 초과되었습니다. 제한: "
                            + timeout.toMinutes() + "분 (설정 탭의 '실행 타임아웃'으로 조정 가능)"
                            + (stderr.isBlank() ? "" : "\n\nstderr:\n" + stderr.trim()));
                }

                stdoutFuture.get(5, TimeUnit.SECONDS);
                String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    finalExitCode = process.exitValue();
                    finalResult = stderr.isBlank()
                            ? "exit=" + process.exitValue() : stderr.trim();
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
                finalStatus = success ? ProcessRecord.Status.COMPLETED : ProcessRecord.Status.FAILED;
                finalExitCode = process.exitValue();
                finalResult = message;
                return new PluginCommandResult(success, message);
            } finally {
                // cancel()이 먼저 remove했으면 null — 이미 CANCELLED로 기록됐으므로 skip
                ActiveProcess removed = activeProcesses.remove(trackKey);
                if (removed != null) {
                    finalizeRecord(recordFile, startTime, finalStatus, finalExitCode, finalResult);
                }
                deletePidFile(trackKey);
            }
        } catch (Exception e) {
            return PluginCommandResult.error("Cursor sidecar 실행 오류: " + e.getMessage());
        }
    }

    /**
     * 플러그인 설정의 timeoutMinutes로 실행 타임아웃을 결정한다.
     *
     * @return 타임아웃, 무제한(미설정·0 이하·파싱 실패)이면 null
     */
    private Duration executionTimeout(String pluginId) {
        String raw = settingsRepository.get()
                .getPluginSetting(pluginId, "timeoutMinutes", "").trim();
        if (raw.isBlank()) return null;
        try {
            long minutes = Long.parseLong(raw);
            return minutes <= 0 ? null : Duration.ofMinutes(minutes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 프로세스 종료를 기다린다. timeout이 null이면 무제한 대기.
     *
     * @return 시간 내 종료됐으면 true (무제한이면 항상 true)
     */
    private boolean awaitProcess(Process process, Duration timeout) throws InterruptedException {
        if (timeout == null) {
            process.waitFor();
            return true;
        }
        return process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 프로세스를 자식까지 포함해 강제 종료한다.
     * destroyForcibly()만으로는 사이드카가 띄운 에이전트 자식 프로세스가 살아남아
     * 백그라운드에서 계속 실행(과금)될 수 있다.
     */
    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * 실행 시작 시 RUNNING 상태의 히스토리 레코드 JSON을 생성하고 파일 경로를 반환한다.
     * 파일명: records/<safeName>-<yyyyMMddHHmmssSSS>.json
     * 실패해도 null을 반환하며 수집 흐름을 막지 않는다.
     *
     * @param trackKey 추적 키
     * @param process 실행된 node 프로세스
     * @param commandId 실행된 command id
     * @param cwd 워크스페이스 경로
     * @param prompt 전달된 프롬프트 원문 (요약해 저장)
     * @return 생성된 레코드 파일 경로, 실패 시 null
     */
    private Path createRecord(String trackKey, Process process,
                               String commandId, String cwd, String prompt) {
        try {
            Path dir = recordsDir();
            Files.createDirectories(dir);
            String ts = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
            String safeName = trackKey.replaceAll("[^a-zA-Z0-9._-]", "-");
            Path recordFile = dir.resolve(safeName + "-" + ts + ".json");
            ProcessRecord record = ProcessRecord.running(
                    process.pid(), trackKey, commandId, cwd,
                    summarizePrompt(prompt),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            mapper.writerWithDefaultPrettyPrinter().writeValue(recordFile.toFile(), record);
            return recordFile;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 프로세스 종료 시 레코드 JSON을 최종 상태로 갱신한다.
     * 파일이 없거나 쓰기에 실패해도 무시한다.
     *
     * @param recordFile createRecord()가 반환한 파일 경로
     * @param startTime 프로세스 시작 시각
     * @param status 최종 상태
     * @param exitCode 프로세스 종료 코드
     * @param result 최종 결과 메시지 (null 가능)
     */
    private void finalizeRecord(Path recordFile, Instant startTime,
                                 ProcessRecord.Status status, int exitCode, String result) {
        if (recordFile == null || !Files.isRegularFile(recordFile)) return;
        try {
            ProcessRecord existing = mapper.readValue(recordFile.toFile(), ProcessRecord.class);
            long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            String endTime = LocalDateTime.now()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            ProcessRecord updated = existing.finalize(status, exitCode, result, endTime, durationMs);
            mapper.writerWithDefaultPrettyPrinter().writeValue(recordFile.toFile(), updated);
        } catch (Exception ignored) {
        }
    }

    /**
     * 최근 실행 레코드를 최신순으로 읽는다. UI의 히스토리 목록에 사용한다.
     *
     * @param limit 최대 반환 건수
     * @return 최신순 레코드 목록
     */
    public List<ProcessRecord> readRecentRecords(int limit) {
        Path dir = recordsDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .limit(limit)
                    .map(f -> {
                        try {
                            return mapper.readValue(f.toFile(), ProcessRecord.class);
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 히스토리 레코드 저장 디렉토리. */
    private Path recordsDir() {
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("llm-manager")
                .resolve("records");
    }

    /**
     * 실행 중인 node 프로세스의 PID를 임시 파일로 기록한다.
     * 외부 도구(Task Manager 스크립트 등)가 실행 중인 프로세스를 식별하는 데 사용.
     * 경로: %TEMP%/llm-manager/<trackKey>.pid
     */
    private void writePidFile(String trackKey, Process process,
                               String commandId, String cwd) {
        try {
            Path pidFile = pidFilePath(trackKey);
            Files.createDirectories(pidFile.getParent());
            String content = "pid=" + process.pid() + "\n"
                    + "command=" + commandId + "\n"
                    + "cwd=" + cwd + "\n";
            Files.writeString(pidFile, content, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** PID 파일을 삭제한다. 프로세스 정상 종료·취소·타임아웃 시 공통 호출. */
    private void deletePidFile(String trackKey) {
        try {
            Files.deleteIfExists(pidFilePath(trackKey));
        } catch (Exception ignored) {
        }
    }

    /** trackKey를 파일명으로 변환한다 — '/'·':' 등 경로 금지 문자를 '-'로 치환. */
    private Path pidFilePath(String trackKey) {
        String safeName = trackKey.replaceAll("[^a-zA-Z0-9._-]", "-");
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("llm-manager")
                .resolve(safeName + ".pid");
    }

    /** 프롬프트 앞 200자만 요약한다 — 전문은 레코드 파일을 비대하게 만든다. */
    private String summarizePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return "";
        String trimmed = prompt.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }

    /**
     * 응답 언어 설정에 따른 지시문을 반환한다. 프롬프트 맨 앞에 붙여
     * 에이전트의 사고 과정(thinking)과 응답이 해당 언어로 작성되게 한다.
     * English는 에이전트 기본 동작이므로 지시문 없음.
     */
    private String languageDirective() {
        String language = settingsRepository.get()
                .getPluginSetting("cursor-agent-runner", "cursor.language", "한국어");
        if (language.isBlank() || "english".equalsIgnoreCase(language.trim())) return "";
        return "[지시] 모든 사고 과정(thinking)과 응답을 반드시 " + language.trim()
                + "로 작성하세요.\n\n";
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
            // 에이전트의 응답·사고 텍스트는 그대로 노출
            if ("text".equals(type)) return message;
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
        // npm은 글로벌 prefix를 %APPDATA%\npm에서 결정 — 없으면 사이드카의
        // `npm root -g`가 잘못된 경로를 반환해 글로벌 @cursor/sdk를 못 찾는다
        copyEnv(source, target, "APPDATA");
        copyEnv(source, target, "LOCALAPPDATA");
        copyEnv(source, target, "ProgramFiles");
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
