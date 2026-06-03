# Swagger MCP Server

> OpenAPI(Swagger) 문서를 읽어 MCP 도구로 변환하는 Java 서버.  
> Claude Code 등 MCP 클라이언트에서 REST API를 자연어로 직접 호출할 수 있게 해준다.

---

## 개요

| 항목 | 값 |
|------|-----|
| 런타임 | Java (Spring Boot) |
| 기본 포트 | 17070 |
| 헬스체크 | `GET /actuator/health` |
| MCP 프로토콜 | HTTP SSE |
| 저장소 | 사내 배포 JAR |

REST API를 MCP 도구로 자동 변환해, Claude Code가 Swagger 문서를 기반으로  
실제 API를 호출하도록 지원한다. 매번 curl 명령어를 작성할 필요 없이  
자연어로 "주문 목록 조회해줘" 같은 요청이 가능해진다.

---

## 시스템 요구사항

- Java 17 이상
- 대상 API 서버가 Swagger 2.x 또는 OpenAPI 3.x 문서를 제공해야 함
- JAR 파일: `swagger-mcp-server.jar`

---

## 설치

별도 설치 과정 없음. JAR 파일을 설치 경로에 복사한 후  
LLM Manager에서 **설치 경로**를 JAR 파일이 있는 디렉토리로 지정한다.

```
설치 경로: C:\Users\사용자\projects\swagger-mcp\
JAR 파일:  swagger-mcp-server.jar
```

---

## 실행 인수

### 필수 설정

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| server.port | `--server.port=` | `17070` | MCP 서버 HTTP 포트 |
| api-base-url | `--infobip.openapi.mcp.api-base-url=` | `http://localhost:20301` | 실제 API 요청이 전달될 백엔드 서버 URL |
| open-api-url | `--infobip.openapi.mcp.open-api-url=` | `http://localhost:20301/v3/api-docs` | OpenAPI 문서 URL 또는 파일 경로 |

> `api-base-url`과 `open-api-url`은 반드시 대상 시스템에 맞게 변경해야 한다.

### 고급 설정 (선택)

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| auth.header-name | `--swagger.auth.header-name=` | (없음) | 인증 헤더 이름 (예: `Authorization`) |
| auth.header-value | `--swagger.auth.header-value=` | (없음) | 인증 헤더 값 (예: `Bearer eyJ…`) |
| embedding.provider | `--embedding.provider=` | `tei` | 임베딩 모델: `tei` / `local` / `ollama` |
| vector-store.provider | `--vector-store.provider=` | `memory` | 벡터 저장소: `memory` / `chroma` / `pgvector` |
| reindex-on-startup | `--indexing.reindex-on-startup=` | `true` | 시작 시 API 툴 재인덱싱 여부 |
| codebase-memory.enabled | `--codebase-memory.enabled=` | `false` | Codebase Memory MCP 연동 활성화 |

---

## Claude Code 연동

서버 실행 후 Claude Code의 MCP 설정 파일(`claude_desktop_config.json` 또는 `.mcp.json`)에 추가한다.

```json
{
  "mcpServers": {
    "swagger-mcp": {
      "type": "sse",
      "url": "http://localhost:17070/sse"
    }
  }
}
```

이후 Claude Code에서 아래와 같이 사용 가능하다.

```
사용자: 미완료 주문 목록을 조회해줘
Claude: [swagger-mcp 도구 호출] GET /api/orders?status=pending → ...
```

---

## Swagger 2.x 연동

OpenAPI 3.x가 아닌 Swagger 2.x 문서를 사용하는 경우 URL 경로를 변경한다.

```
open-api-url = http://localhost:20301/v2/api-docs
```

---

## 벡터 저장소 선택 가이드

| 값 | 특징 | 권장 상황 |
|----|------|-----------|
| `memory` | 재시작 시 재인덱싱 | 개발·테스트 |
| `chroma` | ChromaDB 영속 저장 | 운영 환경, API 변경이 드문 경우 |
| `pgvector` | PostgreSQL 기반 영속 | 기존 PostgreSQL 인프라 활용 |

`chroma` / `pgvector` 사용 시 `reindex-on-startup=false`로 설정하면  
서버 재시작 시간을 단축할 수 있다.

---

## 문제 해결

**연결 거부 오류**  
`api-base-url`이 올바른지, 대상 API 서버가 실행 중인지 확인한다.

**MCP 도구가 Claude Code에 나타나지 않음**  
Claude Code를 재시작하거나 MCP 서버 목록을 새로고침한다.  
서버 로그에서 인덱싱 완료 메시지를 확인한다.

**인증 오류 (401/403)**  
`auth.header-name`과 `auth.header-value`를 올바르게 설정했는지 확인한다.
