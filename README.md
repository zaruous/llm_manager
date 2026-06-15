# LLM Manager

로컬에서 실행하는 AI 서비스(MCP 서버, 임베딩 서버 등)를 등록·시작·중지·모니터링하는 JavaFX 데스크톱 앱.

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-21-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.x-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 주요 기능

- **서비스 관리** — 등록된 서비스를 시작·중지·재시작. 실행 상태를 대시보드 카드와 좌측 목록에 실시간 반영. 우클릭 컨텍스트 메뉴로 제거 가능
- **로그 모니터링** — 프로세스 stdout/stderr를 실시간 스트리밍. 필터·자동 스크롤·5000행 표시 제한
- **설정 편집** — 실행 인수(ArgSpec)·환경변수를 UI에서 직접 편집하고 저장
- **서비스 설치** — git clone + 의존성 설치 자동화. JAR 번들 서비스는 파일 복사로 설치
- **헬스 체크** — HTTP 엔드포인트를 주기적으로 폴링해 상태 갱신 (기본 10초)
- **메모리 모니터링** — OSHI 기반 RSS·가상 메모리 수집. 대시보드 스파크라인으로 시각화
- **기본 제공 서비스** — `service-packs/*.yml`에 정의된 서비스를 선택만 하면 바로 추가 (Groovy 스크립트로 OS/환경 자동 커스터마이징)
- **내장 REST API** — Javalin 기반 API 서버(선택 활성화). Claude Code 등 외부 도구에서 서비스 상태 조회 가능
- **LLM 스킬 & 룰 관리** — Claude/Copilot/Cursor/Gemini 스킬 팩 설치, 외부 디렉토리 스캔 후 선택 파일 로드
- **플러그인 시스템** — `plugin.json` 선언 기반 로컬 플러그인. cursor-agent-runner·LLM Wiki Agent 기본 제공
- **LLM Wiki Agent** — 문서를 수집해 AI가 상호 연결된 지식 위키를 자동 구축. Cursor Agent 위임 실행
- **위키 HTML 내보내기** — 위키 워크스페이스를 정적 HTML 사이트로 내보내기 (사이드바·검색·그래프 포함, 다크/라이트 테마)
- **자동 업데이트** — GitHub Releases 연동. 새 버전 감지 시 알림 후 설치 자동화
- **환경 체크** — 첫 실행 시 Node.js·Python·Cursor 등 필수 환경 자동 점검 (SetupChecker)
- **시스템 트레이** — 최소화 시 트레이 상주, 우클릭 메뉴로 빠른 제어

---

## 기본 제공 서비스

| 서비스 | 런타임 | 설명 |
|--------|--------|------|
| SQL Gen MCP Server | Java | 자연어 → SQL 변환 MCP 서버 (LangChain4j + PostgreSQL + TEI) |
| BGE-M3 Embedding Server | Python | BAAI/bge-m3 임베딩 모델 API 서버 (FastAPI). CUDA 자동 감지 |
| Swagger MCP Server | Java | Swagger/OpenAPI 스펙 기반 MCP 서버 |

---

## 스크린샷

![LLM Manager 메인 화면](https://raw.githubusercontent.com/zaruous/llm_manager/master/docs/llm-manager/MAIN.png)

---

## 시작하기

### 요구 사항

- Java 17 이상
- Gradle 9.x (또는 포함된 Gradle Wrapper 사용)

### 소스에서 실행

```bash
# 개발 서버 시작 (FXML/CSS 핫 리로드)
./gradlew runDev

# 프로덕션 실행
./gradlew run
```

### start.bat (Windows)

```bat
# 기본 실행
start.bat

# CLI 옵션과 함께 실행
start.bat --api.server.enabled=true --api.server.port=9090
```

`start.bat`은 `build/install/` 배포본이 없으면 `installDist`를 자동 실행한다.

---

## 설치 패키지 빌드

### 실행 파일 (기본 — WiX 불필요)

```powershell
powershell -ExecutionPolicy Bypass -File bin\buildExe.ps1
# → deploy\LLMManager-1.0.0.zip
#   압축 해제 후 LLMManager.exe 를 바로 실행 (Java 설치 불필요)
```

### 설치 파일 (EXE 인스톨러 — WiX 자동 다운로드)

```powershell
powershell -ExecutionPolicy Bypass -File bin\buildExe.ps1 -Installer
# → deploy\LLMManager-1.0.0.exe
#   더블클릭 → 설치 마법사 → 시작 메뉴 등록
```

자세한 내용은 [docs/패키징.md](docs/패키징.md) 참조.

---

## CLI 옵션

`start.bat` 또는 `./gradlew run --args="..."` 실행 시 아래 옵션을 사용할 수 있다.

| 옵션 | 설명 | 기본값 |
|------|------|--------|
| `--api.server.enabled=true` | 내장 REST API 서버 활성화 | `false` |
| `--api.server.port=8185` | API 서버 포트 | `8185` |
| `--runtime.python=python3` | Python 실행 명령어 | `python` |
| `--runtime.java-home=경로` | JAVA_HOME 경로 | 환경변수 |
| `--install.base=경로` | 서비스 기본 설치 경로 | `~/llm-services` |
| `--monitor.health-check-interval=5` | 헬스체크 주기 (초, 1~10) | `10` |
| `--skill-library.db.provider=sqlite` | 스킬 라이브러리 DB (sqlite/postgresql/oracle/mssql) | `sqlite` |

설정 우선순위: **CLI 인수 > settings.json > application.yml**

---

## 프로젝트 구조

```
llm_manager/
├── src/main/java/org/kyj/llmmanager/
│   ├── model/          # 데이터 모델 (ServiceInstance, AppSettings 등)
│   ├── model/plugin/   # 플러그인 모델 (PluginManifest, PluginCommand 등)
│   ├── service/        # 비즈니스 로직 (ProcessManager, HealthMonitor 등)
│   ├── setup/          # 환경 점검 (SetupChecker, SetupCheckDialog)
│   ├── ui/             # JavaFX 컨트롤러·다이얼로그·셀
│   └── util/           # 유틸리티
├── src/main/resources/
│   ├── org/kyj/llmmanager/  # FXML 레이아웃
│   └── llm-skills/          # Claude/Copilot/Cursor/Gemini/Wiki-Agent 스킬 파일
├── service-packs/      # 기본 제공 서비스 YAML (배포 포함)
├── plugins/
│   ├── cursor-agent-runner/  # Cursor Agent 실행 플러그인
│   └── wiki-agent/           # LLM Wiki Agent 플러그인 (Python 도구 포함)
├── bin/
│   ├── buildExe.ps1    # 패키지 빌드 스크립트
│   └── Download-WiX.ps1
├── deploy/             # 빌드 결과물 출력 디렉토리
└── docs/               # 아키텍처·패키징·위키 가이드 문서
```

---

## 데이터 저장 위치

모든 사용자 데이터는 `~/llm-services/`에 저장된다.

| 파일 | 내용 |
|------|------|
| `services.json` | 사용자가 추가한 서비스 정의 목록 |
| `settings.json` | 앱 설정 (런타임 경로, API 포트 등) |
| `projects.json` | LLM 스킬 설치 프로젝트 목록 |
| `app.log` | 앱 구동 로그 (7일 롤링) |

> 코드 기준 앱 홈은 `PlatformUtil.getAppHome()`이며 OS와 무관하게 `~/llm-services`를 사용한다.

---

## 개발 모드

```bash
# HotswapAgent 다운로드 (최초 1회)
./gradlew downloadHotswapAgent

# 개발 모드 실행 (FXML/CSS/서비스 정의 YAML 변경 즉시 반영)
./gradlew runDev
```

FXML·CSS 변경 시 앱 재시작 없이 자동으로 화면이 갱신된다.

---

## LLM Wiki Agent

문서를 수집하면 AI가 분석해 상호 연결된 지식 위키를 자동 구축·유지하는 기능.

### 사전 준비

- Node.js 22+
- 글로벌 `@cursor/sdk` (`npm install -g @cursor/sdk`)
- `CURSOR_API_KEY` 시스템 환경변수
- cursor-agent-runner 플러그인 (기본 내장)
- (큰 PDF 전처리 시) Python + pymupdf

### 주요 기능

| 메뉴 | 설명 |
|------|------|
| 문서 수집 (Ingest) | 파일/폴더 선택 → raw 복사 → AI 분석·위키 작성 |
| 위키 질의 (Query) | 위키를 기반으로 자연어 질의·응답 (이력 영속화) |
| 구조 검사 (Health) | 위키 구조 일관성 검사 |
| 품질 검사 (Lint) | 위키 콘텐츠 품질 검사 |
| 그래프 빌드 (Graph) | vis.js 기반 지식 그래프 생성 |
| 위키 브라우저 | 트리 탐색 + 마크다운 렌더링, `[[WikiLink]]` 이동, 키워드 검색 |
| HTML 내보내기 | 정적 HTML 사이트 내보내기 (사이드바·검색·그래프·테마 지원) |

자세한 내용은 [docs/llm-manager/wiki-agent.md](docs/llm-manager/wiki-agent.md) 참조.

---

## 자동 업데이트

앱 시작 시 또는 메뉴에서 GitHub Releases를 확인해 새 버전이 있으면 알림을 표시한다.
"업데이트 설치" 클릭 시 ZIP을 내려받아 현재 설치 경로에 자동 적용한다.

---

## 빌드 상태

`./gradlew build` 정상 통과 (2026-06-15 기준, v1.0.5).

---

## 문서

- [아키텍처](docs/llm-manager/architecture.md) — 레이어 구조·컴포넌트·스레딩 모델
- [서비스 설정 흐름](docs/llm-manager/service-configuration-flow.md) — YAML 서비스팩, 사용자 서비스 저장 흐름
- [패키징 가이드](docs/패키징.md) — 실행파일·설치파일 빌드 방법
- [LLM Wiki Agent 가이드](docs/llm-manager/wiki-agent.md) — 위키 에이전트 사용법·트러블슈팅
