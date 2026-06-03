# LLM Manager

로컬에서 실행하는 AI 서비스(MCP 서버, 임베딩 서버 등)를 등록·시작·중지·모니터링하는 JavaFX 데스크톱 앱.

![Java](https://img.shields.io/badge/Java-17%2B-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-21-blue)
![Gradle](https://img.shields.io/badge/Gradle-9.x-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## 주요 기능

- **서비스 관리** — 등록된 서비스를 시작·중지·재시작. 실행 상태를 대시보드 카드와 좌측 목록에 실시간 반영
- **로그 모니터링** — 프로세스 stdout/stderr를 실시간 스트리밍. 필터·자동 스크롤·5000행 표시 제한
- **설정 편집** — 실행 인수(ArgSpec)·환경변수를 UI에서 직접 편집하고 저장
- **서비스 설치** — git clone + 의존성 설치 자동화. JAR 번들 서비스는 파일 복사로 설치
- **헬스 체크** — HTTP 엔드포인트를 주기적으로 폴링해 상태 갱신 (기본 10초)
- **메모리 모니터링** — OSHI 기반 RSS·가상 메모리 수집. 대시보드 스파크라인으로 시각화
- **기본 제공 서비스** — `lib/def/*.json`에 정의된 서비스를 선택만 하면 바로 추가
- **내장 REST API** — Javalin 기반 API 서버(선택 활성화). Claude Code 등 외부 도구에서 서비스 상태 조회 가능
- **LLM 스킬 설치** — Claude/Copilot/Cursor/Gemini 스킬 파일을 프로젝트 디렉토리에 자동 배포
- **시스템 트레이** — 최소화 시 트레이 상주, 우클릭 메뉴로 빠른 제어

---

## 기본 제공 서비스

| 서비스 | 런타임 | 설명 |
|--------|--------|------|
| SQL Gen MCP Server | Java | 자연어 → SQL 변환 MCP 서버 (LangChain4j + PostgreSQL + TEI) |
| BGE-M3 Embedding Server | Python | BAAI/bge-m3 임베딩 모델 API 서버 (FastAPI) |
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

설정 우선순위: **CLI 인수 > settings.json > application.yml**

---

## 프로젝트 구조

```
llm_manager/
├── src/main/java/org/kyj/llmmanager/
│   ├── model/          # 데이터 모델 (ServiceInstance, AppSettings 등)
│   ├── service/        # 비즈니스 로직 (ProcessManager, HealthMonitor 등)
│   ├── ui/             # JavaFX 컨트롤러·다이얼로그·셀
│   └── util/           # 유틸리티
├── src/main/resources/
│   ├── org/kyj/llmmanager/  # FXML 레이아웃
│   └── llm-skills/          # Claude/Copilot/Cursor/Gemini 스킬 파일
├── lib/
│   └── def/            # 기본 제공 서비스 정의 JSON
├── bin/
│   ├── buildExe.ps1    # 패키지 빌드 스크립트
│   └── Download-WiX.ps1
├── deploy/             # 빌드 결과물 출력 디렉토리
└── docs/               # 아키텍처·패키징 문서
```

---

## 데이터 저장 위치

모든 사용자 데이터는 `~/.llm-manager/`에 저장된다.

| 파일 | 내용 |
|------|------|
| `services.json` | 사용자가 추가한 서비스 정의 목록 |
| `settings.json` | 앱 설정 (런타임 경로, API 포트 등) |
| `projects.json` | LLM 스킬 설치 프로젝트 목록 |
| `app.log` | 앱 구동 로그 (7일 롤링) |

---

## 개발 모드

```bash
# HotswapAgent 다운로드 (최초 1회)
./gradlew downloadHotswapAgent

# 개발 모드 실행 (FXML/CSS/서비스 정의 JSON 변경 즉시 반영)
./gradlew runDev
```

FXML·CSS 변경 시 앱 재시작 없이 자동으로 화면이 갱신된다.

---

## 문서

- [아키텍처](docs/llm-manager/architecture.md) — 레이어 구조·컴포넌트·스레딩 모델
- [패키징 가이드](docs/패키징.md) — 실행파일·설치파일 빌드 방법
- [JavaFX vs Windows 데스크톱](docs/javafx-windows-comparison.md) — 기술 선택 시 장단점
