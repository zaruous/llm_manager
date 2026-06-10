#!/usr/bin/env node

const { Buffer } = require("node:buffer");
const { execSync } = require("node:child_process");
const { createRequire } = require("node:module");
const { pathToFileURL } = require("node:url");
const path = require("node:path");

function writeResult(success, message, extra = {}) {
  if (isStreamMode()) {
    writeLine(success ? "done" : "error", message, extra);
    return;
  }
  process.stdout.write(JSON.stringify({ success, message, ...extra }, null, 2));
}

function writeLine(type, message, extra = {}) {
  process.stdout.write(JSON.stringify({ type, message, ...extra }) + "\n");
}

function isStreamMode() {
  return process.env.LLM_MANAGER_STREAM === "1";
}

function readRequest() {
  const encoded = process.argv[2];
  if (!encoded) throw new Error("요청 payload가 없습니다.");
  const json = Buffer.from(encoded, "base64").toString("utf8");
  return JSON.parse(json);
}

async function main() {
  const request = readRequest();
  if (!request.cwd) throw new Error("cwd가 없습니다.");
  if (!request.prompt || !request.prompt.trim()) {
    throw new Error("프롬프트를 입력해 주세요.");
  }
  if (!process.env.CURSOR_API_KEY) {
    throw new Error("CURSOR_API_KEY 환경변수가 없습니다.");
  }
  if ((request.mode || "sdk") !== "sdk") {
    throw new Error("현재 sidecar는 sdk 모드만 지원합니다. cli 모드는 다음 단계에서 연결합니다.");
  }
  assertNodeVersion();

  const sdkPath = resolveCursorSdk();
  if (!sdkPath) {
    writeResult(false,
      "Node sidecar 실행은 확인되었습니다.\n"
      + "글로벌 @cursor/sdk가 아직 설치되어 있지 않습니다.\n"
      + "플러그인 관리 화면에서 의존성 설치를 실행하세요.",
      {
        cwd: request.cwd,
        model: request.model,
        mode: request.mode
      });
    return;
  }

  writeLine("status", "Cursor Agent 준비 중");
  const sdk = await loadCursorSdk(sdkPath);
  const Agent = sdk.Agent || (sdk.default && sdk.default.Agent);
  if (!Agent || typeof Agent.create !== "function") {
    const keys = Object.keys(sdk).join(", ");
    const defaultKeys = sdk.default ? Object.keys(sdk.default).join(", ") : "";
    throw new Error("@cursor/sdk에서 Agent.create를 찾을 수 없습니다."
      + "\nexports: " + keys
      + (defaultKeys ? "\ndefault exports: " + defaultKeys : ""));
  }

  const events = [];
  let finalStatus = "";
  let finalText = "";
  let finalOk = true;
  const agentOptions = {
    apiKey: process.env.CURSOR_API_KEY,
    model: { id: request.model || "composer-2" },
    local: { cwd: request.cwd },
  };
  // 프로젝트(.cursor/rules 등)·사용자 레이어 규칙 로드 범위
  if (Array.isArray(request.settingSources) && request.settingSources.length > 0) {
    agentOptions.local.settingSources = request.settingSources;
  }
  // 커스텀 서브에이전트 정의 (이름 → {description, prompt})
  if (request.agents && typeof request.agents === "object") {
    const names = Object.keys(request.agents);
    if (names.length > 0) {
      agentOptions.agents = {};
      for (const name of names) {
        const def = request.agents[name] || {};
        agentOptions.agents[name] = {
          description: def.description || name,
          prompt: def.prompt || "",
          model: "inherit",
        };
      }
      writeLine("status", "커스텀 agent 로드: " + names.join(", "));
    }
  }
  // 대화 모드 (agent | plan)
  if (request.agentMode === "plan" || request.agentMode === "agent") {
    agentOptions.mode = request.agentMode;
  }
  const agent = await Agent.create(agentOptions);

  try {
    writeLine("status", "Cursor Agent 시작");
    const run = await agent.send(request.prompt);
    for await (const event of run.stream()) {
      const summary = summarizeEvent(event);
      events.push(summary);
      const status = extractStatus(event);
      if (status) {
        writeLine("status", "status: " + status);
      }
    }
    if (typeof run.wait === "function") {
      const result = await run.wait();
      finalStatus = summarizeValue(result);
      finalOk = !isErrorResult(result);
      finalText = finalMessage(result);
    }
  } finally {
    await disposeAgent(agent);
  }

  writeResult(finalOk,
    "Cursor Agent run 완료\n"
    + "cwd: " + request.cwd + "\n"
    + "model: " + (request.model || "composer-2") + "\n"
    + "events: " + events.length
    + (finalText ? "\n\nfinal:\n" + finalText : ""),
    {
      cwd: request.cwd,
      model: request.model,
      mode: request.mode,
      events,
      final: finalStatus
    });
}

function resolveCursorSdk() {
  try {
    return require.resolve("@cursor/sdk");
  } catch (error) {
    // no-op: global npm lookup below
  }
  try {
    const root = execSync("npm root -g", { encoding: "utf8" }).trim();
    const globalRequire = createRequire(path.join(root, "__llm_manager__.js"));
    return globalRequire.resolve("@cursor/sdk");
  } catch (error) {
    return null;
  }
}

async function loadCursorSdk(sdkPath) {
  try {
    return await import(pathToFileURL(sdkPath).href);
  } catch (error) {
    const sdkRequire = createRequire(__filename);
    return sdkRequire(sdkPath);
  }
}

function assertNodeVersion() {
  const major = Number.parseInt(process.versions.node.split(".")[0], 10);
  if (Number.isFinite(major) && major < 22) {
    throw new Error("@cursor/sdk 실행에는 Node.js 22 이상이 필요합니다. 현재: " + process.versions.node);
  }
}

async function disposeAgent(agent) {
  const disposer = agent && agent[Symbol.asyncDispose];
  if (typeof disposer === "function") {
    await disposer.call(agent);
  }
}

function summarizeEvent(event) {
  if (!event || typeof event !== "object") return summarizeValue(event);
  const type = event.type || event.kind || event.status || "event";
  const text = event.text || event.message || event.delta || event.content || "";
  if (typeof text === "string" && text.trim()) {
    return type + ": " + trim(text.trim(), 500);
  }
  return type + ": " + trim(summarizeValue(event), 500);
}

function extractStatus(event) {
  if (!event || typeof event !== "object") return "";
  if (event.type === "status" && event.status) return event.status;
  if (event.status && event.type !== "assistant") return event.status;
  return "";
}

function isErrorResult(result) {
  if (!result || typeof result !== "object") return false;
  const status = String(result.status || "").toLowerCase();
  return status === "error" || status === "failed" || status === "cancelled";
}

function finalMessage(result) {
  if (!result || typeof result !== "object") return summarizeValue(result);
  if (typeof result.result === "string" && result.result.trim()) {
    return result.result;
  }
  const candidates = [
    result.error,
    result.errorMessage,
    result.message,
    result.reason,
    result.failureReason
  ];
  for (const candidate of candidates) {
    if (typeof candidate === "string" && candidate.trim()) return candidate;
    if (candidate && typeof candidate === "object") return summarizeValue(candidate);
  }
  return summarizeValue(result);
}

function summarizeValue(value) {
  if (typeof value === "string") return trim(value, 1000);
  try {
    return trim(JSON.stringify(value), 1000);
  } catch (error) {
    return String(value);
  }
}

function trim(value, max) {
  return value.length > max ? value.slice(0, max) + "..." : value;
}

main().catch((error) => {
  process.stderr.write(error.message || String(error));
  process.exit(1);
});
