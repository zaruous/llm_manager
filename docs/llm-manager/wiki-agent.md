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
| `wiki_ingest` | `--enable-write` 활성화 시 content/file_path를 raw에 불변 저장하고 Cursor Agent로 위키 갱신 |

`wiki_ingest`는 기본 비활성이다. 서비스 설정에서 `enable-write`를 켜고
`CURSOR_API_KEY` 환경변수를 설정해야 한다. 입력은 서버의 `--workspace`로 지정된
워크스페이스에만 저장되며, 원본은 `raw/mcp-ingest/<날짜>/`에 보존된다.
파일 입력은 Wiki Agent 플러그인의 `ingest.include`/`ingest.exclude` 규칙을
따르며, content 크기 제한과 원본 저장 위치도 각각 `ingest.contentMaxBytes`,
`ingest.stagingDirectory`에서 가져온다. 서버 내부에 별도 ingest 기본값을 두지
않으며, 설정 파일이 없거나 형식이 잘못된 경우 수집을 중단하고 설정 오류를 반환한다.
Cursor 모델과 제한 시간은 별도 값을 지정하지 않으면 각각 Cursor Agent
Runner의 기본 모델과 Wiki Agent의 `timeoutMinutes` 설정을 사용한다.
Cursor SDK는 기존 글로벌 설치를 사용하며 서비스 설치 과정에서 재설치하지 않는다.

### 크기 제한이 적용되는 위치

`ingest.contentMaxBytes`의 기본값은 `10485760`(10 MiB)이며 MCP 호출자가
`wiki_ingest(content=...)`로 원문을 JSON 요청에 직접 포함할 때만 적용된다.
UTF-8 인코딩 후 바이트 수를 검사해 과도한 요청 본문·메모리 사용을 막는 수신 단계
안전장치다. `file_path` 입력, raw 원본 파일 전체 크기, Cursor의 문서 요약,
Wiki 임베딩 청크 크기에는 적용되지 않는다.

Cursor 수집과 임베딩은 별도 기준을 사용한다.

| 단계 | 현재 기준 | 목적 |
|------|----------|------|
| MCP `content` 직접 입력 | 10 MiB | 과도한 MCP/JSON 요청 차단 |
| 작은 파일 Cursor 수집 | 최대 5개, 합계 512 KiB | 한 번의 에이전트 작업량 제한 |
| 큰 텍스트 Cursor 수집 | 200 KiB 초과 시 40,000자 목표, 60,000자 상한 섹션 | 섹션별 노트 후 최종 병합 |
| 큰 PDF Cursor 수집 | 4 MiB 초과 시 5페이지 단위 | 이미지·추출 텍스트 판독 후 병합 |
| Wiki 임베딩 | 목표 1,500자, 최대 2,500자, 중첩 200자 | 검색용 의미 청크 생성 |

현재 원본 파일 전체에 대한 절대 거부 상한은 없다. 큰 텍스트와 PDF는 분할 처리하며,
기타 대형 바이너리는 Cursor에 변환→노트→병합 단계를 지시한다.

## 설정 (설정 > Wiki Agent / Cursor)

| 항목 | 설명 |
|------|------|
| CURSOR_API_KEY (Wiki Agent 탭) | 시스템 환경변수로 설정 (secret은 저장되지 않음) |
| 기본 워크스페이스 | 선택 시 자동 영속화 — 모든 위키 다이얼로그가 마지막 워크스페이스를 복원 |
| 실행 타임아웃 (분) | 비우면 무제한(기본). 초과 시 자식 프로세스까지 강제 종료 |
| Default Model (Cursor 탭) | 위임 실행에 사용할 Cursor 모델 |
| 응답 언어 (Cursor 탭) | 한국어(기본)/English — 사고 과정 포함 출력 언어 지시문 주입 |
| 목표/최대/중첩 청크 문자 수 | Wiki Markdown을 임베딩 직전에 나누는 기준 |
| 선택 디렉토리 색인 상태 확인 | 현재 전처리 청크 해시와 SQLite 해시를 비교해 최신·변경됨·미색인·빈 문서·잔여 색인을 파일별 표시 |

색인 상태 조회는 임베딩 서버를 호출하지 않는다. `현재 청크`는 현재 설정으로 다시
전처리했을 때 생성되는 청크 수이고, `색인 청크`는 SQLite에 저장된 수다.
두 청크 해시 맵이 완전히 같을 때만 `최신`으로 표시한다. Wiki 파일이 변경되었거나
청킹 설정을 바꾸면 `변경됨`, 아직 DB에 없으면 `미색인`, 삭제된 파일의 DB 청크가
남아 있으면 `잔여 색인`으로 표시한다.

## LLM 대화 수집 스킬

Wiki Agent 스킬 팩에는 `wiki-ingest-conversation`이 포함된다.

- Codex: `.agents/skills/wiki-ingest-conversation/`
- Cursor: `.cursor/skills/wiki-ingest-conversation/`
- Claude Code: `.claude/skills/wiki-ingest-conversation/`

사용자가 “현재 대화를 Wiki에 수집해”, “이 설계 논의를 지식으로 저장해”처럼
명시적으로 요청했을 때만 실행한다. 스킬은 현재 대화에서 사용자·assistant의 관련
메시지만 선별하고 시스템 지시, 내부 추론, 불필요한 도구 출력과 비밀값을 제거한
Markdown을 만든다. 이후 `wiki-mcp`의 `wiki_ingest(content=...)`를 한 번 호출해
`raw/mcp-ingest/`에 원본을 보존하고 Cursor의 기존 Wiki ingest 워크플로우를 실행한다.

Codex용 `agents/openai.yaml`은 `allow_implicit_invocation: false`로 설정되어 있어
일반 대화를 자동 수집하지 않는다. `$wiki-ingest-conversation`으로 명시 호출하거나
대화 저장 의도가 분명한 요청에서 사용한다. MCP 서비스는 `enable-write=true`와
`CURSOR_API_KEY`가 필요하다. MCP 응답이 색인 성공을 명시하지 않으면 벡터 색인은
별도 단계로 취급한다.

## 구현 메모 (유지보수용)

- **핵심 클래스**: `PluginCommandExecutor.runWikiTool()`(검증·tools 동기화 후
  `runWikiViaCursorAgent()`로 위임), `WikiIngestDialog`, `WikiQueryDialog`,
  `WikiBrowserDialog`, `WikiWorkspaceInitializer`
- **서비스별 워크스페이스**: wiki-mcp 서비스마다 독립된 워크스페이스를 가질 수 있다.
  각 다이얼로그는 `contribution.linkedServiceId` → `argValues["workspace"]` → 전역 `wiki.defaultCwd`
  순서로 워크스페이스를 결정한다. 상세 내용은 [wiki-per-service-workspace](../done/wiki-per-service-workspace.md) 참조
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
