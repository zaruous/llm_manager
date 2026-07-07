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
