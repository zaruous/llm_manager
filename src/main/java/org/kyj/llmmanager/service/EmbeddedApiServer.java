/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.util.CommandBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM Manager 내장 REST API 서버 (Javalin 기반).
 * 서비스 상태·설정 조회, 시작·중지·재시작 제어 API를 제공하고
 * Swagger UI를 통해 브라우저에서 바로 사용할 수 있다.
 *
 * <pre>
 * 기본 URL: http://localhost:8185
 * Swagger : http://localhost:8185/swagger-ui
 * </pre>
 */
public class EmbeddedApiServer {
    private static final Logger log = LoggerFactory.getLogger(EmbeddedApiServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 앱 컨텍스트 — 서비스 레지스트리·프로세스 관리자 접근용 */
    private final AppContext ctx;

    /** 실행 중인 Javalin 인스턴스. null이면 미실행. */
    private Javalin app;

    /** 현재 리슨 포트. 미실행이면 -1. */
    private int currentPort = -1;

    public EmbeddedApiServer(AppContext ctx) {
        this.ctx = ctx;
    }

    // =========================================================
    // 생명주기
    // =========================================================

    /**
     * 지정 포트로 API 서버를 시작한다.
     * 이미 실행 중이면 기존 서버를 중지하고 새 포트로 재시작한다.
     *
     * @param port 리슨할 HTTP 포트
     */
    public synchronized void start(int port) {
        stop();
        this.currentPort = port;

        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            // CORS: 모든 오리진 허용 (개발·로컬 환경 대상)
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(r -> r.anyHost()));
        });

        registerRoutes();
        app.start(port);
        log.info("[API] LLM Manager API 서버 시작 → http://localhost:{}/swagger-ui", port);
    }

    /** API 서버를 중지한다. 미실행 상태이면 무시. */
    public synchronized void stop() {
        if (app != null) {
            app.stop();
            app = null;
            log.info("[API] LLM Manager API 서버 중지");
        }
    }

    /** 서버가 현재 실행 중인지 반환한다. */
    public boolean isRunning() { return app != null; }

    /** 현재 리슨 포트를 반환한다. 미실행이면 -1. */
    public int getPort() { return currentPort; }

    // =========================================================
    // 라우트 등록
    // =========================================================

    private void registerRoutes() {
        // ── Swagger UI ──
        app.get("/",            ctx -> ctx.redirect("/swagger-ui"));
        app.get("/swagger-ui",  ctx -> ctx.html(swaggerUiHtml()));
        app.get("/openapi.json", ctx -> {
            ctx.contentType("application/json");
            ctx.result(buildOpenApiSpec());
        });

        // ── 서비스 API ──
        app.get("/api/services",              this::handleListServices);
        app.get("/api/services/{id}",         this::handleGetService);
        app.post("/api/services/{id}/start",  this::handleStart);
        app.post("/api/services/{id}/stop",   this::handleStop);
        app.post("/api/services/{id}/restart",this::handleRestart);

        // ── 설정·시스템 ──
        app.get("/api/settings", this::handleSettings);
        app.get("/api/status",   this::handleStatus);
    }

    // =========================================================
    // 핸들러
    // =========================================================

    /** 전체 서비스 목록과 각 상태를 반환한다. */
    private void handleListServices(Context ctx) {
        ArrayNode arr = mapper.createArrayNode();
        for (ServiceDefinition def : this.ctx.getServiceRegistry().getAll()) {
            ServiceInstance inst = this.ctx.getProcessManager().getOrCreate(def);
            arr.add(serviceNode(def, inst));
        }

        ObjectNode resp = mapper.createObjectNode();
        resp.set("services", arr);
        resp.put("total",   arr.size());
        resp.put("running", countRunning());
        jsonResponse(ctx, resp);
    }

    /** 특정 서비스의 상세 정보를 반환한다. */
    private void handleGetService(Context ctx) {
        String id = ctx.pathParam("id");
        ServiceDefinition def = findDef(id);
        if (def == null) { ctx.status(404).json("{\"error\":\"서비스를 찾을 수 없습니다.\"}"); return; }

        ServiceInstance inst = this.ctx.getProcessManager().getOrCreate(def);
        jsonResponse(ctx, serviceNode(def, inst));
    }

    /** 서비스를 시작한다. */
    private void handleStart(Context ctx) {
        ServiceInstance inst = findInst(ctx);
        if (inst == null) return;
        if (inst.getStatus() == ServiceStatus.RUNNING) {
            ctx.status(409).json("{\"error\":\"이미 실행 중입니다.\"}");
            return;
        }
        this.ctx.getProcessManager().start(inst);
        ctx.json("{\"result\":\"start 요청 완료\"}");
    }

    /** 서비스를 중지한다. */
    private void handleStop(Context ctx) {
        ServiceInstance inst = findInst(ctx);
        if (inst == null) return;
        if (inst.getStatus() != ServiceStatus.RUNNING) {
            ctx.status(409).json("{\"error\":\"실행 중이 아닙니다.\"}");
            return;
        }
        this.ctx.getProcessManager().stop(inst);
        ctx.json("{\"result\":\"stop 요청 완료\"}");
    }

    /** 서비스를 재시작한다. */
    private void handleRestart(Context ctx) {
        ServiceInstance inst = findInst(ctx);
        if (inst == null) return;
        this.ctx.getProcessManager().restart(inst);
        ctx.json("{\"result\":\"restart 요청 완료\"}");
    }

    /** 앱 환경 설정을 반환한다. */
    private void handleSettings(Context ctx) {
        var s = this.ctx.getAppSettingsRepository().get();
        ObjectNode node = mapper.createObjectNode();
        node.put("pythonCommand",  s.getPythonCommand());
        node.put("pythonHome",     s.getPythonHome());
        node.put("nodeCommand",    s.getNodeCommand());
        node.put("nodeHome",       s.getNodeHome());
        node.put("javaCommand",    s.getJavaCommand());
        node.put("javaHome",       s.getJavaHome());
        node.put("installBase",    s.getInstallBase());
        node.put("apiServerEnabled", s.isApiServerEnabled());
        node.put("apiServerPort",  s.getApiServerPort());
        jsonResponse(ctx, node);
    }

    /** 전체 시스템 요약 상태를 반환한다. */
    private void handleStatus(Context ctx) {
        ObjectNode node = mapper.createObjectNode();
        node.put("total",   this.ctx.getServiceRegistry().getAll().size());
        node.put("running", countRunning());
        node.put("apiPort", currentPort);

        ArrayNode running = mapper.createArrayNode();
        for (ServiceDefinition def : this.ctx.getServiceRegistry().getAll()) {
            ServiceInstance inst = this.ctx.getProcessManager().getOrCreate(def);
            if (inst.getStatus() == ServiceStatus.RUNNING) {
                ObjectNode s = mapper.createObjectNode();
                s.put("id", def.getId());
                s.put("name", def.getName());
                s.put("pid", inst.getPid());
                s.put("uptime", inst.getUptimeString());
                running.add(s);
            }
        }
        node.set("runningServices", running);
        jsonResponse(ctx, node);
    }

    // =========================================================
    // 헬퍼
    // =========================================================

    /** 서비스 정의 + 인스턴스 → JSON ObjectNode */
    private ObjectNode serviceNode(ServiceDefinition def, ServiceInstance inst) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id",              def.getId());
        node.put("name",            def.getName());
        node.put("description",     nvl(def.getDescription()));
        node.put("runtimeType",     def.getRuntimeType() != null ? def.getRuntimeType().name() : null);
        node.put("status",          inst.getStatus().name());
        node.put("statusLabel",     inst.getStatus().getLabel());
        node.put("statusColor",     inst.getStatus().getColor());
        node.put("pid",             inst.getPid());
        node.put("port",            def.getPort());
        node.put("uptime",          inst.getUptimeString());
        node.put("startCommand",    CommandBuilder.buildStartCommand(def));
        node.put("installDir",      nvl(def.getInstallDir()));
        node.put("workingDir",      nvl(def.getWorkingDir()));
        node.put("repoUrl",         nvl(def.getRepoUrl()));
        node.put("healthCheckPath", nvl(def.getHealthCheckPath()));
        node.put("builtin",         def.isBuiltin());

        // 실행 인수
        ArrayNode specs = mapper.createArrayNode();
        for (ArgSpec spec : def.getArgSpecs()) {
            ObjectNode s = mapper.createObjectNode();
            s.put("name",         spec.getName());
            s.put("flag",         spec.getFlag());
            s.put("type",         spec.getType());
            s.put("currentValue", def.getArgValues().getOrDefault(spec.getName(), spec.getDefaultValue()));
            s.put("defaultValue", spec.getDefaultValue());
            s.put("description",  spec.getDescription());
            s.put("enabled",      spec.isEnabled());
            specs.add(s);
        }
        node.set("argSpecs", specs);

        // 환경 변수
        ObjectNode env = mapper.createObjectNode();
        def.getEnvVars().forEach(env::put);
        node.set("envVars", env);

        return node;
    }

    private void jsonResponse(Context ctx, ObjectNode node) {
        ctx.contentType("application/json");
        try { ctx.result(mapper.writeValueAsString(node)); }
        catch (Exception e) { ctx.status(500).result("{\"error\":\"직렬화 오류\"}"); }
    }

    private ServiceDefinition findDef(String id) {
        return ctx.getServiceRegistry().getAll().stream()
                .filter(d -> d.getId().equals(id)).findFirst().orElse(null);
    }

    private ServiceInstance findInst(Context ctx) {
        ServiceDefinition def = findDef(ctx.pathParam("id"));
        if (def == null) {
            ctx.status(404).json("{\"error\":\"서비스를 찾을 수 없습니다.\"}");
            return null;
        }
        return this.ctx.getProcessManager().getOrCreate(def);
    }

    private long countRunning() {
        return ctx.getProcessManager().getAllInstances().stream()
                .filter(i -> i.getStatus() == ServiceStatus.RUNNING).count();
    }

    private String nvl(String s) { return s == null ? "" : s; }

    // =========================================================
    // Swagger UI HTML
    // =========================================================

    private String swaggerUiHtml() {
        return """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
              <meta charset="UTF-8"/>
              <title>LLM Manager API</title>
              <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
              <style>
                body { margin: 0; background: #1a1a2e; }
                .swagger-ui .topbar { background: #1e1e2e; }
                .swagger-ui .topbar-wrapper img { content: none; }
                .swagger-ui .topbar-wrapper::before {
                  content: 'LLM Manager API';
                  color: #aaccff;
                  font-size: 18px;
                  font-weight: bold;
                  padding-left: 16px;
                }
              </style>
            </head>
            <body>
            <div id="swagger-ui"></div>
            <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
            <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-standalone-preset.js"></script>
            <script>
            window.onload = function() {
              SwaggerUIBundle({
                url: window.location.origin + '/openapi.json',
                dom_id: '#swagger-ui',
                deepLinking: true,
                presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                plugins: [SwaggerUIBundle.plugins.DownloadUrl],
                layout: 'StandaloneLayout'
              });
            };
            </script>
            </body>
            </html>
            """;
    }

    // =========================================================
    // OpenAPI 명세 생성
    // =========================================================

    private String buildOpenApiSpec() {
        try {
            ObjectNode spec = mapper.createObjectNode();
            spec.put("openapi", "3.0.3");

            ObjectNode info = spec.putObject("info");
            info.put("title",       "LLM Manager API");
            info.put("version",     "1.0.0");
            info.put("description", "LLM Manager 내장 REST API — 서비스 상태 조회·제어 및 설정 확인");

            ArrayNode servers = spec.putArray("servers");
            servers.addObject().put("url", "/").put("description", "현재 서버");

            ObjectNode paths = spec.putObject("paths");
            addStatusPath(paths);
            addServicesListPath(paths);
            addServiceByIdPath(paths);
            addServiceControlPath(paths, "start",   "서비스 시작");
            addServiceControlPath(paths, "stop",    "서비스 중지");
            addServiceControlPath(paths, "restart", "서비스 재시작");
            addSettingsPath(paths);

            addComponents(spec);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        } catch (Exception e) {
            log.error("OpenAPI 명세 생성 오류", e);
            return "{}";
        }
    }

    private void addStatusPath(ObjectNode paths) {
        ObjectNode path = paths.putObject("/api/status");
        ObjectNode get  = path.putObject("get");
        get.put("summary", "시스템 전체 상태 요약");
        get.putArray("tags").add("system");
        ObjectNode resp200 = get.putObject("responses").putObject("200");
        resp200.put("description", "성공");
        resp200.putObject("content").putObject("application/json")
               .putObject("schema").put("$ref", "#/components/schemas/StatusResponse");
    }

    private void addServicesListPath(ObjectNode paths) {
        ObjectNode path = paths.putObject("/api/services");
        ObjectNode get  = path.putObject("get");
        get.put("summary", "전체 서비스 목록 및 상태 조회");
        get.putArray("tags").add("services");
        ObjectNode resp200 = get.putObject("responses").putObject("200");
        resp200.put("description", "성공");
        resp200.putObject("content").putObject("application/json")
               .putObject("schema").put("$ref", "#/components/schemas/ServiceListResponse");
    }

    private void addServiceByIdPath(ObjectNode paths) {
        ObjectNode path = paths.putObject("/api/services/{id}");

        ArrayNode params = path.putArray("parameters");
        ObjectNode p = params.addObject();
        p.put("name", "id"); p.put("in", "path"); p.put("required", true);
        p.putObject("schema").put("type", "string");

        ObjectNode get = path.putObject("get");
        get.put("summary", "특정 서비스 상세 조회");
        get.putArray("tags").add("services");
        get.putObject("responses").putObject("200")
           .put("description", "성공");
    }

    private void addServiceControlPath(ObjectNode paths, String action, String summary) {
        ObjectNode path = paths.putObject("/api/services/{id}/" + action);

        ArrayNode params = path.putArray("parameters");
        ObjectNode p = params.addObject();
        p.put("name", "id"); p.put("in", "path"); p.put("required", true);
        p.putObject("schema").put("type", "string");

        ObjectNode post = path.putObject("post");
        post.put("summary", summary);
        post.putArray("tags").add("services");
        ObjectNode responses = post.putObject("responses");
        responses.putObject("200").put("description", "요청 완료");
        responses.putObject("404").put("description", "서비스 없음");
        responses.putObject("409").put("description", "이미 해당 상태");
    }

    private void addSettingsPath(ObjectNode paths) {
        ObjectNode path = paths.putObject("/api/settings");
        ObjectNode get  = path.putObject("get");
        get.put("summary", "앱 환경 설정 조회");
        get.putArray("tags").add("settings");
        get.putObject("responses").putObject("200").put("description", "성공");
    }

    private void addComponents(ObjectNode spec) {
        ObjectNode schemas = spec.putObject("components").putObject("schemas");

        // StatusResponse
        ObjectNode statusResp = schemas.putObject("StatusResponse");
        statusResp.put("type", "object");
        ObjectNode statusProps = statusResp.putObject("properties");
        statusProps.putObject("total")   .put("type", "integer").put("description", "전체 서비스 수");
        statusProps.putObject("running") .put("type", "integer").put("description", "실행 중 서비스 수");
        statusProps.putObject("apiPort") .put("type", "integer").put("description", "API 서버 포트");

        // ServiceListResponse
        ObjectNode listResp = schemas.putObject("ServiceListResponse");
        listResp.put("type", "object");
        ObjectNode listProps = listResp.putObject("properties");
        listProps.putObject("total")   .put("type", "integer");
        listProps.putObject("running") .put("type", "integer");
        ObjectNode svcArr = listProps.putObject("services");
        svcArr.put("type", "array");
        svcArr.putObject("items").put("$ref", "#/components/schemas/Service");

        // Service
        ObjectNode svc = schemas.putObject("Service");
        svc.put("type", "object");
        ObjectNode svcProps = svc.putObject("properties");
        svcProps.putObject("id")            .put("type", "string");
        svcProps.putObject("name")          .put("type", "string");
        svcProps.putObject("description")   .put("type", "string");
        svcProps.putObject("runtimeType")   .put("type", "string");
        svcProps.putObject("status")        .put("type", "string");
        svcProps.putObject("statusLabel")   .put("type", "string");
        svcProps.putObject("pid")           .put("type", "integer").put("format", "int64");
        svcProps.putObject("port")          .put("type", "integer");
        svcProps.putObject("uptime")        .put("type", "string");
        svcProps.putObject("startCommand")  .put("type", "string");
        svcProps.putObject("installDir")    .put("type", "string");
        svcProps.putObject("healthCheckPath").put("type", "string");
        svcProps.putObject("builtin")       .put("type", "boolean");
    }
}
