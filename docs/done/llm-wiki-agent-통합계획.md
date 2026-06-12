# LLM Wiki Agent 통합 계획

> 작성일: 2026-06-12
> 대상: `llm_manager` (JavaFX 데스크톱 앱) ← `llm-wiki-agent` (D:\git\doc\llm-wiki-agent)

## 구현 체크리스트 (2026-06-12 코드 대조 검증)

### Phase 0 — 소스 반입·환경

- [x] 0-1 반입 방식 결정 — 선별 복사 (업스트림 커밋 `23491f2`, 2026-06-03)
- [x] 0-2 리소스 배치 — 스킬 정의 → `src/main/resources/llm-skills/wiki-agent/`,
      Python 도구 9종 → `plugins/wiki-agent/tools/`
- [x] 0-3 Python 환경 체크 — 별도 클래스 대신 기존 `SetupChecker.PYTHON` 재사용,
      litellm 미설치는 실행 시 stderr 감지로 `pip install litellm` 안내 (계획 변경)
- [x] 0-4 라이선스 확인 — MIT, `LICENSE.upstream` 사본 + 양쪽 `UPSTREAM.md`에 출처·커밋 기록

### Phase 1 — 스킬 팩 (A안)

- [x] 1-1 `tools.json` 메타데이터 — `wiki-agent` 도구 등록
- [x] 1-2 팩 리소스 구성 — `wiki-claude`(커맨드 4종+CLAUDE.md) / `wiki-gemini` / `wiki-codex`
- [x] 1-3 디렉토리 골격 — `wiki-skeleton` 팩 + 수집 다이얼로그의 즉석 초기화
      (`WikiWorkspaceInitializer`)로 이중 제공
- [x] 1-4 설치 히스토리 연동 — 기존 `projects.json` 메커니즘 그대로 재사용 (코드 변경 없음)

### Phase 2 — 플러그인 커맨드 (B안 1단계)

- [x] 2-1 매니페스트 — `plugins/wiki-agent/plugin.json`, 커맨드 7종
      (ingest/query/health/lint/graph/openGraph/browse) + Wiki Agent 설정 탭
      (단위 테스트 `PluginManagerWikiAgentTest`로 로딩 검증)
- [x] 2-2 커맨드 → Python 도구 매핑 — `runWikiTool()` + `buildWikiToolArgs()`
- [x] 워크스페이스 개념 — `wiki.defaultCwd` 설정 + 선택 시 자동 영속화
- [x] 프로세스 실행 — `PYTHONIOENCODING=utf-8`/`PYTHONUTF8=1` 주입, 출력 스트리밍
- [x] 실행 전 체크 — 골격(wiki/index.md) 검사, 미초기화 시 수집 다이얼로그가 초기화 제안
- [x] 메뉴 통합 — `rebuildPluginMenu()` 자동 반영 + 전용 다이얼로그 라우팅
- [x] 입력 다이얼로그 — `WikiIngestDialog`(4.3 멀티 선택·폴더 추가·raw 분류 복사),
      `WikiQueryDialog`(4.4 CLI 세션·이력 영속화)
- [x] 검증(무료 경로) — `health.py`를 실제 워크스페이스 골격에서 실행해 리포트 생성 확인
- [ ] 검증(LLM 경로) — ingest→query 종단 실행은 API 키 환경에서 수동 확인 필요
      (코드 레벨 인자 매핑·`--save` 비대화형 처리·모델 폴백은 검증됨)

### Phase 3 — 위키 뷰어 (B안 2단계)

- [x] 3-1 그래프 뷰어 — `wiki.openGraph` 커맨드 + 브라우저 창 '그래프' 버튼
      (OS 기본 브라우저로 graph.html 열기)
- [x] 3-2 위키 브라우저 — `WikiBrowserDialog`: 좌측 트리(문서/카테고리별 페이지 수/리포트)
      + WebView 마크다운 렌더링 (help-template.html 재사용)
- [x] 3-3 `[[WikiLink]]` 내비게이션 — wiki: 스킴 변환(없는 페이지 회색) +
      JS 클릭 인터셉터·title 브리지로 앱 내 이동, 상대 `.md` 링크도 지원, 뒤로 가기 스택
- [x] 3-4 리포트 뷰 — health/lint 리포트를 트리 '리포트' 노드로 열람
- [x] 3-5 화면 구성 — **탭 대신 별도 비모달 창으로 구현** (계획이 허용한 대안 —
      main.fxml 탭은 서비스 상세 종속이라 워크스페이스 단위 뷰와 부적합)

### Phase 4 — 배포·문서화

- [x] 4-1 jpackage 포함 — `build.gradle`의 `from('plugins') → lib/plugins` 복사로 자동 충족
- [x] 4-2 설치 스크립트 pip 안내 — `bin/install-python.ps1` 완료 메시지에
      litellm(필수)·markitdown·networkx(선택) 설치 안내 추가
- [x] 4-3 사용자 문서 — 앱 내 도움말 "LLM Wiki Agent" 항목 (`docs/wiki-agent.md`)
- [x] 4-3b `docs/llm-manager/wiki-agent.md` — 저장소 보관용 사용 가이드 (아키텍처·설정·트러블슈팅)
- [x] 4-4 CLAUDE.md 갱신 — 주요 경로 표 + 통합 내역 섹션

> 유일한 미완 항목은 Phase 2의 "검증(LLM 경로)" — `ANTHROPIC_API_KEY` 또는
> `CURSOR_API_KEY` 환경에서 ingest→query 한 사이클을 수동 확인하면 전체 완료.

**구현 중 발견한 업스트림 제약 (계획과의 차이):**

1. **도구의 경로 해석** — 업스트림 tools/*.py는 위키 경로를 CWD가 아닌 스크립트 위치
   기준(`Path(__file__).parent.parent`)으로 해석한다. 도구를 패치하는 대신, 실행 전마다
   플러그인의 tools/를 워크스페이스 `tools/`로 동기화(덮어쓰기)하는 방식을 택했다 —
   업스트림 레이아웃 그대로이고 워크스페이스가 CLI로도 자급 가능해진다.
2. **query.py `--save`의 인터랙티브 프롬프트** — 경로 없는 `--save`는 `input()`으로
   파일명을 물어 비대화형 실행이 멈춘다. 타임스탬프 슬러그(`syntheses/query-<ts>.md`)를
   항상 함께 전달한다.
3. **retire된 기본 모델** — 리스크 표에 적은 대로 업스트림 기본값(`claude-3-5-*`)은
   404가 난다. 실행 환경에 `LLM_MODEL`/`LLM_MODEL_FAST`가 없으면 플러그인 설정 →
   `claude-sonnet-4-6`/`claude-haiku-4-5` 순으로 폴백 주입한다.

**계획 이후 추가된 기능:**

- **실행 에이전트 선택 (claude | cursor 드롭다운)** — 설정 탭의 `wiki.agent`로 위키
  작업의 실행 주체를 고른다. `claude`(기본)는 litellm 도구 직접 실행, `cursor` 선택 시
  **Cursor Agent Runner의 사이드카에 자연어 프롬프트로 위임**한다
  (`runWikiViaCursorAgent`). 에이전트는 워크스페이스의 AGENTS.md 워크플로우를
  따르며, 없으면 내장 리소스에서 자동 설치. 필수 환경변수도 에이전트에 따라
  전환된다 — claude=`ANTHROPIC_API_KEY`, cursor=`CURSOR_API_KEY`
  (`effectiveRequiredEnv`).
- **워크스페이스 골격 즉석 초기화** — 수집 다이얼로그가 `wiki/index.md` 부재를 감지하면
  초기화를 제안하고 즉시 생성한다 (`WikiWorkspaceInitializer`). 스킬 설치 화면 불필요.
- **워크스페이스 영속화** — 수집·질의·브라우저에서 선택한 워크스페이스를
  `wiki.defaultCwd` 설정에 자동 저장해 다음 실행 시 복원.
- **실행 타임아웃 설정** — `timeoutMinutes` 설정(기본 무제한). 초과 시
  `destroyProcessTree()`로 자식 프로세스까지 강제 종료해 고아 에이전트의 과금 지속을 방지.
- **중지 버튼** — 수집·질의 다이얼로그에서 실행 중인 작업을 즉시 종료
  (`PluginCommandExecutor.cancel(commandId)` — Cursor 위임 실행도 원래 wiki command id로 추적).
- **사이드카 환경변수 수정** — 화이트리스트에 `APPDATA`/`LOCALAPPDATA`/`ProgramFiles` 추가.
  APPDATA 부재 시 `npm root -g`가 깨진 경로를 반환해 글로벌 @cursor/sdk를 못 찾던 버그 해결.
- **사이드카 출력 개선** — 반복되는 `status: running/completed` 노이즈 억제, 대신
  에이전트의 응답·사고(thinking) 텍스트를 줄 버퍼링으로 스트리밍. think ↔ 응답 구간
  전환 시 `------` 구분자 출력.
- **응답 언어 설정** — Cursor 설정 탭의 `cursor.language`(한국어/English 드롭다운, 기본
  한국어). 사고 과정 포함 전체 출력을 해당 언어로 작성하라는 지시문을 프롬프트에 주입
  (`languageDirective` — 직접 실행·위키 위임 공통 적용).
- **위키 브라우저 링크 수정** — WebKit이 커스텀 스킴 내비게이션을 무시해 클릭이 무반응이던
  문제를 JS 클릭 인터셉터 + `document.title` 브리지로 해결. 상대 `.md` 링크 지원 추가.
- **위키 브라우저 키워드 검색** — 트리 상단 검색창(Enter). 제목·본문 부분 일치를
  백그라운드 스캔으로 검색하고 결과 목록으로 트리 전환 (제목 일치 ★ 우선 정렬,
  검색어 비우면 원래 트리 복원). 차기 과제 Phase 5의 시맨틱 검색 전 단계.

---

## 1. 배경

### 1.1 llm-wiki-agent란

원본 문서(`raw/`)를 수집하면 AI 에이전트가 분석하여 상호 연결된 마크다운 위키(`wiki/`)와
지식 그래프(`graph/`)를 자동 구축·유지하는 **에이전트 스킬 모음**이다. RAG와 달리 한 번
컴파일된 위키가 지속적으로 누적되며, 모순 검출·커뮤니티 분석이 내장되어 있다.

| 구성 요소 | 내용 |
|-----------|------|
| `.claude/commands/*.md` | 4개 스킬 정의: `wiki-ingest`, `wiki-query`, `wiki-lint`, `wiki-graph` |
| `tools/*.py` | 독립 실행 Python 도구 (~3,300줄): `ingest.py`, `query.py`, `lint.py`, `health.py`, `build_graph.py`, `pdf2md.py` 등 |
| `wiki/` | 에이전트가 소유하는 위키 데이터 (sources / entities / concepts / syntheses + index.md, log.md, overview.md) |
| `raw/` | 불변 원본 문서 저장소 |
| `graph/` | 자동 생성 산출물 (`graph.json`, `graph.html` — vis.js 자체 포함 HTML) |
| `CLAUDE.md` / `AGENTS.md` / `GEMINI.md` | 에이전트별 워크플로우 스키마 |

### 1.2 실행 모드 두 가지

1. **에이전트 모드** — Claude Code / Codex / Gemini CLI가 `CLAUDE.md` + 커맨드 정의를 읽고
   직접 위키를 관리. Python 불필요, 에이전트 CLI만 필요.
2. **스크립트 모드** — `python tools/ingest.py ...` 형태로 독립 실행. Python 3.8+ 와
   `litellm`(필수), `markitdown`·`networkx`(선택) 및 API 키(`ANTHROPIC_API_KEY` 등) 필요.

### 1.3 llm_manager의 기존 확장 지점

| 확장 지점 | 구현 | 통합 적합성 |
|-----------|------|------------|
| **플러그인 시스템** | `plugins/<id>/plugin.json` 매니페스트 → `PluginManager` 로드 → `MainController.rebuildPluginMenu()`로 동적 메뉴 → `PluginCommandExecutor` 실행. Cursor Agent Runner가 선례 (Node 사이드카 + 설정 탭 스키마) | ★★★ 핵심 통합 경로 |
| **LLM 스킬 팩 설치** | `src/main/resources/llm-skills/tools.json` 메타데이터 + 팩별 리소스 → `LlmSkillInstaller`가 대상 프로젝트에 복사 (템플릿 변수 치환·백업 지원) | ★★★ 스킬 배포 경로 |
| **service-packs** | 장기 실행 서비스 YAML 정의 (`ServicePackLoader`) | ☆ 위키 도구는 단발성 실행이라 부적합 |
| **환경 체크** | Node.js 환경 체크 선례 있음 (Cursor 플러그인) | ★★ Python 체크에 재활용 |

---

## 2. 통합 전략 개요

**두 갈래로 통합한다.** 서로 독립적이며 단계적으로 진행 가능하다.

```
┌─────────────────────────────────────────────────────────────┐
│ llm_manager                                                  │
│                                                              │
│  A. 스킬 팩 통합 (배포자 역할)                                  │
│     llm-skills/wiki-agent/ ──install──▶ 사용자 프로젝트에       │
│     .claude/commands/wiki-*.md + CLAUDE.md 섹션 설치           │
│                                                              │
│  B. 플러그인 통합 (실행기 역할)                                  │
│     plugins/wiki-agent/ ──▶ 위키 워크스페이스 선택              │
│     ├─ Ingest / Query / Lint / Health / Graph 커맨드 실행      │
│     ├─ 설정 탭 (LLM_MODEL, API 키, Python 경로)               │
│     └─ WebView로 graph.html 시각화                            │
└─────────────────────────────────────────────────────────────┘
```

- **A안 (스킬 팩)**: llm_manager가 이미 잘하는 일(스킬 설치)의 자연스러운 확장. 사용자가
  자기 프로젝트에 위키 스킬을 깔고 Claude Code 등으로 직접 사용. **저비용·저위험.**
- **B안 (플러그인)**: llm_manager 안에서 위키를 직접 운영하는 경험 제공. Python 스크립트
  모드를 래핑하고, 그래프 시각화까지 앱 내에서 제공. **고가치·중간 비용.**

---

## 3. 단계별 계획

### Phase 0 — 소스 반입 및 환경 체크 기반 작업

**목표**: llm-wiki-agent 자산을 llm_manager 저장소에 반입하고 Python 환경 체크를 마련한다.

| 작업 | 상세 |
|------|------|
| 0-1. 반입 방식 결정 | 필요한 파일만 **선별 복사**를 권장: 스킬 정의(`.claude/commands/*.md`), `CLAUDE.md` 스키마, `tools/*.py`. git subtree/submodule은 외부 저장소 변경 추적이 필요할 때만 고려 |
| 0-2. 리소스 배치 | 스킬 정의 → `src/main/resources/llm-skills/wiki-agent/` (A안용)<br>Python 도구 → `plugins/wiki-agent/tools/` (B안용) |
| 0-3. Python 환경 체크 유틸 | 기존 Node.js 환경 체크와 동일한 패턴으로 `PythonEnvironmentChecker` 작성: `python --version`(3.8+), `pip show litellm` 확인. 미설치 시 안내 다이얼로그 |
| 0-4. 라이선스 확인 | llm-wiki-agent의 LICENSE를 확인하고 반입 파일에 출처 명기 |

**산출물**: 반입된 리소스 디렉토리, `PythonEnvironmentChecker` 클래스.

---

### Phase 1 — 스킬 팩 통합 (A안)

**목표**: 사용자가 llm_manager의 스킬 설치 UI에서 "Wiki Agent" 팩을 선택해 자기 프로젝트에
설치할 수 있다. 설치 후엔 해당 프로젝트에서 Claude Code/Gemini CLI로 `/wiki-ingest` 등을
바로 사용 가능하다.

| 작업 | 상세 |
|------|------|
| 1-1. `tools.json` 메타데이터 추가 | `llm-skills/tools.json`에 wiki-agent 팩 항목 등록 (기존 Claude/Copilot/Cursor/Gemini 팩과 동일 스키마) |
| 1-2. 팩 리소스 구성 | 에이전트별 설치 대상 매핑:<br>• Claude → `.claude/commands/wiki-*.md` 4종 + `CLAUDE.md` 위키 스키마 섹션<br>• Gemini → `GEMINI.md`<br>• Codex/기타 → `AGENTS.md` |
| 1-3. 디렉토리 골격 생성 | 설치 시 대상 프로젝트에 `raw/`, `wiki/`(+ `index.md`, `log.md` 초기 파일), `graph/` 골격과 `.gitignore` 항목(`graph/.cache.json` 등)을 생성하는 옵션 추가. `LlmSkillInstaller`의 템플릿 변수 치환 기능 활용 |
| 1-4. 설치 히스토리 연동 | 기존 `~/llm-services/projects.json` 히스토리에 그대로 기록 (추가 작업 불필요 — 회귀 확인만) |

**검증**: 빈 프로젝트에 팩 설치 → Claude Code로 열어 `/wiki-ingest` 실행이 동작하는지 확인.

**예상 규모**: 소 (코드 변경 거의 없음, 리소스 추가 위주).

---

### Phase 2 — 플러그인 통합: 커맨드 실행 (B안 1단계)

**목표**: `plugins/wiki-agent/`를 추가해 llm_manager 메뉴에서 위키 작업을 직접 실행한다.

#### 2-1. 플러그인 매니페스트 (`plugins/wiki-agent/plugin.json`)

Cursor Agent Runner 매니페스트 스키마를 그대로 따른다.

```json
{
  "id": "wiki-agent",
  "name": "LLM Wiki Agent",
  "version": "0.1.0",
  "type": "local",
  "permissions": ["filesystem.read", "filesystem.write", "process.exec", "network"],
  "contributes": {
    "commands": [
      { "id": "wiki.ingest",  "title": "문서 수집 (Ingest)",   "requires": { "cwd": true } },
      { "id": "wiki.query",   "title": "위키 질의 (Query)",    "requires": { "cwd": true } },
      { "id": "wiki.health",  "title": "구조 검사 (Health)",   "requires": { "cwd": true } },
      { "id": "wiki.lint",    "title": "품질 검사 (Lint)",     "requires": { "cwd": true } },
      { "id": "wiki.graph",   "title": "그래프 빌드 (Graph)",  "requires": { "cwd": true } }
    ],
    "settingsTabs": [
      {
        "id": "wiki.settings", "title": "Wiki Agent", "type": "schema",
        "sections": [
          { "title": "Environment", "fields": [
            { "key": "ANTHROPIC_API_KEY", "kind": "env", "secret": true },
            { "key": "LLM_MODEL",       "kind": "env", "defaultValue": "claude-sonnet-4-6" },
            { "key": "LLM_MODEL_FAST",  "kind": "env", "defaultValue": "claude-haiku-4-5" }
          ]},
          { "title": "Defaults", "fields": [
            { "key": "wiki.pythonPath",   "kind": "directory" },
            { "key": "wiki.workspaceDir", "kind": "directory" }
          ]}
        ]
      }
    ]
  }
}
```

#### 2-2. 커맨드 → Python 도구 매핑

| 커맨드 | 실행 | 입력 UI | 출력 처리 |
|--------|------|---------|----------|
| `wiki.ingest` | `python tools/ingest.py <path> [path2 ...]` | 파일 멀티 선택 + 폴더 추가 다이얼로그 → `raw/`에 복사 후 일괄 실행 | 로그 패널에 스트리밍 |
| `wiki.query` | `python tools/query.py "<q>" --save` | 질문 입력 다이얼로그 | 응답을 다이얼로그/로그에 표시 |
| `wiki.health` | `python tools/health.py --save` | 없음 (LLM 미호출, 무료) | `health-report.md` 열기 |
| `wiki.lint` | `python tools/lint.py --save` | 실행 전 비용 경고 (LLM 호출 발생) | `lint-report.md` 열기 |
| `wiki.graph` | `python tools/build_graph.py` | 없음 | Phase 3에서 OS 기본 브라우저로 열기 연동 |

#### 2-3. 구현 작업

| 작업 | 상세 |
|------|------|
| 워크스페이스 개념 | 위키 루트 디렉토리(= `raw/`·`wiki/`·`graph/`를 가진 폴더)를 선택·기억. `settings.json`의 플러그인 설정(`wiki.workspaceDir`)에 저장 |
| 프로세스 실행 | `PluginCommandExecutor` 재사용. 작업 디렉토리 = 워크스페이스, 환경변수 = 설정 탭 값 주입. **stdout/stderr는 기존 로그 패널(`LogService`)로 스트리밍** |
| Python 호출 인코딩 | Windows에서 `PYTHONIOENCODING=utf-8` 환경변수를 주입해 한글 출력 깨짐 방지 |
| 실행 전 체크 | Phase 0의 `PythonEnvironmentChecker` + 워크스페이스 유효성(`wiki/index.md` 존재) 확인. 워크스페이스가 비어 있으면 골격 초기화 제안 |
| 메뉴 통합 | `rebuildPluginMenu()`가 매니페스트의 commands를 자동 반영하므로 추가 작업 최소화. 커맨드별 입력 다이얼로그(파일 선택·질문 입력)만 신규 구현 |

**검증**: 샘플 문서 1개로 ingest → health → graph 전체 플로우가 메뉴에서 완주되는지 확인.

**예상 규모**: 중 (플러그인 리소스 + 입력 다이얼로그 2종 + 환경변수 주입 로직).

---

### Phase 3 — 플러그인 통합: 위키 뷰어 UI (B안 2단계)

**목표**: 위키 콘텐츠와 지식 그래프를 llm_manager 안에서 직접 본다.

> **그래프 뷰어는 JavaFX WebView가 아닌 OS 기본 브라우저로 연다.**
> 근거: ① `graph.html`은 vis-network를 unpkg CDN에서 로드하므로(`build_graph.py:644`)
> WebView로 띄워도 네트워크가 필요하다. ② WebView(WebKit)의 canvas 렌더링은 노드 수백 개
> 규모의 물리 시뮬레이션에서 성능이 부족하다. ③ 업스트림 도구 자체가
> `build_graph.py --open`으로 브라우저에 여는 것을 표준 사용법으로 삼고, HTML 안에
> 노드 미리보기·마크다운 렌더링·필터 UI가 내장돼 있어 앱에서 보완할 것이 없다.

| 작업 | 상세 |
|------|------|
| 3-1. 그래프 뷰어 | `wiki.graph` 실행 완료 시 `HostServices.showDocument()`(또는 `java.awt.Desktop.browse()`)로 `graph/graph.html`을 OS 기본 브라우저에 연다. 메뉴에 "그래프 열기" 항목 추가 (그래프가 이미 빌드된 경우 재빌드 없이 바로 열기) |
| 3-2. 위키 브라우저 탭 | 좌측 트리(`wiki/` 디렉토리 구조: sources/entities/concepts/syntheses) + 우측 마크다운 렌더링. 기존 도움말 리소스 렌더링 방식이 있으면 재사용, 없으면 commonmark-java 등 경량 라이브러리 검토 |
| 3-3. `[[WikiLink]]` 내비게이션 | 렌더링 시 위키링크를 앱 내 페이지 이동 링크로 변환. 끊긴 링크는 회색 처리 |
| 3-4. 리포트 뷰 | `health-report.md` / `lint-report.md`를 같은 마크다운 뷰어로 표시 |
| 3-5. FXML 구성 | `wiki-view.fxml` + `WikiViewController` 신규. main.fxml 탭 또는 별도 창 — 기존 탭 구조(개요/로그/설정/설치)에 맞춰 탭 추가를 권장 |

**검증**: `wiki.graph` 실행 → 브라우저에 그래프가 열리고, 앱 위키 브라우저 탭에서
`[[WikiLink]]` 클릭으로 페이지 간 이동이 되는지 확인.

**예상 규모**: 중~대 (신규 FXML/컨트롤러 + 마크다운 렌더링).

---

### Phase 4 — 배포 및 문서화

| 작업 | 상세 |
|------|------|
| 4-1. jpackage 포함 | `plugins/wiki-agent/`(tools 포함)과 `llm-skills/wiki-agent/` 리소스가 배포 이미지에 포함되도록 `build.gradle` 확인. 기존 배포 구조상 `app/` 하위에 plugins 디렉토리 복사 단계 필요 여부 점검 |
| 4-2. 설치 스크립트 | `bin/` PowerShell 설치 스크립트에 Python 의존성 안내(자동 설치 X — 안내만) 추가 검토 |
| 4-3. 사용자 문서 | `docs/llm-manager/`에 위키 에이전트 사용 가이드 추가. 앱 내 도움말 리소스에도 항목 추가 |
| 4-4. CLAUDE.md 갱신 | 프로젝트 가이드의 주요 경로 표·빌드 상태 섹션에 wiki-agent 항목 반영 |

---

## 4. 플러그인 UI 설계 (와이어프레임)

### 4.1 플러그인 메뉴 (Phase 2)

`rebuildPluginMenu()`가 매니페스트의 `contributes.commands`를 읽어 메뉴를 구성한다.
워크스페이스가 미설정이면 커맨드 항목은 비활성화하고 "워크스페이스 선택…"만 활성화한다.

```
┌──────────────────────────────────────────────────────────────┐
│ 파일   서비스   플러그인   도움말                                │
├──────────────────────────────────────────────────────────────┤
│           ┌─────────────────────────────┐                     │
│           │ Cursor Agent Runner       ▶ │                     │
│           │ LLM Wiki Agent            ▶ │──┐                  │
│           └─────────────────────────────┘  │                  │
│              ┌─────────────────────────────┴───┐              │
│              │ 워크스페이스 선택…                │              │
│              │ ─────────────────────────────── │              │
│              │ 문서 수집 (Ingest)…              │              │
│              │ 위키 질의 (Query)…               │              │
│              │ ─────────────────────────────── │              │
│              │ 구조 검사 (Health)        무료    │              │
│              │ 품질 검사 (Lint)        LLM 호출  │              │
│              │ ─────────────────────────────── │              │
│              │ 그래프 빌드 (Graph)     LLM 호출  │              │
│              │ 그래프 열기 (브라우저)            │              │
│              └─────────────────────────────────┘              │
└──────────────────────────────────────────────────────────────┘
```

### 4.2 워크스페이스 선택 / 초기화 다이얼로그 (Phase 2)

선택한 디렉토리에 `wiki/index.md`가 없으면 골격 초기화를 제안한다.

```
┌─ 위키 워크스페이스 선택 ──────────────────────────────────────┐
│                                                              │
│  워크스페이스 디렉토리                                          │
│  ┌─────────────────────────────────────────────┐ ┌────────┐  │
│  │ D:\notes\my-wiki                            │ │ 찾아보기 │  │
│  └─────────────────────────────────────────────┘ └────────┘  │
│                                                              │
│  ⚠ 이 디렉토리에 wiki/index.md가 없습니다.                      │
│                                                              │
│  ☑ 위키 골격 초기화 (raw/, wiki/, graph/ 생성)                  │
│  ☑ .gitignore에 graph 캐시 항목 추가                           │
│                                                              │
│                                  ┌────────┐  ┌────────────┐  │
│                                  │  취소   │  │    확인     │  │
│                                  └────────┘  └────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 4.3 문서 수집 (Ingest) 다이얼로그 (Phase 2)

**파일 멀티 선택을 지원한다.** `ingest.py`가 다중 경로·디렉토리 인자를 받으므로
(`Usage: ingest.py <path> [path2 ...] [dir1 ...]`), UI는
`FileChooser.showOpenMultipleDialog()`로 여러 파일을, "폴더 추가"로 디렉토리를 받아
선택 항목 전체를 `raw/<하위분류>/`에 복사한 뒤 한 번의 `ingest.py` 호출로 전달한다.

```
┌─ 문서 수집 (Ingest) ─────────────────────────────────────────┐
│                                                              │
│  수집할 파일·폴더                       ┌─────────┐ ┌────────┐ │
│                                       │ 파일 추가 │ │ 폴더 추가│ │
│                                       └─────────┘ └────────┘ │
│  ┌────────────────────────────────────────────────────────┐  │
│  │ C:\Downloads\llama2-paper.pdf                      [×] │  │
│  │ C:\Downloads\gpt4-report.pdf                       [×] │  │
│  │ D:\notes\meetings\                (폴더, 14개 파일)  [×] │  │
│  └────────────────────────────────────────────────────────┘  │
│   (md/pdf/docx/pptx/html/txt 등 — 비마크다운은 자동 변환)        │
│                                                              │
│  raw/ 하위 분류                                                │
│  ┌─────────────────────────────────────────────┐             │
│  │ papers                                   ▼  │             │
│  └─────────────────────────────────────────────┘             │
│                                                              │
│  ℹ 총 16개 파일 — 파일 수에 비례해 LLM 호출이 발생합니다          │
│                                                              │
│                                  ┌────────┐  ┌────────────┐  │
│                                  │  취소   │  │  수집 시작  │  │
│                                  └────────┘  └────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 4.4 위키 질의 (Query) — CLI 스타일 대화 이력 UI (Phase 2)

단발 다이얼로그가 아닌 **터미널/채팅처럼 과거 질문·응답이 위로 쌓이는 세션 창**으로
구성한다. 상단은 스크롤 가능한 이력 영역, 하단은 입력창 — CLI 사용 경험과 동일하게
이전 문답을 보면서 이어서 질문할 수 있다.

```
┌─ 위키 질의 (Query) — D:\notes\my-wiki ───────────────────────┐
│  ┌────────────────────────────────────────────────────────┐  │
│  │ [06-10 14:22] > 지금까지 수집한 논문들의 공통 주제는?       │  │
│  │                                                        │  │
│  │   수집된 논문들은 [[Attention]]과 [[RLHF]]를 중심으로      │  │
│  │   세 가지 흐름이 보입니다. 첫째, ...                       │  │
│  │                              [응답 저장됨 → syntheses/]  │  │
│  │ ────────────────────────────────────────────────────── │  │
│  │ [06-11 09:05] > RLHF 관련 모순된 주장이 있었나?            │  │
│  │                                                        │  │
│  │   [[Llama 2 Paper]]와 [[GPT-4 Report]] 간에 보상 모델    │  │
│  │   크기에 대한 상반된 결론이 기록되어 있습니다...             │  │
│  │                                        [저장] [복사]    │  │
│  │ ────────────────────────────────────────────────────── │  │
│  │ [06-12 11:30] > 다음으로 수집하면 좋을 자료는?              │  │
│  │   ⠹ 질의 중... (위키 페이지 8개 읽는 중)                   │  │
│  │                                                        │  │
│  └────────────────────────────────────────────────────▲▼──┘  │
│                                                              │
│  > ┌─────────────────────────────────────────┐ ┌─────────┐  │
│    │ 질문 입력... (Enter로 전송)                │ │  질의    │  │
│    └─────────────────────────────────────────┘ └─────────┘  │
│  ☐ 응답을 wiki/syntheses/ 에 자동 저장 (--save)                │
└──────────────────────────────────────────────────────────────┘
```

**동작 설계:**

| 항목 | 상세 |
|------|------|
| 이력 영역 | 질문(`>` 프리픽스 + 타임스탬프)과 응답이 교대로 쌓이는 스크롤 뷰. 실행 중에는 스피너 + 진행 로그 표시 |
| 이력 영속화 | 워크스페이스 하위 `.llm-manager/query-history.json`에 문답 기록 저장 → 창을 다시 열어도 과거 이력 복원. `wiki/log.md`의 query 기록과는 별개(log.md는 질문만 기록되고 응답 본문이 없음) |
| 응답별 액션 | 각 응답에 [저장](→ `wiki/syntheses/`) / [복사] 버튼. `--save` 체크 시 자동 저장 |
| `[[WikiLink]]` | 응답 내 위키링크는 클릭 시 위키 브라우저 탭(Phase 3)으로 이동 — Phase 2에서는 일반 텍스트 |
| 독립 실행 주의 | `query.py`는 호출마다 독립 실행(stateless)이므로 이력은 **UI 차원의 기록**이다. 이전 문답이 다음 질의의 컨텍스트로 전달되지는 않음 — 후속 질문은 위키 자체에 누적된 내용(저장된 syntheses 포함)을 통해 간접적으로 연결된다. 이 한계를 입력창 placeholder 또는 도움말에 명시 |

### 4.5 설정 탭 — Wiki Agent (Phase 2)

매니페스트 `settingsTabs` 스키마가 기존 플러그인 설정 렌더러로 자동 생성된다.

```
┌─ 설정 ────────────────────────────────────────────────────────┐
│ ┌────────┬─────────┬────────────┐                             │
│ │  일반   │ Cursor  │ Wiki Agent │                             │
│ └────────┴─────────┴────────────┘                             │
│                                                               │
│  Environment                                                  │
│  ─────────────────────────────────────────────────────────    │
│  ANTHROPIC_API_KEY *  ┌──────────────────────────┐ (secret)   │
│                       │ ●●●●●●●●●●●●●●●●         │            │
│                       └──────────────────────────┘            │
│  LLM_MODEL            ┌──────────────────────────┐            │
│                       │ claude-sonnet-4-6        │            │
│                       └──────────────────────────┘            │
│  LLM_MODEL_FAST       ┌──────────────────────────┐            │
│                       │ claude-haiku-4-5         │            │
│                       └──────────────────────────┘            │
│                                                               │
│  Defaults                                                     │
│  ─────────────────────────────────────────────────────────    │
│  Python 경로           ┌──────────────────────┐ ┌──────────┐   │
│                       │ (PATH에서 자동 감지)   │ │ 찾아보기  │   │
│                       └──────────────────────┘ └──────────┘   │
│  기본 워크스페이스       ┌──────────────────────┐ ┌──────────┐   │
│                       │ D:\notes\my-wiki     │ │ 찾아보기  │   │
│                       └──────────────────────┘ └──────────┘   │
│                                                               │
│  환경 체크: ✔ Python 3.11.4   ✔ litellm   ✖ markitdown (선택)  │
│                                                               │
│                                              ┌────────────┐   │
│                                              │    저장     │   │
│                                              └────────────┘   │
└───────────────────────────────────────────────────────────────┘
```

### 4.6 위키 브라우저 탭 (Phase 3)

기존 메인 탭(개요/로그/설정/설치) 옆에 "위키" 탭을 추가한다.
좌측 트리 + 우측 마크다운 뷰, `[[WikiLink]]`는 앱 내 페이지 이동 링크로 변환.

```
┌─ LLM Manager ────────────────────────────────────────────────────┐
│ ┌──────┬──────┬──────┬──────┬──────┐                             │
│ │ 개요  │ 로그  │ 설정  │ 설치  │ 위키  │                             │
│ └──────┴──────┴──────┴──────┴──────┘                             │
│ ┌─────────────────────┬───────────────────────────────────────┐  │
│ │ 워크스페이스          │  Attention                 (concept)  │  │
│ │ D:\notes\my-wiki  ▼ │  ───────────────────────────────────  │  │
│ │ ─────────────────── │  last_updated: 2026-06-12             │  │
│ │ ▼ sources (12)      │                                       │  │
│ │   · llama2-paper    │  Transformer의 핵심 메커니즘으로,        │  │
│ │   · gpt4-report     │  [[Llama 2 Paper]]와 [[GPT-4 Report]]  │  │
│ │ ▼ entities (8)      │  에서 공통적으로 다뤄진다...              │  │
│ │   · Meta AI         │                                       │  │
│ │   · OpenAI          │  ## Connections                       │  │
│ │ ▼ concepts (15)     │  - [[RLHF]] — 학습 기법으로 연결         │  │
│ │   · Attention   ◀── │  - [[Scaling Laws]] — 상위 개념        │  │
│ │   · RLHF            │                                       │  │
│ │ ▶ syntheses (3)     │  ## Contradictions                    │  │
│ │ ─────────────────── │  (없음)                                │  │
│ │ 리포트               │                                       │  │
│ │   · health-report   │ ┌──────────┐ ┌──────────┐ ┌────────┐  │  │
│ │   · lint-report     │ │ ◀ 뒤로    │ │ 새로고침   │ │ 그래프  │  │  │
│ └─────────────────────┴─┴──────────┴─┴──────────┴─┴────────┴──┘  │
│ 상태: ingest 완료 — llama2-paper.md → 페이지 3개 생성/갱신          │
└──────────────────────────────────────────────────────────────────┘
```

- 트리 상단: 워크스페이스 전환 콤보박스
- 트리 하단: `health-report.md` / `lint-report.md` 바로가기 (3-4)
- 우측 하단 "그래프" 버튼: OS 기본 브라우저로 `graph.html` 열기 (3-1과 동일 동작)
- 커맨드 실행 중 stdout/stderr는 기존 **로그 탭**으로 스트리밍 (별도 UI 없음)

---

## 5. 의존성 및 리스크

| 리스크 | 영향 | 완화책 |
|--------|------|--------|
| Python 미설치 환경 | B안 커맨드 실행 불가 | Phase 0 환경 체크 + 안내 다이얼로그. **A안(스킬 팩)은 Python 없이도 동작**하므로 최소 가치는 보장됨 |
| API 키 비용 | B안의 `lint`·`graph`(semantic pass)·`query`·`ingest`는 `ANTHROPIC_API_KEY` 종량제 과금 발생 (Claude Pro/Max 구독과 별개). A안(Claude Code 스킬)은 구독에 포함되어 추가 비용 없음 | 실행 전 비용 경고 다이얼로그, `health`는 무료임을 UI에 명시. 비용 부담 없는 A안을 기본 경로로 안내 |
| 업스트림 기본 모델이 retire됨 | llm-wiki-agent의 `LLM_MODEL` 기본값(`claude-3-5-sonnet-latest` 등)은 retire된 모델이라 API 호출 시 404 | 플러그인 설정 탭 기본값을 `claude-sonnet-4-6` / `claude-haiku-4-5`로 교체 (4.5 와이어프레임 반영됨) |
| litellm 등 pip 의존성 | 사용자 환경 오염 우려 | venv 생성 옵션 검토 (워크스페이스 하위 `.venv/`), 최소한 `pip install litellm` 안내 |
| 한글 경로·출력 인코딩 | Windows에서 Python 출력 깨짐 | `PYTHONIOENCODING=utf-8` 주입, ProcessBuilder 출력 스트림 UTF-8 고정 |
| 업스트림(llm-wiki-agent) 변경 추적 | 반입 파일이 구버전화 | 반입 시점·커밋 해시를 리소스 디렉토리의 `UPSTREAM.md`에 기록 |
| 오프라인 환경에서 그래프 열람 불가 | `graph.html`이 vis-network를 unpkg CDN에서 로드 | 그래프는 OS 기본 브라우저로 열므로 일반 환경은 문제없음. 오프라인 지원이 필요해지면 vis-network.min.js를 로컬 번들하고 HTML 생성 시 경로 치환 검토 |

---

## 6. 우선순위 및 로드맵 요약

| 단계 | 내용 | 규모 | 가치 | 선행 조건 |
|------|------|------|------|----------|
| Phase 0 | 소스 반입 + Python 환경 체크 | 소 | — | 없음 |
| Phase 1 | 스킬 팩 통합 (A안) | 소 | 높음 | Phase 0 |
| Phase 2 | 플러그인 커맨드 실행 (B안) | 중 | 높음 | Phase 0 |
| Phase 3 | 위키 뷰어 UI | 중~대 | 중 | Phase 2 |
| Phase 4 | 배포·문서화 | 소 | — | Phase 1~3 |

**권장 진행 순서**: Phase 0 → 1 (빠른 가치 확보) → 2 → 3 → 4.
Phase 1까지만 진행해도 "llm_manager로 위키 에이전트 스킬을 배포한다"는 핵심 가치가 성립하며,
Phase 2~3은 독립적으로 중단·연기 가능하다.

## 7. 차기 과제 (다음 단계)

Phase 1~3 완료 후의 확장 계획은 별도 문서로 관리한다.

- **[llm-wiki-agent-차기과제-vector-db-mcp.md](../llm-wiki-agent-차기과제-vector-db-mcp.md)**
  - Phase 5: Vector DB 통합 — bgem3-embedding 서비스 팩 재활용, SQLite+sqlite-vec 색인, 시맨틱 검색
  - Phase 6: wiki-mcp 서버 — 위키를 MCP 도구로 노출 (`wiki_search`·`wiki_get_page` 등), service-pack(`wiki-mcp.yml`)으로 등록해 외부 에이전트가 위키 지식을 활용
