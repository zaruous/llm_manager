# SQL Gen MCP Server

> 자연어를 SQL로 변환하는 MCP 서버.  
> LangChain4j + PostgreSQL + TEI 임베딩 기반으로 스키마를 이해하고 쿼리를 생성한다.

---

## 개요

| 항목 | 값 |
|------|-----|
| 런타임 | Java (Spring Boot) |
| HTTP 포트 | 8081 |
| MCP 포트 | 7070 |
| 헬스체크 | `GET /actuator/health` |
| DB | PostgreSQL |
| 임베딩 | TEI (Text Embeddings Inference) / ONNX 내장 |

테이블 스키마를 벡터로 인덱싱해두고, 자연어 질의가 들어오면  
관련 테이블을 시맨틱 검색으로 찾아 SQL을 생성한다.  
Claude Code를 통해 "이번 달 출하량 조회해줘"와 같은 요청을 처리할 수 있다.

---

## 시스템 요구사항

- Java 17 이상
- PostgreSQL 서버 (접근 가능한 주소·계정 필요)
- JAR 파일: `sql-gen-mcp-1.0.0-SNAPSHOT.jar`
- (권장) BGE-M3 또는 TEI 임베딩 서버

---

## 설치

JAR 파일을 설치 경로에 복사한 후 LLM Manager에서 경로를 지정한다.  
Groovy 스크립트가 자동으로 `workingDir`와 `startCommand`를 설정한다.

```
설치 경로: C:\Users\사용자\projects\sql-gen-mcp\bin\
JAR 파일:  sql-gen-mcp-1.0.0-SNAPSHOT.jar
```

---

## 실행 인수

### MCP 서버 설정

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| server.port | `--server.port=` | `8081` | HTTP 리슨 포트 (헬스체크·관리 API) |
| mcp.port | `--mcp.port=` | `7070` | MCP 프로토콜 포트 — Claude Code가 연결하는 포트 |

### 데이터베이스 설정

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| db.url | `--db.url=` | `jdbc:postgresql://192.168.45.7:5433/dbmes` | PostgreSQL 연결 URL |
| db.user | `--db.user=` | `tester1` | 접속 사용자명 |
| db.pw | `--db.pw=` | `tester1` | 접속 비밀번호 |
| db.schema | `--db.schema=` | `public` | 사용할 스키마 이름 |
| db.driver | `--db.driver=` | `org.postgresql.Driver` | JDBC 드라이버 클래스명 |

### 임베딩 설정

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| tei.base-url | `--ai.vector-store.providers.tei.base-url=` | `http://localhost:18080/v1` | TEI 서버 URL (`/v1` 포함) |

### 고급 설정 (선택)

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| embedding.provider | `--ai.vector-store.embedding.provider=` | `tei` | 임베딩 모델: `tei` / `local` / `ollama` / `openai` / `vllm` |
| vector-store.provider | `--ai.vector-store.provider=` | `local` | 벡터 저장소: `local`(인메모리) / `chroma` |

---

## 포트 구성

두 포트가 별도로 동작한다.

```
┌─────────────────────────────────────────────────┐
│  SQL Gen MCP Server                             │
│                                                 │
│  HTTP :8081  ← 헬스체크, 관리 API              │
│  MCP  :7070  ← Claude Code MCP 클라이언트      │
└─────────────────────────────────────────────────┘
```

LLM Manager의 **포트** 필드는 헬스체크용 HTTP 포트(8081)를 입력한다.

---

## Claude Code 연동

`.mcp.json` 또는 `claude_desktop_config.json`에 추가한다.

```json
{
  "mcpServers": {
    "sql-gen-mcp": {
      "type": "sse",
      "url": "http://localhost:7070/sse"
    }
  }
}
```

연동 후 Claude Code에서 자연어로 SQL을 요청할 수 있다.

```
사용자: 지난달 라인별 생산량 합계를 조회해줘
Claude: [sql-gen-mcp 도구 호출]
        SELECT line_id, SUM(qty) FROM production
        WHERE prod_date >= '2026-05-01' GROUP BY line_id
```

---

## 임베딩 제공자 선택

| 값 | 설명 | 권장 상황 |
|----|------|-----------|
| `tei` | Text Embeddings Inference 서버 (별도 기동 필요) | 품질 우선 |
| `local` | ONNX 내장 임베딩 (서버 불필요) | 간편한 설정 |
| `ollama` | Ollama 서버 경유 | Ollama 사용 환경 |

BGE-M3 Embedding Server를 TEI 제공자로 사용하는 경우:

```
tei.base-url = http://localhost:18080/v1
embedding.provider = tei   (고급 설정에서 활성화)
```

---

## 문제 해결

**DB 연결 실패**  
`db.url`의 호스트·포트·DB명을 확인한다.  
방화벽으로 인한 차단 여부도 확인한다.

**스키마 인덱싱 실패**  
`db.schema` 값이 실제 PostgreSQL 스키마명과 일치하는지 확인한다.  
계정에 스키마 읽기 권한이 있어야 한다.

**MCP 연결 안 됨**  
`mcp.port`(기본 7070)가 방화벽에 열려 있는지,  
다른 프로세스가 점유하지 않는지 확인한다.
