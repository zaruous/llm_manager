# BGE-M3 Embedding Server

> BAAI/bge-m3 임베딩 모델을 FastAPI로 서빙하는 Python 서버.  
> 텍스트를 고밀도 벡터로 변환해 시맨틱 검색·RAG·유사도 계산에 사용한다.

---

## 개요

| 항목 | 값 |
|------|-----|
| 런타임 | Python (FastAPI + Uvicorn) |
| 기본 포트 | 18080 |
| 헬스체크 | `GET /health` |
| 모델 | BAAI/bge-m3 (HuggingFace) |
| 저장소 | https://github.com/zaruous/bgem3-pyserver |

BGE-M3는 다국어를 지원하는 대규모 임베딩 모델로, 한국어 문서 검색에 특히 적합하다.  
Dense / Sparse / ColBERT 세 가지 검색 방식을 동시에 지원한다.

---

## 시스템 요구사항

- Python 3.9 이상
- pip 또는 pip3
- (선택) CUDA 지원 NVIDIA GPU — CPU로도 동작하나 속도가 느림
- 디스크 여유 공간 약 **2.5 GB** (모델 가중치 포함)
- RAM 최소 **4 GB** (GPU 없을 때 8 GB 권장)

---

## 설치

LLM Manager에서 서비스를 선택한 뒤 **설치 탭 → ⬇ 설치** 버튼을 누르면  
아래 명령어가 자동으로 실행된다.

```bash
pip install fastapi "uvicorn[standard]" FlagEmbedding torch pydantic numpy
```

소스 코드는 설치 경로에 미리 git clone 해두어야 한다.

```bash
git clone https://github.com/zaruous/bgem3-pyserver <설치경로>
```

---

## 실행 인수

| 인수 | 플래그 | 기본값 | 설명 |
|------|--------|--------|------|
| port | `--port` | `18080` | 서버 리슨 포트 |
| device | `--device` | `auto` | 연산 장치: `auto` / `cpu` / `cuda` / `mps` |
| fp16 | `--fp16` | `false` | FP16 반정밀도 — CUDA 환경에서 속도·메모리 절감 |
| batch-size | `--batch-size` | `32` | 한 번에 처리할 텍스트 배치 크기 |
| model | `--model` | `BAAI/bge-m3` | HuggingFace 모델 이름 또는 로컬 경로 |

### device 선택 가이드

| 값 | 설명 |
|----|------|
| `auto` | GPU가 있으면 CUDA, Apple Silicon이면 MPS, 없으면 CPU 자동 선택 |
| `cpu` | CPU 강제 사용. 느리지만 범용적 |
| `cuda` | NVIDIA GPU. CUDA Toolkit 설치 필요 |
| `mps` | Apple Silicon (M1/M2/M3). macOS 전용 |

---

## API 엔드포인트

서버 실행 후 `http://localhost:3000` 에서 확인할 수 있다.

```
POST /embed          — 텍스트 배열을 벡터로 변환
GET  /health         — 서버 상태 확인
GET  /docs           — FastAPI 자동 생성 Swagger UI
```

### 임베딩 요청 예시

```bash
curl -X POST http://localhost:3000/embed \
  -H "Content-Type: application/json" \
  -d '{"texts": ["안녕하세요", "hello world"]}'
```

---

## SQL Gen MCP 연동

SQL Gen MCP Server의 임베딩 제공자로 사용할 수 있다.

```
tei.base-url = http://localhost:3000/v1
embedding.provider = tei
```

> BGE-M3 서버를 먼저 실행한 후 SQL Gen MCP를 시작해야 한다.

---

## 문제 해결

**모델 다운로드가 너무 느림**  
첫 실행 시 HuggingFace에서 약 2.5 GB 모델을 다운로드한다.  
`HF_ENDPOINT` 환경변수로 미러 서버를 지정할 수 있다.

**CUDA out of memory**  
`--batch-size` 값을 줄이거나 (예: 8~16), `--fp16 true`를 활성화한다.

**포트 충돌**  
기본 포트 3000이 사용 중이면 `--port` 값을 변경한다.
