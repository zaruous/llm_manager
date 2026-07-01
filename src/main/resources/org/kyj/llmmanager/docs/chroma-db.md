# ChromaDB

> 오픈소스 벡터 데이터베이스 서버.  
> 임베딩 벡터를 영속 저장하고 유사도 검색을 제공한다. swagger-mcp·sql-gen-mcp의
> `chroma` 벡터 스토어 백엔드로 사용된다.

---

## 개요

| 항목 | 값 |
|------|-----|
| 런타임 | Python (`chroma` CLI) |
| 기본 포트 | 18000 |
| 헬스체크 | `GET /api/v2/heartbeat` |
| 데이터 저장 | `<설치경로>/data` (SQLite + HNSW 인덱스) |
| 홈페이지 | https://www.trychroma.com |

기본 포트를 18000으로 설정한 이유: Swagger MCP·SQL Gen MCP의
ChromaDB URL 기본값이 `http://localhost:18000` 이므로, 이 템플릿으로 설치하면
별도 설정 없이 바로 연동된다.

---

## 시스템 요구사항

- Python 3.9 이상
- pip 또는 pip3
- 디스크 여유 공간: 저장할 벡터 규모에 비례 (최소 수백 MB 권장)

---

## 설치

LLM Manager에서 서비스를 선택한 뒤 **설치 탭 → ⬇ 설치** 버튼을 누르면
아래 명령어가 자동으로 실행된다.

```bash
pip install chromadb
```

git clone이 필요 없다. 설치 경로(`~/llm-services/chroma-db`)는 데이터 저장용으로만
사용되며, 서버 본체는 pip 패키지의 `chroma` CLI로 실행된다.

특정 버전이 필요하면 설치 전 `[설치 설정] chromadb-version` 인수에
버전(예: `1.0.15`)을 입력한다.

---

## 실행 인수

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| port | `--port` | `18000` | 서버 리슨 포트 |
| host | `--host` | `localhost` | 바인딩 호스트. 외부·Docker 접근 허용 시 `0.0.0.0` |
| path | `--path` | `<설치경로>/data` | 벡터 데이터 영속 저장 경로 |

---

## API 엔드포인트

서버 실행 후 `http://localhost:18000` 에서 확인할 수 있다.

```
GET /api/v2/heartbeat   — 서버 상태 확인 (헬스체크)
GET /docs               — FastAPI 자동 생성 Swagger UI
```

```bash
curl http://localhost:18000/api/v2/heartbeat
```

---

## MCP 서버 연동

### Swagger MCP Server

```
vector-store.provider   = chroma
vector-store.chroma.url = http://localhost:18000
```

### SQL Gen MCP Server

```
vector-store.provider = chroma
chroma.url            = http://localhost:18000
```

> ChromaDB를 먼저 실행한 후 MCP 서버를 시작해야 한다.
> Docker 컨테이너 내부에서 접속할 때는 `http://host.docker.internal:18000` 을 사용한다.

---

## 문제 해결

**`chroma` 명령을 찾을 수 없음**  
pip 스크립트 경로가 PATH에 없는 경우다. Windows는 `%APPDATA%\Python\Scripts`
또는 Python 설치 경로의 `Scripts` 디렉토리를 PATH에 추가한다.

**포트 충돌**  
기본 포트 18000이 사용 중이면 `--port` 값을 변경한다. 포트를 바꾸면
연동하는 MCP 서버의 chroma URL도 함께 수정해야 한다.

**데이터 초기화**  
서비스를 중지한 뒤 `--path` 로 지정한 데이터 디렉토리를 삭제하면 된다.
