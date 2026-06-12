# LLM Wiki Agent 사용 가이드

> 관련 문서: [통합 계획·구현 체크리스트](../llm-wiki-agent-통합계획.md) ·
> [차기 과제 (Vector DB·MCP)](../llm-wiki-agent-차기과제-vector-db-mcp.md)
> 앱 내 도움말(도움말 > LLM Wiki Agent)과 동일한 내용의 저장소 보관용 문서.

문서를 수집하면 AI가 분석해 상호 연결된 지식 위키를 자동 구축·유지하는 기능이다.
업스트림 [llm-wiki-agent](https://github.com/SamurAIGPT/llm-wiki-agent)(MIT)를
스킬 팩 + 플러그인 두 갈래로 통합했다.

## 아키텍처 요약

```
┌─ llm_manager ──────────────────────────────────────────────┐
│  플러그인 메뉴 (wiki.*)            위키 워크스페이스           │
│  ├─ Ingest / Query / Health      ├─ raw/    (원본, 불변)    │
│  │   / Lint / Graph              ├─ wiki/   (위키 페이지)    │
│  ├─ 그래프 열기 → OS 브라우저      ├─ graph/  (시각화 산출물)  │
│  └─ 위키 브라우저 (트리+렌더링)    └─ tools/  (실행 시 동기화) │
│                                                            │
│  실행 에이전트 (설정에서 선택)                                 │
│  ├─ claude: tools/*.py + litellm → Claude API 직접 호출     │
│  └─ cursor: Cursor 사이드카에 자연어 위임 (AGENTS.md 기반)    │
└────────────────────────────────────────────────────────────┘
```

## 빠른 시작

1. **워크스페이스 준비** — 빈 폴더를 만들고, 플러그인 > LLM Wiki Agent >
   문서 수집에서 그 폴더를 지정하면 골격 초기화를 제안한다 (수락 시 자동 생성).
2. **에이전트 선택** — 설정 > Wiki Agent > 실행 에이전트:
   - `claude`(기본): Python 3.8+, `pip install litellm`, `ANTHROPIC_API_KEY` 필요. 종량제 과금.
   - `cursor`: Node.js 22+, 글로벌 `@cursor/sdk`, `CURSOR_API_KEY` 필요. Python 불필요.
3. **문서 수집** — 파일 멀티 선택/폴더 추가 → raw/분류로 복사 → 위키 페이지 자동 생성.
4. **질의** — CLI 세션형 창에서 질문. 이력은 워크스페이스 `.llm-manager/query-history.json`에
   영속화. 질의는 매번 독립 실행(stateless)이며 이전 문답은 컨텍스트로 전달되지 않는다.
5. **탐색** — 위키 브라우저(트리 + 마크다운 렌더링, `[[위키링크]]`·상대 .md 링크 이동),
   그래프 빌드 후 "그래프 열기"로 OS 브라우저에서 vis.js 시각화.

## 메뉴별 LLM 호출 여부

| 메뉴 | LLM 호출 |
|------|---------|
| 문서 수집 (Ingest) | 발생 (파일 수 비례) |
| 위키 질의 (Query) | 발생 |
| 구조 검사 (Health) | **없음 (무료)** |
| 품질 검사 (Lint) | 발생 |
| 그래프 빌드 (Graph) | 발생 (semantic pass) |
| 그래프 열기 / 위키 브라우저 | 없음 |

## 설정 (설정 > Wiki Agent / Cursor)

| 항목 | 설명 |
|------|------|
| 실행 에이전트 | `claude` / `cursor` 드롭다운 |
| LLM_MODEL / LLM_MODEL_FAST | claude 에이전트용 모델 (기본 `claude-sonnet-4-6` / `claude-haiku-4-5`) |
| 기본 워크스페이스 | 선택 시 자동 영속화 — 모든 위키 다이얼로그가 마지막 워크스페이스를 복원 |
| 실행 타임아웃 (분) | 비우면 무제한(기본). 초과 시 자식 프로세스까지 강제 종료 |
| 응답 언어 (Cursor 탭) | 한국어(기본)/English — 사고 과정 포함 출력 언어 지시문 주입 |

## 구현 메모 (유지보수용)

- **핵심 클래스**: `PluginCommandExecutor.runWikiTool()`(claude 경로) /
  `runWikiViaCursorAgent()`(cursor 위임), `WikiIngestDialog`, `WikiQueryDialog`,
  `WikiBrowserDialog`, `WikiWorkspaceInitializer`
- **업스트림 제약 대응**: 도구의 스크립트 위치 기준 경로 해석 → 실행 전 워크스페이스로
  tools/ 동기화. `query.py --save`의 인터랙티브 프롬프트 → 타임스탬프 슬러그 자동 전달.
  retire된 기본 모델 → 최신 모델 env 폴백 주입
- **중지/취소**: `PluginCommandExecutor.cancel(commandId)` → `destroyProcessTree()`
- **사이드카 출력**: status 노이즈 억제, 응답·사고 텍스트 스트리밍, think ↔ 응답 전환 시
  `------` 구분자
- 자세한 이력은 [통합 계획 문서](../llm-wiki-agent-통합계획.md)의 구현 체크리스트 참조

## 트러블슈팅

| 증상 | 해결 |
|------|------|
| `No module named litellm` | `pip install litellm` |
| `위키 워크스페이스가 아닙니다` | wiki/index.md를 가진 **루트 폴더**를 선택해야 함 (wiki/ 하위 폴더 아님). 빈 폴더면 수집 다이얼로그가 초기화 제안 |
| 글로벌 @cursor/sdk를 못 찾음 | APPDATA 환경변수 문제 — 수정됨(화이트리스트 포함). 앱 재시작 |
| 사이드카 타임아웃 | 기본 무제한. 설정의 '실행 타임아웃'이 설정돼 있다면 비우기 |
| 설정 탭/메뉴가 안 보임 | plugin.json은 앱 시작 시 로드 — 앱 재시작 또는 플러그인 관리 > 새로고침 |
