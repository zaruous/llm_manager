# LLM Manager — 프로젝트 가이드

## 프로젝트 개요

JavaFX 기반 LLM 서비스 관리 데스크톱 앱.
- 서비스 등록·시작·중지·설치·로그 모니터링
- 개발 모드 핫 리로드 (FXML/CSS/DEF 파일 감시)
- 기본 제공 서비스 정의 (`service-packs/*.yml`)와 사용자 서비스 (`~/llm-services/services.json`) 분리
- LLM 스킬 팩 설치와 외부 디렉토리 스킬·룰 파일 로드
- 플러그인 시스템: Cursor 에이전트 러너 포함

## 주요 경로

| 경로 | 역할 |
|------|------|
| `src/main/java/org/kyj/llmmanager/` | Java 소스 루트 |
| `src/main/resources/org/kyj/llmmanager/` | FXML / CSS / 도움말 리소스 |
| `src/main/resources/llm-skills/` | Claude/Copilot/Cursor/Gemini/Wiki-Agent 스킬 팩 리소스 |
| `plugins/wiki-agent/` | LLM Wiki Agent 플러그인 (manifest + 업스트림 Python 도구) |
| `service-packs/` | 기본 제공 서비스 YAML (배포 포함). `ServicePackLoader`가 로드 |
| `bin/` | 배포·설치 PowerShell 스크립트 |
| `lib/hotswap/hotswap-agent.jar` | 개발용 HotswapAgent (gitignore) |
| `lib/*.jar` | 번들 JAR (gitignore — 100 MB 초과) |
| `~/llm-services/services.json` | 사용자 서비스 목록 (런타임) |
| `~/llm-services/settings.json` | 앱 설정 |
| `~/llm-services/projects.json` | LLM 스킬 설치 히스토리 |

## 실행

```bash
# 프로덕션
./gradlew run

# 개발 모드 (FXML/CSS 핫 리로드 + HotswapAgent)
./gradlew downloadHotswapAgent   # 최초 1회
./gradlew runDev
```

## 배포 (jpackage)

```powershell
# 배포 이미지 생성 (Windows installer .exe)
./gradlew jpackageImage

# 산출물 위치
build/installer/LLMManager/
```

배포 구조:
```
LLMManager/
├── app/
│   ├── lib/           ← 앱 JAR + 의존성
│   ├── bin/           ← PowerShell 스크립트 (*.ps1)
│   └── service-packs/ ← 서비스 YAML 팩
└── runtime/
    └── bin/
        └── java.exe   ← jpackage가 제거하므로 doLast로 복사
```

> **주의**: jpackage는 번들 JRE에서 `java.exe`를 제거한다. `build.gradle`의 `copyJavaExeToRuntime()` 헬퍼가 빌드 JDK에서 `runtime/bin/`으로 복사한다.

## service-packs

`service-packs/*.yml`은 내장 서비스 정의 파일이다. `ServicePackLoader.resolvePacksDir()`이 두 단계로 경로를 탐색한다.

1. 실행 디렉토리의 `service-packs/` (개발 모드)
2. JAR 위치에서 상위 2단계까지 `service-packs/` 탐색 (배포 모드)

각 YAML에는 선택적으로 `groovyScript`를 포함할 수 있다. 스크립트 바인딩:

| 변수 | 설명 |
|------|------|
| `service` | `ServiceDefinition` 인스턴스 (변경 가능) |
| `os` | `"windows"` / `"linux"` / `"mac"` |
| `userHome` | 사용자 홈 디렉토리 |
| `arch` | `"x86_64"` 등 |
| `env(name)` | 환경변수 조회 클로저 |

## 빌드 상태

`./gradlew build` 정상 통과 (2026-06-25 기준).

- `BuiltinServiceLoader` 제거 → `ServicePackLoader`로 일원화
- `service-packs/` 디렉토리: `bgem3-embedding.yml`, `chroma-db.yml`, `sql-gen-mcp.yml`, `swagger-mcp.yml`, `wiki-mcp.yml`
- `chroma-db.yml`: ChromaDB 벡터 DB 템플릿 — 기본 포트 18000 (swagger-mcp·sql-gen-mcp의 chroma.url 기본값과 일치), `pip install chromadb` 자동 설치, 헬스체크 `/api/v2/heartbeat`
- `bgem3-embedding.yml`: CUDA 자동 감지(CUDA_PATH + nvcc), install-type/cuda-version argSpec 추가
- 서비스 목록 우클릭 컨텍스트 메뉴에 "제거" 기능 추가
- HikariCP 5.1.0 + sqlite-jdbc 3.45.2.0 의존성으로 `LlmSkillLibraryRepository` 컴파일

현재 UI의 "로드" 탭은 선택 파일을 대상 프로젝트에 복사한다 (DB 저장은 미연결).

### LLM Wiki Agent 통합 (2026-06-12)

`docs/done/llm-wiki-agent-통합계획.md`의 Phase 0~4 구현 완료 (완료 문서는 `docs/done/`에 보관).

- 스킬 팩: `tools.json`에 `wiki-agent` 도구 추가 (워크스페이스 골격/Claude/Gemini/Codex 팩)
- 플러그인: `plugins/wiki-agent/` — wiki.ingest/query/health/lint/graph/openGraph 커맨드
- `PluginCommandExecutor.runWikiTool()`은 워크스페이스 검증·tools 동기화 후 항상
  Cursor Agent에 위임한다 (v0.2.0에서 Python/litellm + ANTHROPIC_API_KEY 직접 실행 경로 제거)
- 업스트림 Python 도구(tools/*.py)는 그래프 빌드 등에서 에이전트가 활용할 수 있도록
  실행 전 플러그인 tools/를 워크스페이스 `tools/`로 동기화한다
- 전용 UI: `WikiQueryDialog`(CLI 세션형 — 이력은 워크스페이스 `.llm-manager/query-history.json`),
  `WikiIngestDialog`(멀티 파일/폴더 선택 → raw/분류 복사 → `WikiIngestPlanner` 작업 계획
  순차 실행 + 프로그레스바; 작은 파일 배치 / 큰 텍스트 섹션 노트→병합 / 큰 PDF는
  `WikiPageExtractor`+`tools/extract_pages.py`(pymupdf)로 페이지 이미지 전처리 후
  5페이지씩 이미지 판독→병합, 실패 시 단계 처리 폴백;
  raw 불변 복사·지원 확장자 필터·기수집 스킵·실행 전 횟수 확인),
  `WikiBrowserDialog`(트리 + WebView 마크다운 뷰, `[[WikiLink]]` 앱 내 이동 — help-template.html 재사용)
- 도움말에 "LLM Wiki Agent" 항목 추가 (`docs/wiki-agent.md`)
- 실행 경로는 cursor 전용 — Cursor 사이드카에 자연어 위임(`runWikiViaCursorAgent`),
  AGENTS.md 자동 설치, 필수 env는 `CURSOR_API_KEY` (plugin.json requires.env에 선언)
- 실행 제어: `timeoutMinutes` 설정(기본 무제한), 중지 버튼은
  `PluginCommandExecutor.cancel(commandId)` → `destroyProcessTree()`로 자식까지 종료.
  워크스페이스 선택은 `wiki.defaultCwd`에 자동 영속화(`WikiWorkspaceInitializer.rememberWorkspace`).
  사이드카 env 화이트리스트에 APPDATA 필수 — 없으면 `npm root -g`가 깨져 글로벌 SDK 탐색 실패

### Wiki Vector MCP + 버그 수정 (2026-06-25, feature/wiki-vector-mcp)

- **wiki_ingest 파라미터 재설계**: `title`, `content`, `desc=""`, `tags=""` — 저장 시 YAML
  frontmatter(`title`·`desc`·`tags`·`ingest_at`) + 마크다운 본문으로 스테이징 파일 생성
- **AGENTS.md 번들링 3중 보장**
  - `WikiWorkspaceInitializer` SKELETON 맵에 `/llm-skills/wiki-agent/AGENTS.md` 추가
  - `server.py`: 워크스페이스에 AGENTS.md 없으면 번들 파일에서 자동 복사
  - `wiki-mcp.yml` Groovy 스크립트: 설치 시 `plugins/wiki-agent/AGENTS.md` → installDir 복사
- **워크스페이스 초기화 UX**
  - `WikiBrowserDialog`: `wiki/index.md` 없으면 초기화 확인 → `WikiWorkspaceInitializer.initialize()`
  - `WikiQueryDialog`: 워크스페이스 미초기화 시 submit 전 확인 다이얼로그
  - `SettingsDialog`: `wiki.defaultCwd` 저장 시 미초기화 워크스페이스이면 초기화 제안
- **워크스페이스 찾기 버튼 제거**: `WikiQueryDialog`·`WikiIngestDialog`에서 제거
  (설정 → 환경설정에서 일원화, `WikiBrowserDialog`만 유지)
- **버그 수정**
  - `onUninstall()`: 실행 중 제거 동의 시 `stop()` 먼저 호출 (PID 잔류 방지)
  - WebView `IllegalAccessError`: `build.gradle`에 `--add-opens javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED` 추가 (applicationDefaultJvmArgs·startScripts·jpackage 세 곳)
- **관리 메모리 게이지** (대시보드 시스템 리소스 패널)
  - "JVM 힙" → "관리 메모리": OSHI `OperatingSystem.getProcess(pid).getResidentSetSize()` 합산
  - `SystemMonitorService`: `trackedPids[]` + `managedRss` volatile 필드, 백그라운드 `collect()`에서 2초마다 RSS 합산
  - `MainController.refreshTrackedPids()`: `updateSystemStats()` (2초 주기 Timeline) 에서 호출해 PID 설정 타이밍과 무관하게 항상 최신 목록 유지
  - 실행 중 서비스 없으면 "서비스 없음" 표시

---

## 주석 작성 규칙 (Java)

> 이 프로젝트의 모든 Java 코드는 아래 규칙에 따라 주석을 작성한다.

### 1. 클래스 — Javadoc 필수

```java
/**
 * [한 줄 역할 요약 — 무엇을 하는 클래스인지]
 *
 * [필요 시 두 번째 줄: 동작 방식·제약·협력 객체 설명]
 */
public class FooService { ... }
```

- **반드시 포함**: 이 클래스가 하는 일 (역할)
- **선택 포함**: 생명주기, 스레드 안전성, 외부 의존성

### 2. 필드 — 한 줄 Javadoc

```java
/** 서비스 ID → 인스턴스 맵. 중복 생성 방지를 위해 ConcurrentHashMap 사용. */
private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

/** null이면 미실행 상태. setProcess() 호출 시 pid도 함께 갱신. */
private Process process;
```

- 타입·이름으로 명백한 필드는 왜(WHY)·제약·특이사항 위주로 작성
- 단순 getter용 필드(`private String name;`)는 생략 가능

### 3. 메서드 — Javadoc + @param/@return

```java
/**
 * startCommand에 ArgSpec 값들을 이어붙여 실행 가능한 명령어를 반환한다.
 * BOOLEAN 타입은 true일 때만 플래그를 추가하고, 나머지는 --flag value 형식.
 *
 * @param def 명령어를 조립할 서비스 정의
 * @return 완성된 CLI 명령어 문자열
 */
public static String buildStartCommand(ServiceDefinition def) { ... }
```

- **반드시 포함**: 무엇을 하는지 (1줄), 비자명한 동작 설명
- **반드시 포함**: `@param` (2개 이상일 때), `@return` (void 제외)
- **선택 포함**: `@throws` (체크 예외), 사전조건·사후조건

### 4. 분기 (if / switch) — 의도가 불명확할 때만

```java
// BOOLEAN 타입은 값 없이 플래그만 추가
if ("BOOLEAN".equals(spec.getType())) {
    if ("true".equalsIgnoreCase(value)) sb.append(" ").append(spec.getFlag());
} else {
    sb.append(" ").append(spec.getFlag()).append(" ").append(value);
}
```

```java
// Windows는 taskkill /F로 강제 종료 (destroy()가 자식 프로세스를 남길 수 있음)
if (PlatformUtil.isWindows()) {
    Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/PID", ...});
} else {
    process.destroy();
}
```

- **주석 대상**: OS 분기, 타입 분기, 상태 분기처럼 WHY가 코드에서 안 보이는 경우
- **주석 불필요**: `if (value == null) return;` 처럼 의도가 자명한 가드절

### 5. 반복문 (for / while) — 비자명한 순서·조건만

```java
// stop 후 프로세스가 완전히 종료될 때까지 최대 4초 대기
for (int i = 0; i < 20; i++) {
    if (instance.getStatus() == ServiceStatus.STOPPED) break;
    Thread.sleep(200);
}
```

```java
// 5000줄 초과 시 앞 1000줄 제거 — 메모리 과다 사용 방지
if (logs.size() > 5000) logs.remove(0, 1000);
```

- **주석 대상**: 매직 넘버의 의미, 타임아웃 이유, 순서가 중요한 반복
- **주석 불필요**: `for (ArgSpec spec : def.getArgSpecs())` 같이 단순 순회

### 6. 인라인 주석 — 절제

```java
pb.redirectErrorStream(false);  // stdout/stderr를 별도로 읽어야 색상 구분 가능
```

- 코드 바로 위 또는 끝에 `//`로 작성
- 코드가 이미 말하는 것을 반복하지 않는다
- 라이브러리 제약·회피책·숨겨진 전제조건처럼 독자가 놀랄 수 있는 부분에만 작성

### 요약 판단 기준

| 상황 | 주석 필요? |
|------|-----------|
| 클래스 선언 | **항상** |
| public 메서드 | **항상** |
| private 유틸 메서드 | 로직이 복잡하면 |
| 필드 (자명하지 않은 것) | **항상** |
| if/for (WHY가 자명) | 불필요 |
| if/for (WHY가 불명확) | **필요** |
| 매직 넘버 | **항상** |
| 라이브러리 제약·버그 회피 | **항상** |
