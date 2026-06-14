# Wiki 문서 수집(Ingest) 처리 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                       WikiIngestDialog (FX UI)                       │
│  워크스페이스 선택 · 파일/폴더 추가 · 분류(raw 하위) · 수집 시작/중지  │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ 1. copyIntoRaw()
                            │    raw/<category>/ 로 복사 (불변 원본 저장소)
                            │    이미 워크스페이스 내부 파일은 패스
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       WikiIngestPlanner.plan()                       │
│  파일별 크기·확장자 분석 → IngestPlan(List<IngestTask>) 반환          │
│                                                                      │
│  ┌─────────────────┬───────────────────────┬──────────────────────┐ │
│  │  소형 파일       │   대형 텍스트 파일      │   대형 바이너리 파일  │ │
│  │ ≤512KB·≤5개     │   >200KB               │   >4MB               │ │
│  │  (md/txt/html/…)│   (md/txt/html/…)      │   (pdf/docx/…)       │ │
│  │                 │                        │                      │ │
│  │   배치 작업     │  PDF + PageExtractor?  │  PDF + 전처리 성공?   │ │
│  │   payload=      │    ↙ 예       ↘ 아니오  │    ↙ 예    ↘ 폴백    │ │
│  │   파일목록      │  페이지자산   섹션분할   │  페이지작업  단독작업 │ │
│  │   (개행구분)    │  5페이지씩   40K자씩    │  5페이지씩  payload= │ │
│  │                 │  판독 패스N  섹션 패스N │  판독 패스N  파일경로 │ │
│  │                 │       +           +    │         +            │ │
│  │                 │  병합 패스1  병합 패스1 │  병합 패스1          │ │
│  └─────────────────┴───────────────────────┴──────────────────────┘ │
│                                                                      │
│  WikiPageExtractor — PDF 전처리 (extract_pages.py, LLM 호출 없음)     │
│    python extract_pages.py <pdf> <outputDir>                         │
│    → pages/{01.md, 01.png, …} + manifest.json                       │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ IngestPlan 반환
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│           WikiIngestDialog.runPlan() — 순차 실행 루프                 │
│                                                                      │
│   for task in plan.tasks():                                          │
│     if stopRequested → 남은 작업 취소                                 │
│     updateProgress(progressBar, i/total, task.label)                 │
│     executeStreaming(pluginId, command, request, onOutput)  ──────┐  │
│     if !result.success → failedLabels 추가, continue              │  │
│     if task.stagingDir != null → deleteRecursively(staging)       │  │
└───────────────────────────────────────────────────────────────────┼──┘
                                                                    │
                                                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    PluginCommandExecutor                              │
│                                                                      │
│  executeStreaming()                                                   │
│    └→ command.id.startsWith("wiki.")                                 │
│         └→ runWikiTool()                                             │
│              ① workspace 존재 검증 (wiki/index.md 확인)               │
│              ② syncToolsIntoWorkspace()                              │
│                 plugin/tools/*.py → workspace/tools/                  │
│              └→ runWikiViaCursorAgent()                              │
│                   ③ ensureAgentsSchema() — AGENTS.md 자동 설치       │
│                   ④ buildWikiAgentPrompt()                           │
│                      ┌──────────────────────────────────────────┐   │
│                      │ ingestMode별 영어 프롬프트 조립            │   │
│                      │ batch  → "Follow AGENTS.md workflow.      │   │
│                      │           Ingest: <파일목록>"              │   │
│                      │ section→ "note-taking pass N/M for         │   │
│                      │           section file: <경로>"            │   │
│                      │ pages  → "page-reading pass pp.N-M        │   │
│                      │           image: primary source"           │   │
│                      │ merge  → "final merge pass,               │   │
│                      │           notes: <노트경로>"               │   │
│                      │ large  → "work in stages: convert→        │   │
│                      │           section-notes→merge"             │   │
│                      └──────────────────────────────────────────┘   │
│                   ⑤ languageDirective() 앞에 삽입 (응답 언어 설정)   │
│                   └→ runCursorSidecar()                              │
│                        payload Base64 인코딩 → node sidecar 실행    │
│                        activeProcesses[wiki.ingest] = process        │
│                        stdout 스트리밍 → onOutput 콜백               │
│                        cancel() → destroyProcessTree()               │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ node cursor-agent-sidecar.js <base64>
                                ▼
                 ┌──────────────────────────────┐
                 │   cursor-agent-sidecar.js     │
                 │   (Node.js, CWD=workspace)    │
                 │                              │
                 │   @cursor/sdk 로 에이전트 실행 │
                 │   ← AGENTS.md 워크플로우 기반 │
                 │   ← workspace/tools/*.py 활용 │
                 │                              │
                 │  stdout JSON lines:           │
                 │  {"type":"status","message"}  │
                 │  {"type":"text","message"}    │
                 │  {"type":"done","message"}    │
                 └──────────────────────────────┘
```

---

## 핵심 흐름 요약

| 단계 | 담당 | 역할 |
|------|------|------|
| **① 파일 수집** | `WikiIngestDialog` | raw/<분류>/ 로 불변 복사, 확장자 필터링 |
| **② 작업 분해** | `WikiIngestPlanner` | 파일 크기·타입 → IngestTask 배열 생성 |
| **③ PDF 전처리** | `WikiPageExtractor` | Python(PyMuPDF)으로 페이지 이미지+텍스트 분리 (LLM 없음) |
| **④ 순차 실행** | `WikiIngestDialog.runPlan()` | 작업 하나씩 실행, 진행률 갱신, 실패 무시 후 계속 |
| **⑤ 프롬프트 조립** | `PluginCommandExecutor` | ingestMode 별 영어 지시문 → Cursor Agent에 전달 |
| **⑥ 에이전트 실행** | `cursor-agent-sidecar.js` | AGENTS.md 워크플로우 + tools/*.py 활용 |

## 중지 경로

`stopBtn` → `cancel("wiki.ingest")` → `activeProcesses`에서 프로세스 찾아
`destroyProcessTree()` (자식까지 강제 종료), 다음 루프 경계에서
`stopRequested` 플래그로 남은 작업 취소.
