# LLM Wiki Agent 차기 과제 — Vector DB · MCP 통합

> 작성일: 2026-06-12
> 선행 문서: [llm-wiki-agent-통합계획.md](done/llm-wiki-agent-통합계획.md) (Phase 0~4 — 완료, done/ 보관)
> 상태: **차기 과제 (다음 단계)** — 본 계획의 Phase 1~3 완료 후 착수

---

## 1. 배경과 목표

통합계획 Phase 0~4가 완료되면 llm_manager 안에서 위키를 구축·질의·열람할 수 있다.
다만 두 가지 한계가 남는다.

1. **검색 한계** — `query.py`는 `wiki/index.md` 기반으로 관련 페이지를 고르므로
   키워드·카탈로그 수준의 탐색이다. 위키가 수백 페이지로 커지면 의미 기반(semantic)
   검색 없이는 관련 페이지 선별의 정확도와 비용이 모두 나빠진다.
2. **고립된 지식** — 위키는 llm_manager와 해당 워크스페이스를 연 에이전트만 접근
   가능하다. 다른 프로젝트에서 작업 중인 Claude Code/Cursor 등이 위키 지식을
   도구로 활용할 수 없다.

이를 해결하는 두 확장이 이 문서의 범위다.

| 확장 | 해결하는 문제 | 핵심 산출물 |
|------|--------------|------------|
| **Phase 5: Vector DB** | 의미 기반 위키 검색 | 위키 페이지 임베딩 색인 + 시맨틱 검색 |
| **Phase 6: MCP 서버** | 외부 에이전트의 위키 접근 | `wiki-mcp` 서버 (service-pack으로 등록) |

### llm_manager의 기존 자산 재활용

이 확장은 처음부터 만드는 것이 아니라 **이미 저장소에 있는 부품을 조립**하는 작업이다.

| 기존 자산 | 재활용 방법 |
|-----------|------------|
| `service-packs/bgem3-embedding.yml` | BGE-M3 임베딩 서비스를 그대로 임베딩 생성기로 사용 (CUDA 자동 감지 포함) |
| `service-packs/sql-gen-mcp.yml` | MCP 서버를 service-pack으로 정의·구동하는 선례 — `wiki-mcp.yml`의 템플릿 |
| HikariCP 5.1.0 + sqlite-jdbc 3.45.2.0 | `LlmSkillLibraryRepository`에서 이미 사용 중 — 벡터 색인 저장소(SQLite)에 동일 스택 적용 가능 |
| `ServiceManager` / `HealthMonitor` / `LogService` | MCP 서버·임베딩 서비스의 시작·중지·헬스체크·로그를 기존 인프라로 관리 |

> 통합계획에서 service-packs를 "단발성 실행이라 부적합"(☆)으로 분류했지만,
> **MCP 서버와 임베딩 서비스는 장기 실행 프로세스이므로 service-pack이 정확히
> 맞는 통합 지점이다.** 이 차기 과제에서 service-pack 경로가 처음으로 활용된다.

---

## 2. 목표 아키텍처

```
┌────────────────────────────────────────────────────────────────────┐
│ llm_manager                                                        │
│                                                                    │
│  [서비스 관리]                          [위키 워크스페이스]            │
│  ┌─────────────────────┐               ┌──────────────────────┐    │
│  │ bgem3-embedding      │◀── 임베딩 ────│ raw/  wiki/  graph/  │    │
│  │ (service-pack, 기존)  │    요청       │ .llm-manager/        │    │
│  └─────────┬───────────┘               │   └ vector-index.db  │    │
│            │ 벡터                       └──────────┬───────────┘    │
│            ▼                                      │                │
│  ┌─────────────────────┐                          │                │
│  │ vector-index.db      │◀── 색인 동기화 ───────────┘                │
│  │ (SQLite + sqlite-vec)│      (ingest 후 자동)                     │
│  └─────────┬───────────┘                                           │
│            │ 시맨틱 검색                                             │
│            ▼                                                       │
│  ┌─────────────────────┐      MCP (stdio/HTTP)   ┌──────────────┐  │
│  │ wiki-mcp 서버         │◀────────────────────────│ Claude Code  │  │
│  │ (service-pack, 신규)  │                         │ Cursor 등     │  │
│  └─────────────────────┘                         │ 외부 에이전트  │  │
│                                                  └──────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

- 벡터 색인은 워크스페이스 하위 `.llm-manager/vector-index.db`에 둔다 —
  위키 데이터(`wiki/`)와 분리하되 워크스페이스와 함께 이동 가능.
- `wiki-mcp` 서버가 시맨틱 검색·페이지 조회를 MCP 도구로 노출하면, 외부
  에이전트는 위키를 RAG 소스처럼 사용한다.

---

## 3. Phase 5 — Vector DB 통합

**목표**: 위키 페이지를 임베딩해 색인하고, 질의·브라우저에서 의미 기반 검색을 제공한다.

### 5-1. 벡터 저장소 선정

| 후보 | 장점 | 단점 | 판단 |
|------|------|------|------|
| **SQLite + sqlite-vec** | 단일 파일·무서버·기존 sqlite-jdbc 스택 재사용. HNSW 인덱스로 규모 무관 ~1ms 검색 | 네이티브 바이너리 배포 필요 → service-pack 설치로 해소 | ★ **권장** |
| SQLite + Java 코사인 | 네이티브 바이너리 불필요 | 25,000 청크 기준 쿼리당 10~50ms, 메모리 100MB+ | 목표 규모 초과 |
| Chroma / Qdrant | 고기능 (필터링·하이브리드 검색) | 별도 서버 프로세스·의존성 증가 | 위키 규모 대비 과함 |
| graph.json 내 임베딩 직렬화 | 의존성 0 | 전량 메모리 로드·검색 직접 구현 | 비권장 |

**sqlite-vec 채택 근거 (규모 분석)**

목표 문서 수 5,000개 이상(UI + 서비스 코드 기준)을 가정하면:

```
5,000 페이지 × 평균 5청크 = 25,000 청크
25,000 × 1,024차원(BGE-M3) × 4 bytes = ~100MB (벡터만)
Java 선형 탐색: 쿼리당 10~50ms — 위키 질의가 내부 복수 검색 시 체감
sqlite-vec HNSW: 규모 무관 ~1ms
```

네이티브 바이너리 배포 부담은 service-pack 설치 템플릿(6-3 참조)으로 완전히 자동화한다.

### 5-2. 색인 파이프라인

```
wiki/**/*.md 변경 감지 (ingest 완료 시 또는 수동 "재색인")
    ↓
페이지 → 청크 분할 (섹션 단위, frontmatter 메타데이터 유지)
    ↓
bgem3-embedding 서비스 호출 (HTTP) → 벡터
    ↓
vector-index.db upsert  (page_path, chunk_id, type, tags, vector, content_hash)
```

| 작업 | 상세 |
|------|------|
| 5-2-1. 색인 스키마 | `chunks(page_path, chunk_no, type, tags, content, content_hash, embedding BLOB)`. `content_hash`로 변경된 청크만 재임베딩 (graph.json의 SHA256 캐시 방식과 동일한 발상) |
| 5-2-2. 동기화 트리거 | `wiki.ingest` 커맨드 완료 후 자동 색인 + 플러그인 메뉴에 "재색인(전체)" 항목. 임베딩 서비스 미기동 시 색인 건너뛰고 안내 |
| 5-2-3. 임베딩 클라이언트 | bgem3-embedding 서비스의 HTTP 엔드포인트 호출 Java 클라이언트. 서비스가 STOPPED면 `ServiceManager`로 기동 제안 |
| 5-2-4. 검색 API | 내부 Java API: `WikiVectorSearch.search(query, topK, typeFilter)` — 질의 임베딩 → 코사인 유사도 top-K → 페이지 경로 반환 |

### 5-3. UI 연동

| 작업 | 상세 |
|------|------|
| 위키 브라우저 검색창 | Phase 3의 위키 탭 트리 상단에 검색 입력 추가 — 시맨틱 검색 결과(유사도순)를 트리 대신 표시 |
| Query 세션 연동 | 4.4 질의 UI에서 `query.py` 실행 전 시맨틱 검색으로 관련 페이지를 선별해 `--pages` 인자로 전달(업스트림 옵션 확인 필요 — 없으면 패치 또는 프롬프트에 경로 주입). index.md 스캔 대비 정확도·토큰 비용 개선 |
| 유사 페이지 패널 | 페이지 열람 시 "비슷한 페이지" 사이드 패널 (벡터 유사도 기반) |

**검증**: 100페이지 이상 위키에서 키워드가 일치하지 않는 의미 질의
("보상 모델 크기 논쟁" → RLHF 관련 페이지)가 top-5 안에 들어오는지 확인.

**예상 규모**: 중 (색인 파이프라인 + 검색 API + UI 검색창).

---

## 4. Phase 6 — MCP 서버 통합

**목표**: 위키를 MCP 도구로 노출해 Claude Code·Cursor 등 외부 에이전트가
어떤 프로젝트에서든 위키 지식을 조회·갱신할 수 있게 한다.

### 6-1. 서버 형태

`sql-gen-mcp.yml` 선례를 따라 **service-pack으로 등록되는 독립 MCP 서버 프로세스**로
구현한다. llm_manager의 서비스 목록에서 시작·중지·로그 모니터링이 그대로 동작한다.

| 결정 사항 | 권장 | 근거 |
|-----------|------|------|
| 구현 언어 | Python (FastMCP) 또는 Node | 업스트림 tools/*.py 재사용성 → Python 우선 검토 |
| 전송 방식 | stdio (로컬 에이전트용) + HTTP(Streamable) 선택 | Claude Code 로컬 등록은 stdio면 충분, 원격 공유 시 HTTP |
| 워크스페이스 지정 | 서버 기동 인자 `--workspace <dir>` | service-pack argSpec으로 노출 |

### 6-2. 노출할 MCP 도구

| 도구 | 입력 | 동작 | 비고 |
|------|------|------|------|
| `wiki_search` | query, topK, type? | Phase 5 벡터 색인 시맨틱 검색 → 페이지 요약 목록 | 색인 없으면 index.md 키워드 검색 폴백 |
| `wiki_get_page` | path 또는 title | 페이지 본문(마크다운) 반환 | |
| `wiki_overview` | — | `wiki/overview.md` + 통계(페이지 수·최근 갱신) | 에이전트의 첫 컨텍스트 파악용 |
| `wiki_query` | question | `wiki_search` 결과 청크를 질문 컨텍스트로 반환 | MCP 서버 내부 LLM 호출 없음 |
| `wiki_ingest` | file_path 또는 content | 문서 수집 실행 | **쓰기 도구** — 기본 비활성, argSpec으로 opt-in |
| `wiki_list_contradictions` | — | Contradictions 섹션이 있는 페이지 수집 | lint 결과 활용 |
| `wiki_health` | save?, as_json? | 빈/스텁 파일, index.md 동기화, log.md ingest 누락 점검 | deterministic, LLM/API 호출 없음 |
| `wiki_lint` | save?, include_semantic?, as_json? | 고아 페이지, 깨진 위키링크, 누락 엔티티 후보, sparse 페이지, Contradictions, graph.json 품질 점검 | deterministic. include_semantic은 안내만 포함 |

> 쓰기 도구(`wiki_ingest`)는 외부 에이전트가 위키를 오염시킬 수 있으므로
> 기본 비활성화하고, service-pack argSpec(`--enable-write` BOOLEAN)으로만 켠다.

### 6-3. service-pack 정의 (`service-packs/wiki-mcp.yml` 초안)

`bgem3-embedding.yml`이 CUDA 자동 감지를 `groovyScript`로 처리하듯,
`wiki-mcp.yml`은 설치 시 sqlite-vec 바이너리 경로와 DB 경로를 자동 주입한다.
사용자는 워크스페이스 경로만 지정하면 된다.

**설치 흐름**

```
사용자: 기본 제공 서비스 → wiki-mcp 선택
    ↓
BuiltinServiceSetupDialog
  - --workspace 경로 입력
  - --transport 선택 (stdio / http)
    ↓
InstallationService.install()
  - vec0.dll / vec0.so 를 ~/llm-services/native/ 로 복사
  - vector-index.db 스키마 초기화
    ↓
groovyScript 실행
  - OS 감지 → --vec-lib 경로 자동 주입
  - --db-path 를 workspace/.llm-manager/vector-index.db 로 자동 설정
  - bgem3-embedding 서비스 미기동 시 안내
```

**배포 패키지 구조**

```
app/
├── lib/           ← JAR들
├── native/
│   ├── vec0.dll   ← Windows (jpackage doLast로 복사)
│   └── vec0.so    ← Linux (향후)
└── service-packs/
    └── wiki-mcp.yml
```

`build.gradle`의 `copyJavaExeToRuntime()`과 동일한 방식으로 `doLast` 태스크에서
플랫폼별 바이너리를 `app/native/`로 복사한다.

**wiki-mcp.yml**

```yaml
name: Wiki MCP Server
id: wiki-mcp
description: LLM Wiki를 MCP 도구로 노출하는 서버 (시맨틱 검색·페이지 조회·질의)
runtimeType: python
startCommand: python -m wiki_mcp_server
argSpecs:
  - flag: --workspace
    label: 위키 워크스페이스
    type: DIRECTORY
    required: true
  - flag: --transport
    label: 전송 방식
    type: SELECT
    options: [stdio, http]
    default: stdio
  - flag: --port
    label: HTTP 포트
    type: NUMBER
    default: 8765
  - flag: --enable-write
    label: 쓰기 도구(wiki_ingest) 허용
    type: BOOLEAN
    default: false
  - flag: --vec-lib
    label: sqlite-vec 라이브러리 경로
    type: STRING
    hidden: true   # groovyScript가 자동 주입 — 사용자 노출 안 함
  - flag: --db-path
    label: 벡터 색인 DB 경로
    type: STRING
    hidden: true   # groovyScript가 자동 주입

groovyScript: |
  def nativeDir = new File(userHome + "/llm-services/native")
  def vecLib = os == "windows"
      ? new File(nativeDir, "vec0.dll")
      : new File(nativeDir, "vec0.so")

  service.setArgValue("--vec-lib", vecLib.absolutePath)
  service.setArgValue("--db-path",
      service.getArgValue("--workspace") +
      "/.llm-manager/vector-index.db")
```

### 6-4. 에이전트 등록 연동

| 작업 | 상세 |
|------|------|
| Claude Code 등록 도우미 | 플러그인 메뉴 "MCP 등록 안내" — `claude mcp add wiki -- python -m wiki_mcp_server --workspace <dir>` 명령을 클립보드에 복사해 주는 다이얼로그 |
| 스킬 팩 갱신 | Phase 1의 wiki-agent 스킬 팩에 `.mcp.json` 항목 추가 옵션 — 설치 대상 프로젝트가 자동으로 wiki-mcp를 인지 |
| 헬스체크 | `HealthMonitor`의 HTTP 체크를 http 전송 모드에 연결 (stdio 모드는 프로세스 생존만 확인) |

**검증**: 별도 프로젝트에서 Claude Code로 `wiki_search` → `wiki_get_page` 흐름이
동작하고, llm_manager 로그 탭에서 MCP 요청 로그가 보이는지 확인.

**예상 규모**: 중~대 (MCP 서버 신규 구현 + service-pack 정의 + 등록 도우미).

---

## 5. 단계 간 의존성

```
통합계획 Phase 0 (반입·환경)
    └─▶ Phase 1 (스킬 팩) ─────────────┐
    └─▶ Phase 2 (플러그인 커맨드)        │
            └─▶ Phase 3 (위키 뷰어)     │
            └─▶ Phase 5 (Vector DB) ◀──┘   ← ingest 파이프라인(2)과 워크스페이스 개념 필요
                    └─▶ Phase 6 (MCP 서버)  ← wiki_search가 5의 색인에 의존
```

- **Phase 5는 Phase 2 완료가 전제** — 색인 동기화 트리거가 ingest 커맨드에 걸린다.
- **Phase 6은 Phase 5 없이도 축소판 가능** (키워드 검색 폴백 + 페이지 조회만) —
  벡터 검색이 지연될 경우 `wiki_get_page`/`wiki_overview`만으로 선출시 가능.

## 6. 리스크

| 리스크 | 영향 | 완화책 |
|--------|------|--------|
| bgem3-embedding 서비스 의존 | 임베딩 서비스 미설치·미기동 시 색인 불가 | 색인은 항상 선택 기능 — 미기동 시 키워드 검색 폴백. UI에서 서비스 기동 유도 |
| sqlite-vec 네이티브 바이너리 | OS별 확장 로딩 이슈 가능 | service-pack 설치 시 `InstallationService`가 `~/llm-services/native/`에 자동 복사, `groovyScript`가 경로 주입. 도입 전 Windows 스파이크 테스트 필수. 로딩 실패 시 Java 코사인 검색으로 자동 폴백(5,000페이지 미만 운영 시 허용 범위) |
| MCP 쓰기 도구 오남용 | 외부 에이전트가 위키 오염 | `wiki_ingest` 기본 비활성 + opt-in argSpec. 모든 쓰기는 `wiki/log.md`에 기록되므로 추적 가능 |
| 색인-위키 불일치 | 에이전트가 위키를 직접 수정하면(스킬 모드) 색인이 낡음 | `content_hash` 비교 기반 증분 재색인 + 위키 탭에 "색인 상태" 표시(낡은 청크 수) |
| query.py 페이지 주입 옵션 부재 | 5-3의 Query 연동이 업스트림 수정 필요할 수 있음 | 옵션 없으면 프롬프트 주입 방식으로 우회, 업스트림 PR 검토 |

## 7. 로드맵 요약

| 단계 | 내용 | 규모 | 선행 조건 |
|------|------|------|----------|
| Phase 5-1~2 | 벡터 저장소 + 색인 파이프라인 | 중 | 통합계획 Phase 2 |
| Phase 5-3 | 시맨틱 검색 UI 연동 | 소~중 | Phase 5-2, (위키 탭은 Phase 3) |
| Phase 6 | wiki-mcp 서버 + service-pack + 등록 도우미 | 중~대 | Phase 5 (축소판은 Phase 2만으로 가능) |

**권장 착수 시점**: 통합계획 Phase 1~2 완료 후. Phase 3(뷰어)과 Phase 5(벡터)는
서로 독립이므로 병행 가능하다.
