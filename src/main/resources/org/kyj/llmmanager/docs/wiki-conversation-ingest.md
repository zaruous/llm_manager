# Wiki 대화 수집

현재 LLM 대화에서 결정사항, 설계 논의, 해결된 오류 등 필요한 부분만 선별해
Wiki 원본으로 저장하는 기능입니다.

대화를 자동으로 수집하지 않습니다. 사용자가 명시적으로 요청하면
`wiki-ingest-conversation` 스킬이 내용을 정제한 뒤 Wiki MCP의 `wiki_ingest`
도구를 호출합니다.

---

## 전체 흐름

```text
현재 LLM 대화
  → wiki-ingest-conversation 스킬
  → 관련 대화 선별·비밀값 제거
  → wiki_ingest(content=...)
  → raw/mcp-ingest/<날짜>/에 원본 보존
  → Cursor Agent가 Wiki 페이지 생성·갱신
  → 필요 시 벡터 색인 갱신
```

시스템 지시, 내부 추론, 불필요한 도구 출력은 수집하지 않습니다. API 키나 토큰처럼
비밀값으로 의심되는 내용은 `[REDACTED]`로 제거합니다.

---

## 1. 사전 준비

다음 항목이 필요합니다.

- Node.js 22 이상
- 글로벌 `@cursor/sdk`
- `cursor-agent-runner` 플러그인
- `CURSOR_API_KEY` 시스템 환경변수
- Wiki 워크스페이스
- 실행 중인 `Wiki MCP Server`

Wiki 워크스페이스는 다음 구조를 가져야 합니다.

```text
my-wiki/
├── raw/
├── wiki/
│   ├── index.md
│   └── log.md
└── graph/
```

아직 없다면 `플러그인 > LLM Wiki Agent > 문서 수집`에서 빈 디렉토리를 선택하고
초기화 안내를 수락합니다.

---

## 2. 대화 수집 스킬 설치

1. LLM Manager에서 `LLM 스킬 설치` 화면을 엽니다.
2. 프로젝트 경로에 Wiki 워크스페이스 루트를 지정합니다.
3. `LLM Wiki Agent`를 선택합니다.
4. 사용하는 클라이언트에 맞는 팩을 선택합니다.
5. 설치 전 미리보기에서 대상 경로를 확인하고 설치합니다.

| 클라이언트 | 선택할 팩 | 설치 경로 |
|------------|------------|-----------|
| Codex | Codex/OpenCode 스키마 | `.agents/skills/wiki-ingest-conversation/` |
| Cursor | Cursor 대화 수집 스킬 | `.cursor/skills/wiki-ingest-conversation/` |
| Claude Code | Claude Code 스킬 | `.claude/skills/wiki-ingest-conversation/` |

Codex 스킬은 자동 과수집을 막기 위해 `allow_implicit_invocation: false`로 설치됩니다.
명시적으로 `$wiki-ingest-conversation`을 호출해야 합니다.

---

## 3. Wiki MCP Server 설정

서비스 목록에서 `Wiki MCP Server`를 설치하고 다음 값을 설정합니다.

| 설정 | 권장값 | 설명 |
|------|--------|------|
| `workspace` | Wiki 워크스페이스 루트 | `wiki/index.md`가 있는 상위 디렉토리 |
| `port` | `18090` | MCP HTTP 포트 |
| `enable-write` | `true` | `wiki_ingest` 도구 활성화 |
| `cursor-model` | 비움 | Cursor Agent Runner 기본 모델 사용 |
| `cursor-timeout` | `-1` | Wiki Agent의 실행 제한 설정 사용 |
| `embedding-url` | `http://localhost:18080` | 검색·색인용 BGE-M3 서버 |

`enable-write=false`이면 조회 도구는 사용할 수 있지만 대화 수집용 `wiki_ingest`는
노출되지 않습니다.

설정을 저장한 뒤 Wiki MCP Server를 시작합니다. 기본 MCP 주소는 다음과 같습니다.

```text
http://localhost:18090/mcp
```

---

## 4. LLM 클라이언트에 MCP 연결

### Codex

Wiki 워크스페이스에서 다음 명령을 한 번 실행합니다.

```powershell
codex mcp add wiki-mcp --url http://localhost:18090/mcp
```

연결 상태 확인:

```powershell
codex mcp list
```

Codex를 이미 실행 중이었다면 새 세션을 열어 스킬과 MCP 도구 목록을 다시 읽습니다.

### Cursor 또는 Claude Code

클라이언트의 MCP 설정 화면이나 프로젝트 MCP 설정 파일에서 이름을 `wiki-mcp`,
URL을 `http://localhost:18090/mcp`로 등록합니다. 전송 방식은 Streamable HTTP를
사용합니다. 제품 버전에 따라 설정 파일 위치와 UI 명칭이 다를 수 있으므로,
연결 후 도구 목록에 `wiki_ingest`가 표시되는지 확인합니다.

---

## 5. 사용 방법

### Codex 명시 호출

```text
$wiki-ingest-conversation 현재 대화를 Wiki에 수집해
```

특정 범위만 수집할 수도 있습니다.

```text
$wiki-ingest-conversation 이번 대화 중 임베딩 전처리 설계 부분만 Wiki에 저장해
```

### Cursor·Claude 자연어 요청

```text
현재 대화를 Wiki에 수집해.
방금 해결한 오류와 최종 해결 방법만 지식으로 저장해.
이번 설계 논의에서 합의된 결정사항만 Wiki에 남겨줘.
```

스킬은 다음 형식의 대화 원본을 만듭니다.

- Context
- Discussion
- Decisions
- Implemented or Verified
- Open Questions
- Sanitized Transcript

제안과 실제 완료 작업을 구분하고, 검증되지 않은 assistant 답변을 확정 지식으로
기록하지 않도록 지시되어 있습니다.

---

## 6. 성공 여부 확인

성공하면 MCP 응답에 다음 정보가 포함됩니다.

- `success: true`
- `staged_source`: `raw/mcp-ingest/<날짜>/...md`
- Cursor Agent 처리 결과

워크스페이스에서도 확인할 수 있습니다.

1. `raw/mcp-ingest/<날짜>/`에서 정제된 대화 원본 확인
2. `wiki/sources/`에서 생성된 출처 페이지 확인
3. `wiki/index.md`, `wiki/log.md` 갱신 확인

---

## 7. 임베딩 상태 확인

대화 수집 성공과 벡터 임베딩 성공은 별개입니다.

1. `설정 > Wiki Agent` 탭을 엽니다.
2. `선택 디렉토리 색인 상태 확인`을 누릅니다.
3. Wiki 워크스페이스를 선택하고 상태를 조회합니다.

| 상태 | 의미 |
|------|------|
| 최신 | 현재 전처리 청크와 SQLite 색인 해시가 일치 |
| 변경됨 | Wiki가 바뀌었거나 청킹 설정이 변경되어 재색인 필요 |
| 미색인 | Wiki 파일은 있지만 저장된 임베딩 청크가 없음 |
| 빈 문서 | 임베딩할 본문이 없음 |
| 잔여 색인 | Wiki 파일은 삭제됐지만 DB 청크가 남아 있음 |

`변경됨` 또는 `미색인`이면 `플러그인 > LLM Wiki Agent > 벡터 색인 갱신`을
실행합니다. BGE-M3 Embedding Server가 실행 중이어야 합니다.

---

## 크기 제한

`plugin.json`의 `ingest.contentMaxBytes`는 MCP `content` 필드에 직접 담는
UTF-8 원문의 최대 크기입니다. 기본값은 10 MiB입니다.

이 값은 다음 항목의 제한이 아닙니다.

- `file_path`로 전달하는 원본 파일 크기
- Cursor가 요약하는 전체 문서 크기
- 최종 Wiki Markdown 크기
- 임베딩 청크 크기

대화 원본이 제한에 가까우면 스킬은 중복 transcript를 줄이면서 결정사항과 검증
근거를 우선 보존합니다. 그래도 안전하게 담을 수 없으면 대화를 주제별로 나누어
수집해야 합니다.

---

## 문제 해결

| 증상 | 확인 사항 |
|------|-----------|
| 스킬이 보이지 않음 | 설치 경로 확인 후 LLM 클라이언트 새 세션 또는 재시작 |
| `wiki_ingest`가 보이지 않음 | Wiki MCP의 `enable-write=true` 여부와 서버 재시작 확인 |
| MCP 연결 실패 | `http://localhost:18090/mcp` 주소와 서비스 실행 상태 확인 |
| `CURSOR_API_KEY` 오류 | 시스템 환경변수 설정 후 LLM Manager와 Wiki MCP 재시작 |
| `Cursor sidecar 스크립트가 없습니다` | Wiki MCP 서비스를 다시 설치해 sidecar 파일 동기화 |
| 워크스페이스 오류 | `wiki/index.md`가 있는 상위 루트를 `workspace`로 설정 |
| 수집됐지만 검색되지 않음 | Wiki 생성 후 벡터 색인 갱신 및 색인 상태 확인 |
| 대화가 너무 큼 | 필요한 주제를 지정해 여러 번 나누어 수집 |

