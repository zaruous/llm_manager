# LLM Wiki Agent 사용 가이드

> 관련 문서: [통합 계획·구현 체크리스트](../done/llm-wiki-agent-통합계획.md) ·
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
│  실행 경로 (cursor 전용)                                      │
│  └─ Cursor 사이드카에 자연어 위임 (AGENTS.md 워크플로우 기반)   │
└────────────────────────────────────────────────────────────┘
```

> v0.2.0부터 Python(litellm) + `ANTHROPIC_API_KEY` 직접 실행 경로를 제거하고
> **Cursor Agent 위임 단일 경로**로 축소했다.

## 빠른 시작

1. **워크스페이스 준비** — 빈 폴더를 만들고, 플러그인 > LLM Wiki Agent >
   문서 수집에서 그 폴더를 지정하면 골격 초기화를 제안한다 (수락 시 자동 생성).
2. **사전 준비** — Node.js 22+, 글로벌 `@cursor/sdk`, `CURSOR_API_KEY` 환경변수,
   cursor-agent-runner 플러그인. Python 불필요.
3. **문서 수집** — 파일 멀티 선택/폴더 추가 → raw/분류로 복사 → `WikiIngestPlanner`의
   작업 계획대로 나눠 수집(프로그레스바 표시, 시작 전 실행 횟수 확인).
   작은 파일은 5개·512KB 예산 배치, 큰 텍스트(200KB↑)는 섹션 노트→병합 시퀀스,
   큰 PDF(4MB↑)는 `extract_pages.py`(pymupdf) 전처리로 페이지 이미지+텍스트를 뽑아
   5페이지씩 이미지 판독→병합 시퀀스(전처리 실패 시 에이전트 내부 단계 처리 폴백),
   그 외 큰 바이너리는 에이전트 내부 단계(변환→노트→병합) 단독 작업.
   raw는 불변(덮어쓰지 않음, 동일 내용 재사용·충돌 시 개명), 폴더는 지원 확장자만
   재귀 수집(숨김·도트 디렉토리 제외), 기수집 파일은 스킵.
4. **질의** — CLI 세션형 창에서 질문. 이력은 워크스페이스 `.llm-manager/query-history.json`에
   영속화. 질의는 매번 독립 실행(stateless)이며 이전 문답은 컨텍스트로 전달되지 않는다.
5. **탐색** — 위키 브라우저(트리 + 마크다운 렌더링, `[[위키링크]]`·상대 .md 링크 이동,
   키워드 검색 — 제목 일치 ★ 우선·본문 일치 순, 비우면 트리 복원),
   그래프 빌드 후 "그래프 열기"로 OS 브라우저에서 vis.js 시각화.

## 메뉴별 Cursor Agent 호출 여부

| 메뉴 | Cursor Agent 호출 |
|------|---------|
| 문서 수집 (Ingest) | 발생 (파일 수 비례) |
| 위키 질의 (Query) | 발생 |
| 구조 검사 (Health) | 발생 |
| 품질 검사 (Lint) | 발생 |
| 그래프 빌드 (Graph) | 발생 |
| 그래프 열기 / 위키 브라우저 | 없음 |

## Wiki MCP 서비스 도구

`wiki-mcp` 서비스를 설치·시작하면 외부 MCP 클라이언트에서 다음 도구를 사용할 수 있다.
조회/검사 도구는 기본적으로 LLM/API를 호출하지 않는다.

| 도구 | 용도 |
|------|------|
| `wiki_search` | 벡터 색인 검색, 색인/임베딩 실패 시 키워드 검색 폴백 |
| `wiki_get_page` | 위키 페이지 원문 조회 |
| `wiki_overview` | overview.md, index.md, 페이지 통계 조회 |
| `wiki_query` | 관련 청크를 질문 컨텍스트로 반환 |
| `wiki_list_contradictions` | Contradictions 섹션이 있는 페이지 목록 |
| `wiki_health` | 빈/스텁 파일, index.md 동기화, log.md ingest 누락 점검 |
| `wiki_lint` | 고아 페이지, 깨진 위키링크, 누락 엔티티 후보, sparse 페이지, graph.json 품질 점검 |

## 설정 (설정 > Wiki Agent / Cursor)

| 항목 | 설명 |
|------|------|
| CURSOR_API_KEY (Wiki Agent 탭) | 시스템 환경변수로 설정 (secret은 저장되지 않음) |
| 기본 워크스페이스 | 선택 시 자동 영속화 — 모든 위키 다이얼로그가 마지막 워크스페이스를 복원 |
| 실행 타임아웃 (분) | 비우면 무제한(기본). 초과 시 자식 프로세스까지 강제 종료 |
| Default Model (Cursor 탭) | 위임 실행에 사용할 Cursor 모델 |
| 응답 언어 (Cursor 탭) | 한국어(기본)/English — 사고 과정 포함 출력 언어 지시문 주입 |

## 구현 메모 (유지보수용)

- **핵심 클래스**: `PluginCommandExecutor.runWikiTool()`(검증·tools 동기화 후
  `runWikiViaCursorAgent()`로 위임), `WikiIngestDialog`, `WikiQueryDialog`,
  `WikiBrowserDialog`, `WikiWorkspaceInitializer`
- **tools/ 동기화**: 플러그인의 업스트림 Python 도구를 매 실행마다 워크스페이스로 복사 —
  그래프 빌드 시 에이전트가 `python tools/build_graph.py`를 활용할 수 있게 함
- **ingest 작업 계획 실행**: `WikiIngestPlanner.plan()`이 수집 대상을 에이전트 1회 실행
  단위 작업으로 분해(배치/section/pages/merge/large 모드 — 프롬프트는
  `PluginCommandExecutor.buildIngestPrompt()`), `WikiIngestDialog.runPlan()`이 순차 실행하며
  프로그레스바 갱신. 큰 텍스트는 `.llm-manager/ingest/<slug>/`에 섹션 스테이징 후
  노트 패스→병합 패스, 성공 시 스테이징 자동 정리. 실패 시 남은 작업 목록 출력,
  중지는 작업 경계에서도 동작. raw 복사는 `copyImmutable()`(덮어쓰기 금지,
  `Files.mismatch`로 동일 내용 재사용, 충돌 시 개명).
  에이전트 소유 레이어(wiki/graph/tools) 내부 파일은 수집 대상에서 제외
- **PDF 페이지 전처리**: `WikiPageExtractor`가 플러그인 `tools/extract_pages.py`(자체 추가,
  pymupdf 필요, LLM 호출 없음)를 런타임 실행 — 페이지별 텍스트 .md + 렌더 .png +
  manifest.json을 `.llm-manager/ingest/<slug>/pages/`에 생성. 페이지 판독 패스(pages 모드)는
  이미지를 일차 소스로 읽고 추출 텍스트를 보조 참조(텍스트 30자 미만 페이지는 image-only
  표시). pymupdf 미설치·실패 시 large 모드 폴백
- **중지/취소**: `PluginCommandExecutor.cancel(commandId)` → `destroyProcessTree()`
- **사이드카 출력**: status 노이즈 억제, 응답·사고 텍스트 스트리밍, think ↔ 응답 전환 시
  `------` 구분자
- 자세한 이력은 [통합 계획 문서](../done/llm-wiki-agent-통합계획.md)의 구현 체크리스트 참조

## 트러블슈팅

| 증상 | 해결 |
|------|------|
| `필수 환경변수가 없습니다: CURSOR_API_KEY` | 시스템 환경변수에 키 설정 후 앱 재시작 |
| `Cursor Agent Runner 플러그인이 없습니다` | cursor-agent-runner 플러그인 설치 |
| `위키 워크스페이스가 아닙니다` | wiki/index.md를 가진 **루트 폴더**를 선택해야 함 (wiki/ 하위 폴더 아님). 빈 폴더면 수집 다이얼로그가 초기화 제안 |
| 글로벌 @cursor/sdk를 못 찾음 | APPDATA 환경변수 문제 — 수정됨(화이트리스트 포함). 앱 재시작 |
| 사이드카 타임아웃 | 기본 무제한. 설정의 '실행 타임아웃'이 설정돼 있다면 비우기 |
| 설정 탭/메뉴가 안 보임 | plugin.json은 앱 시작 시 로드 — 앱 재시작 또는 플러그인 관리 > 새로고침 |
