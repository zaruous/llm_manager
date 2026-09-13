# TODO - 옵시디언 링크 문서 직접 적재 최적화 (Fast Obsidian Import)

이미 옵시디언 형식의 상호 참조 링크가 완성되어 있는 마크다운 문서를 수집할 때, 비용이 크고 속도가 느린 LLM(Cursor Agent) 기반의 Ingest 단계를 생략하고 로컬 임베딩 서버를 통해 SQLite 벡터 DB에 다이렉트로 증분 적재하여 수집 속도를 극대화한다.

## 1. 배경
- 현재의 문서 수집(Ingest)은 `WikiIngestDialog.java`를 거쳐 `wiki.ingest` 커맨드를 호출하여 Cursor Agent(LLM)가 문서를 하나씩 분석하고, 관련 엔티티/개념 페이지를 새로 생성하거나 위키 링크를 능동적으로 이어주는 작업을 진행한다.
- 하지만 수집 대상 폴더의 문서들이 **이미 옵시디언 위키링크 `[[PageName]]` 구조를 온전히 갖추고 있다면**, LLM이 지식을 새롭게 가공하거나 링크를 능동적으로 창작해 줄 필요가 없다.
- 이 경우, 문서를 청킹하여 로컬 BGE-M3 임베딩 서버(`localhost:18080`)에만 전달한 후 SQLite DB에 적재하면 비용 0원에 수 초 내로 완료할 수 있다.

## 2. 구현 범위

### A. UI 개선 (WikiIngestDialog.java)
- **직접 적재 옵션 추가**: 문서수집 대화 상자에 `[ ] 옵시디언 링크 구조 보존 (LLM Ingest 건너뛰고 벡터 DB 직접 적재)` 체크박스를 추가한다.
- **자동 감지 힌트**: 사용자가 수집할 소스 디렉토리를 지정할 시, 내부 파일 중 임의로 3~5개를 빠르게 읽어 `[[...]]` 형태의 옵시디언 링크가 다수 포착되면 체크박스를 자동으로 활성화하고 우측에 `(권장)` 표시를 보여주는 UX 힌트를 제공한다.

### B. 동작 분기 (PluginCommandExecutor.java)
- Ingest 실행 시 해당 옵션(`directVectorImport` 플래그)이 활성화되어 있다면, 외부 Node JS 프로세스를 띄워 LLM Ingest를 처리하는 기존 흐름 대신, 자바 백엔드의 `WikiIndexService`를 직접 구동하도록 제어 흐름을 분기한다.

### C. 증분 임베딩 적재 연동
- 대상 폴더 내 파일들을 읽어 `WikiPreprocessor` 및 `WikiChunker`로 청크를 생성하고, `WikiIndexService` 및 `WikiVectorRepository`를 직접 연동하여 SQLite DB(`wiki-vector.sqlite`)에 곧바로 적재 및 증분 업데이트(Reindex)한다.
- 진행률(Progress Bar) 표시를 지원하여 백그라운드 진행 상태를 유려하게 노출한다.

## 3. 수정 대상 파일

- **UI 및 컨트롤러**: 
  - [WikiIngestDialog.java](file:///D:/git/java/llm_manager/src/main/java/org/kyj/llmmanager/ui/dialog/WikiIngestDialog.java)
- **비즈니스 로직 및 커맨드 분기**: 
  - [PluginCommandExecutor.java](file:///D:/git/java/llm_manager/src/main/java/org/kyj/llmmanager/service/PluginCommandExecutor.java)
- **인덱싱 처리 및 적재**: 
  - [WikiIndexService.java](file:///D:/git/java/llm_manager/src/main/java/org/kyj/llmmanager/service/WikiIndexService.java)
  - [WikiVectorRepository.java](file:///D:/git/java/llm_manager/src/main/java/org/kyj/llmmanager/service/WikiVectorRepository.java)

## 4. 완료 기준
- 이미 옵시디언 위키링크가 존재하는 폴더를 수집할 때, 대기나 LLM 구동 없이 프로그레스 바가 올라가며 수 초 만에 수집 완료 알림이 뜰 것.
- 수집이 끝난 즉시 `wiki-vector.sqlite` DB 파일에 해당 문서들의 본문 및 메타데이터, BGE-M3 임베딩 값이 완벽히 적재될 것.
- `wiki_query`를 호출하여 수집된 지식에 대한 시맨틱 컨텍스트 조회가 정상적으로 수행될 것.

## 5. 구현 플랜 초안

### 5-1. 현재 코드 기준 관찰
- 현재 `wiki.ingest`는 `WikiIngestDialog`에서 선택한 파일을 `raw/<category>/`로 복사한 뒤, `WikiIngestPlanner.plan(...)`이 만든 작업 목록을 `PluginCommandExecutor.executeStreaming(...)`으로 순차 실행한다.
- 이 실행 경로는 결국 `PluginCommandExecutor.runWikiTool(...)` → `runWikiViaCursorAgent(...)` 흐름으로 이어지며, `wiki.ingest` 성공 후에만 비동기 `wiki.reindex`를 트리거한다.
- 반면 `wiki.reindex`는 이미 Cursor Agent를 거치지 않고 `WikiIndexService.indexWorkspace(...)`를 직접 호출하는 내부 경로가 준비돼 있다.
- `WikiPreprocessor`와 `WikiChunker`는 frontmatter/title/type/tags 보정, 섹션 분할, content hash 부여를 이미 지원하므로, "잘 정리된 마크다운을 바로 임베딩/증분 적재"하는 데 필요한 핵심 부품은 존재한다.
- 다만 현재 `WikiIndexService`는 `workspace/wiki/` 아래 페이지를 수집하는 구조이므로, raw 원본을 그대로 읽어 인덱싱하는 방식보다는 `wiki/sources/` 쪽에 최소 복사/동기화한 뒤 기존 색인기를 재사용하는 편이 변경 범위가 작다.

### 5-2. 1차 구현 목표 (최소 변경 경로)
- 1차 목표는 "옵시디언 구조가 이미 갖춰진 markdown 묶음"에 한해, LLM Ingest를 건너뛰고 `wiki/sources/`에 빠르게 적재한 뒤 즉시 `WikiIndexService.indexWorkspace(...)`를 실행하는 것이다.
- 이 단계에서는 엔티티/개념 페이지 자동 생성, overview 재서술, contradiction 정리 같은 LLM 부가가치는 포기한다.
- 대신 사용자는 비용 없이 빠르게 시맨틱 검색 가능한 상태를 얻고, 필요하면 나중에 특정 문서만 정식 ingest로 다시 태울 수 있게 한다.

### 5-3. 왜 raw 직통 인덱싱이 아니라 wiki/sources/ 동기화인가
- 현재 검색·브라우저·사이트 export·색인 상태 점검은 모두 `wiki/` 구조를 전제로 한다.
- `WikiMarkdownUtils.collectPages(...)`도 `overview.md`, `index.md`, `log.md`, `sources/entities/concepts/syntheses`를 기준으로 페이지를 수집한다.
- 따라서 raw 폴더를 별도 검색 루트로 추가하면 `WikiIndexService`, `WikiBrowserDialog`, `wiki_query`, 상태 점검 UI까지 연쇄 수정이 필요하다.
- 반면 `wiki/sources/`에 문서를 빠르게 미러링하면 기존 브라우저/검색/상태 점검을 거의 그대로 재사용할 수 있다.
- 결론적으로 1차 구현은 "raw 보존 + wiki/sources/ 직접 동기화 + indexWorkspace 호출"을 기본안으로 한다.

### 5-4. 사용자 경험(UX) 설계

#### A. 수집 다이얼로그 옵션 추가
- `WikiIngestDialog.java`에 다음 체크박스를 추가한다.
  - `[ ] 옵시디언 링크 구조 보존 (LLM Ingest 건너뛰고 벡터 색인만 빠르게 적재)`
- 체크박스가 켜지면 안내 문구를 함께 노출한다.
  - `wiki/sources/에 최소 메타데이터만 붙여 저장하고, entity/concept 자동 생성은 생략합니다.`

#### B. 자동 추천 힌트
- 사용자가 파일/폴더를 고르면 백그라운드에서 샘플 3~5개 markdown 파일만 빠르게 검사한다.
- `[[...]]` 패턴이 일정 밀도 이상 검출되면:
  - 체크박스를 자동 ON
  - 라벨에 `(권장)` 또는 `옵시디언 링크 다수 감지` 표시
- 단, 사용자가 수동으로 OFF 하면 그 상태를 우선한다.

#### C. 적용 범위 제한
- 1차 구현에서는 아래 조건을 모두 만족할 때만 direct import 옵션을 활성 권장한다.
  - 입력 파일이 `.md` 위주일 것
  - `[[...]]` 링크가 일정 수 이상 감지될 것
  - 워크스페이스가 이미 초기화되어 있을 것 (`wiki/index.md` 존재)
- PDF/DOCX/HTML 혼합 수집에서는 기존 LLM ingest 흐름을 기본값으로 유지한다.

### 5-5. 동작 분기 설계

#### A. 요청 옵션 전달
- `WikiIngestDialog.runPlan(...)`는 현재 각 `IngestTask`의 `options()`만 `PluginCommandRequest.options`에 실어 보낸다.
- direct import는 태스크별 옵션이 아니라 "이번 수집 세션 전체 정책"이므로, 실행 전에 공통 옵션 맵에 아래 값을 추가해 넘긴다.
  - `directVectorImport=true`
  - `directImportMode=obsidian-fast`
  - 필요 시 `directImportCategory=<raw-category>`

#### B. `PluginCommandExecutor` 분기
- `PluginCommandExecutor.runWikiTool(...)`에서 `wiki.ingest` 처리 시 `request.options().get("directVectorImport")`를 먼저 검사한다.
- `true`면 기존 `runWikiViaCursorAgent(...)` 대신 새 내부 메서드로 분기한다.
  - 예: `runDirectObsidianImport(workspace, request, onOutput)`
- direct import 성공 시에는 별도의 비동기 `triggerAsyncReindex(...)`를 다시 부르지 않는다.
  - direct import 내부에서 이미 `wiki/sources/` 동기화와 `indexWorkspace(...)`까지 끝내므로 중복 재색인을 막아야 한다.

### 5-6. direct import 내부 처리 순서

#### Step 1. 입력 파일 집합 해석
- `request.prompt()`에는 현재처럼 파일/폴더 목록 또는 플래너가 만든 payload가 들어간다.
- 1차 구현에서는 플래너 경유 payload를 재파싱하는 대신, `WikiIngestDialog`에서 direct import 전용 실행 경로를 따로 태워 "실제 복사 완료된 파일 목록"을 옵션 또는 단순 newline payload로 넘기는 쪽이 안전하다.
- 가능하면 `runPlan(...)` 전에 direct import 모드에서는 `WikiIngestPlanner`를 생략하고, `copyIntoRaw(...)` 결과 파일 목록을 바로 executor에 한 번 전달한다.

#### Step 2. raw 원본 보존
- 기존과 동일하게 원본은 `raw/<category>/` 아래에 복사한다.
- raw는 불변 보관소 역할을 유지하고, 이 파일은 직접 수정하지 않는다.

#### Step 3. `wiki/sources/` 빠른 생성/갱신
- 각 raw markdown 파일마다 대응하는 `wiki/sources/<slug>.md`를 생성 또는 갱신한다.
- LLM 기반 요약 문서가 아니라, "옵시디언 원문을 거의 그대로 보존"하는 얇은 래퍼 포맷을 사용한다.

권장 포맷 예시:

```markdown
---
title: "원본 제목 또는 파일명"
type: source
tags: [obsidian-import]
source_file: raw/<category>/<file>.md
import_mode: direct-obsidian
last_updated: YYYY-MM-DD
---

## 원문

<원본 markdown 본문>
```

- 핵심은 `WikiPreprocessor`가 frontmatter/title/type/tags를 읽을 수 있게 최소 메타데이터를 붙이고, 본문은 원문을 최대한 그대로 두는 것이다.
- 원문에 frontmatter가 이미 있다면 아래 정책 중 하나를 선택한다.
  - 기본안: 외부 래퍼 frontmatter만 유지하고, 원본 frontmatter는 본문 상단에 그대로 둔다.
  - 대안: 원본 frontmatter의 `title/tags`를 병합하고 중복은 정규화한다.
- 1차 구현은 단순성을 위해 "외부 래퍼 + 원문 그대로"를 우선한다.

#### Step 4. `wiki/index.md` 동기화
- direct import 문서도 브라우저와 사용자 가시성을 위해 `wiki/index.md` Sources 섹션에 등록한다.
- 단, 한 줄 요약은 LLM 없이 만들 수 없으므로 초기에는 다음처럼 보수적으로 기록한다.
  - `[문서 제목](sources/foo.md) — direct obsidian import`
- 기존 항목이 있으면 중복 추가하지 않고 경로/제목만 보정한다.

#### Step 5. `wiki/log.md` 기록
- direct import도 쓰기 작업이므로 추적 가능해야 한다.
- 다음 형식으로 append 한다.
  - `## [YYYY-MM-DD] ingest | <Title> (direct-obsidian)`

#### Step 6. 증분 벡터 색인 실행
- `WikiIndexService.indexWorkspace(workspace, onOutput)`를 즉시 호출한다.
- 이미 같은 content hash가 존재하는 청크는 skip, 본문 동일/헤더 변경은 relink, 변경 청크만 embed 하므로 대량 재수집에도 효율적이다.

### 5-7. 새 헬퍼/분리 후보
- `WikiIngestDialog`가 너무 비대해지지 않도록 direct import 관련 로직은 서비스 클래스로 분리하는 편이 좋다.
- 후보 클래스:
  - `WikiDirectImportService`
    - `importObsidianMarkdown(workspace, copiedFiles, category, onOutput)`
  - 내부 책임:
    - raw 파일 → `wiki/sources/` slug 계산
    - wrapper markdown 생성
    - `index.md` / `log.md` 동기화
    - `WikiIndexService` 호출
- 이 분리를 해 두면 나중에 UI가 아니라 CLI/자동화에서 같은 경로를 재사용하기 쉽다.

### 5-8. slug / 제목 / 링크 정책
- `wiki/sources/` 파일명은 기본적으로 raw 파일명 stem을 kebab-case 정규화해서 사용한다.
- 충돌 시 `-1`, `-2` suffix를 붙이되, 가능하면 raw와 sources의 대응이 안정적으로 유지되도록 동일 파일 내용이면 기존 페이지를 재사용한다.
- 옵시디언 `[[PageName]]` 링크는 1차 구현에서 "그대로 보존"한다.
- 링크 대상 페이지가 아직 `wiki/`에 없더라도, 브라우저/검색에서 나중에 이어질 수 있으므로 변환하지 않고 유지한다.

### 5-9. 자동 감지 규칙 초안
- 샘플 파일별 점수:
  - `[[...]]` 1개당 +2
  - frontmatter 존재 시 +1
  - `#`, `##` heading 구조가 있으면 +1
  - `.md` 확장자면 기본 검사 대상
- 추천 기준 예시:
  - 샘플 3개 이상에서 `[[...]]` 발견 또는
  - 전체 점수 합 8 이상이면 추천 ON
- 이 규칙은 heuristic일 뿐이며, 체크박스를 강제하지 않는다.

### 5-10. 예외/폴백 정책
- direct import 체크 상태라도 다음 경우에는 기존 ingest로 폴백한다.
  - 선택 항목 대부분이 markdown이 아님
  - 임베딩 서버가 응답하지 않음
  - `wiki/sources/` 쓰기 실패
  - 워크스페이스 초기화 실패
- 단, 폴백 시 조용히 바꾸지 말고 로그에 명확히 남긴다.
  - `옵시디언 직접 적재 조건 불충족 → 기본 LLM ingest로 전환`
- 사용자가 "반드시 direct import만"을 원하면 이후 2차 옵션으로 `폴백 없이 실패` 모드를 추가할 수 있다.

### 5-11. 테스트 계획

#### A. 단위 테스트
- `WikiDirectImportServiceTest` 신설
  - raw markdown 2~3개를 넣으면 `wiki/sources/*.md`가 생성되는지
  - frontmatter 없는 문서도 wrapper가 붙는지
  - `wiki/index.md`에 direct import 항목이 추가되는지
  - `wiki/log.md`에 direct-obsidian 로그가 남는지
- `WikiIndexServiceTest` 보강
  - direct import로 생성된 source wrapper가 CURRENT/STALE/NOT_INDEXED 판정과 호환되는지
- 자동 감지 로직을 별도 메서드/클래스로 뽑는다면
  - `ObsidianHeuristicsTest`에서 `[[link]]` 밀도 기반 추천 여부 검증

#### B. UI/흐름 테스트
- `WikiIngestDialog` 로직이 직접 테스트하기 어렵다면, 옵션 맵 생성 부분을 작은 메서드로 분리해 테스트한다.
- 체크박스 ON 시 `PluginCommandRequest.options`에 `directVectorImport=true`가 포함되는지 검증한다.

#### C. 수동 검증
- 샘플 옵시디언 vault 일부를 선택
- direct import ON
- LLM 호출 없이 빠르게 완료되는지 확인
- `wiki/sources/` 생성 확인
- `wiki-vector.sqlite` 청크 수 증가 확인
- `wiki_query` 또는 대응 검색 UI에서 문서 내용 검색 확인
- 동일 문서 재실행 시 대부분 skip/relink 되는지 확인

### 5-12. 단계별 구현 순서 제안

#### Phase 1. 백엔드 최소 경로 완성
- `WikiDirectImportService` 작성
- raw → `wiki/sources/` wrapper 생성
- `index.md` / `log.md` 동기화
- `WikiIndexService.indexWorkspace(...)` 연계

#### Phase 2. executor 분기 연결
- `PluginCommandExecutor.runWikiTool(...)`에 `directVectorImport` 분기 추가
- direct import 성공 시 중복 reindex 방지

#### Phase 3. UI 체크박스 추가
- `WikiIngestDialog`에 체크박스 + 설명 라벨 추가
- 옵션 전달 경로 연결

#### Phase 4. 자동 추천 UX
- 샘플 스캔 기반 추천 ON/OFF
- `(권장)` 힌트 표기

#### Phase 5. 테스트/문서 보강
- 단위 테스트 추가
- `docs/llm-manager/wiki-agent.md`와 도움말에 direct import 모드 설명 추가

### 5-13. 1차 구현에서 의도적으로 하지 않는 것
- entity/concept 자동 생성
- overview 자동 갱신
- contradiction 분석
- raw 문서 외의 별도 wiki graph 보강
- Obsidian 링크를 실제 `entities/`/`concepts/`로 재배치하는 리라이트

이 기능들은 direct import의 목표(비용 최소화, 속도 극대화)와 충돌하므로 1차 범위에서 제외한다.

### 5-14. 오픈 질문
- direct import wrapper의 본문에 `## 원문` 같은 섹션 헤더를 붙일지, 원문을 바로 body로 둘지 결정 필요
- 원본 frontmatter를 병합할지, wrapper frontmatter만 둘지 결정 필요
- direct import 대상 문서가 매우 많을 때 `wiki/index.md` append 전략을 어떻게 최적화할지 검토 필요
- 추후 `wiki_context`/`wiki_query` 응답에서 direct-import source를 구분 표기할지 UX 논의 필요

### 5-15. 최종 권장안
- 1차 릴리스는 "옵시디언 마크다운 → raw 보존 → wiki/sources 얇은 wrapper 생성 → 기존 `WikiIndexService`로 즉시 증분 색인"으로 구현한다.
- 즉, 새 검색 인프라를 만드는 대신 기존 `wiki/` 생태계를 그대로 활용하는 쪽이 가장 안전하고 빠르다.
- 이후 사용성이 검증되면 2차로
  - direct import 전용 source 템플릿 고도화
  - 옵시디언 링크 자동 분석 기반 entity/concept 승격
  - 폴더 단위 대량 import 최적화

### 5-16. 재색인 버튼 배치 권장안
- 재색인 액션은 `WikiIngestDialog`의 주 액션으로 두기보다, `WikiIndexStatusDialog`에 두는 편이 역할이 명확하다.
- 권장 UI는 다음과 같다.
  - `색인 상태 조회`
  - `전체 재색인`
- 사용자는 상태(CURRENT / STALE / NOT_INDEXED)를 본 직후 바로 재색인할 수 있고, 재색인 완료 후 같은 다이얼로그에서 즉시 상태를 다시 새로고침할 수 있다.
- `SettingsDialog`의 `선택 디렉토리 색인 상태 확인` 버튼은 유지하고, 운영 액션은 `WikiIndexStatusDialog` 안에서 마무리하도록 한다.

### 5-17. docstor 2차 도입 아이디어
- `docstor`는 direct import 이후의 2차 구조화 저장 계층으로 검토할 가치가 높다.
- 다만 1차 direct import의 목표는 "최소 변경으로 빠른 적재와 검색 가능 상태 확보"이므로, 지금 단계에서 바로 `docstor`까지 넣으면 책임이 과도하게 커진다.
- 권장 순서는 다음과 같다.
  1. direct import + 재색인 운영 UX를 안정화한다.
  2. `wiki/sources/` wrapper, raw 원본, 색인 메타데이터에서 공통 필드를 추출한다.
  3. 이후 `DocumentRecord`/`DocstorService` 같은 공통 저장 계층으로 승격한다.
- 장기적으로 `docstor`는 아래 역할을 맡기 좋다.
  - raw 원본과 파생 문서(wrapper / 요약 / 구조화 문서)의 연결 관리
  - 문서별 ingest mode(`llm`, `direct-obsidian`, 향후 `conversation`) 추적
  - title / tags / source path / hash / last indexed / vector status 같은 공통 메타데이터 관리
  - 위키 외 다른 문서 저장 파이프라인으로의 재사용
- 결론적으로 `docstor`는 좋은 방향이지만, direct import 1차 릴리스의 하부 인프라가 아니라 "검증된 뒤 공통화하는 2차 리팩터링/승격 목표"로 두는 것이 안전하다.
