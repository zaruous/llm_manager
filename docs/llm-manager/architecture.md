# LLM Manager — 전체 아키텍처

## 개요

JavaFX 기반 LLM 서비스 관리 데스크톱 앱.  
로컬에서 실행하는 AI 서비스(임베딩 서버, MCP 서버 등)의 등록·시작·중지·로그 모니터링을 단일 UI로 제공한다.

---

## 레이어 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                          UI Layer                               │
│  MainController  DashboardController  LlmSkillsController       │
│  각종 Dialog / Cell                                             │
├─────────────────────────────────────────────────────────────────┤
│                    AppContext (싱글톤)                           │
│          모든 서비스 객체를 생성·보관·UI에 제공                  │
├──────────────┬───────────────────┬──────────────────────────────┤
│  Process     │  Persistence      │  Support Services            │
│  ProcessManager                  │  LogService                  │
│  HealthMonitor                   │  InstallationService         │
│  PidFileManager                  │  LlmSkillInstaller           │
│              │ ServiceRegistry (services.json)                  │
│              │ AppSettingsRepository (settings.json)            │
│              │ ProjectRegistry (projects.json)                  │
│              │  SystemMonitorService                            │
│              │  EmbeddedApiServer                               │
│              │  ServiceCustomizer (Groovy)                      │
│              │  ServicePackLoader (YAML)                        │
├──────────────┴───────────────────┴──────────────────────────────┤
│                        Model Layer                              │
│  ServiceDefinition  ServiceInstance  ArgSpec                    │
│  RuntimeType  ServiceStatus  AppSettings  SkillPack             │
│  LlmTool  ProjectConfig  SkillFile  LoadFileEntry               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 앱 시작 순서

```
main()
  ├─ AppConfigLoader.parseArgs()     ← CLI 인수 파싱 (--key=value 형식)
  ├─ AWT Toolkit 초기화             ← SystemTray 안정성 확보
  └─ JavaFX launch()
        │
        ▼
LlmManagerApp.start()
  ├─ NordDark 테마 적용
  ├─ AppContext.init()
  │     ├─ AppSettingsRepository.load()   ← ~/llm-services/settings.json
  │     ├─ AppConfigLoader.applyCli()     ← CLI 오버라이드 (최우선 순위)
  │     ├─ ServiceRegistry.load()         ← ~/llm-services/services.json
  │     ├─ BuiltinServiceLoader 생성      ← lib/def/*.json (지연 로드)
  │     ├─ LogService / ProcessManager 생성
  │     ├─ PidFileManager 생성            ← PID 파일 기반 고아 프로세스 추적
  │     ├─ InstallationService 생성
  │     ├─ HealthMonitor.start()          ← intervalSeconds 주기 헬스체크 (기본 10초)
  │     ├─ LlmSkillInstaller / ProjectRegistry.load()
  │     ├─ SystemTrayManager 설치
  │     ├─ SystemMonitorService.start()   ← CPU·메모리 수집
  │     └─ EmbeddedApiServer.start()      ← 설정에서 활성화된 경우만
  ├─ SceneFactory.init(cssUrl)       ← 전역 CSS 등록
  ├─ main.fxml 로드 → MainController.initializeContext(ctx)
  ├─ DevHotReloader.start()          ← 개발 모드만: FXML/CSS/DEF 감시
  └─ Stage.show() + SystemTray 설치
```

---

## 핵심 컴포넌트

### AppContext
앱 전역 싱글톤. 모든 서비스 객체의 생성·보관을 담당하며 UI 레이어에 의존성을 제공한다.  
`AppContext.getInstance()`로 어디서든 접근 가능.

### ProcessManager
서비스 프로세스의 생명주기를 관리한다.

- `start()` — 별도 스레드에서 `ProcessBuilder`로 프로세스 실행, stdout/stderr를 `LogService`에 연결
- `stop()` — Windows: `taskkill /F`, Unix: `destroy()` → 5초 대기 후 강제 종료
- `restart()` — stop 완료 대기 후 start 재호출
- `instances` — `ConcurrentHashMap<serviceId, ServiceInstance>`로 인스턴스 관리
- `onStatusChange` 콜백 — 상태 변경 시 트레이 메뉴 갱신

### ServiceRegistry
`~/llm-services/services.json` 영속 저장소.
`add()` / `remove()` / `update()` 호출 시마다 즉시 파일에 저장한다.

### HealthMonitor
설정된 주기(기본 10초, 1~10초 범위)로 등록된 서비스의 `healthCheckPath`에 HTTP GET을 보내 응답 여부로 상태를 갱신한다.  
앱 시작 후 최초 1회에 한해 PID 파일을 스캔해 고아 프로세스(앱 재시작 전 실행 중이던 서비스)를 감지하고 RUNNING 상태로 복원한다.  
OSHI를 통해 각 RUNNING 서비스의 RSS·가상 메모리도 주기적으로 수집한다.

### LogService
`ProcessBuilder`의 stdout/stderr 스트림을 별도 스레드에서 읽어 내부 큐에 누적한다.
100ms 주기 `log-flusher` 스레드가 큐를 감시하여 한 번에 최대 1000건을 JavaFX 스레드에서 일괄 반영한다.
로그 폭주 시 `Platform.runLater()`가 과도하게 쌓이는 문제를 해소한다.

- 스트림 디코딩 charset: `ServiceDefinition.logCharset`에 지정된 값(빈 값이면 시스템 기본값)으로 처리
- 5000행 초과 시 앞 1000행을 제거하는 trim 로직을 `LogService.trimBeforeAdd()`가 담당
- 로그 항목은 `ObservableList<LogEntry>`로 UI와 실시간 바인딩된다

### PidFileManager
서비스 시작 시 PID를 파일로 저장하고 종료 시 삭제한다.  
`HealthMonitor`가 첫 번째 체크 시 이 파일을 읽어 앱 재시작 후에도 실행 중인 서비스를 인식한다.

### ServiceCustomizer
Groovy 스크립트를 통해 서비스 정의를 런타임에 수정한다.  
`AddServiceController`에서 스크립트를 작성·미리보기 후 저장 시 자동 적용된다.

### ServicePackLoader
YAML 형식의 서비스 팩 파일을 읽어 `ServiceDefinition` 목록으로 변환한다.  
`AddServiceController`의 "YAML 가져오기" 기능에서 사용한다.

### EmbeddedApiServer
Javalin 기반 내장 REST API 서버. 설정에서 활성화 시 기동.  
외부 도구(Claude Code 등)에서 서비스 목록·상태를 HTTP로 조회할 수 있다.

### SystemMonitorService
OSHI 라이브러리로 CPU 사용률·물리 메모리를 수집한다.  
`MainController`의 2초 주기 `Timeline`이 수집값을 읽어 대시보드 게이지에 표시한다.

### LlmSkillInstaller
`src/main/resources/llm-skills/tools.json`에 정의된 AI 도구별 스킬 팩을 읽고 프로젝트 디렉토리에 파일을 설치한다.
지원 도구는 Cursor IDE, Claude Code, Gemini CLI, GitHub Copilot이다. 템플릿 파일은 `{{projectName}}`, `{{language}}`, `{{author}}` 변수를 치환한 뒤 저장한다.

### LlmSkillsLoadController
외부 디렉토리를 재귀 스캔해 `.md`, `.mdc`, `.json`, `.yaml`, `.yml`, `.txt`, `.toml`, `.xml` 파일을 선택 목록으로 표시한다.
선택 파일은 상대 경로를 유지해 대상 프로젝트 디렉토리로 복사하며, `.git`, `node_modules`, `target`, `build`, `.gradle`, `.idea`, `.llm-backup*` 경로는 제외한다.

### LlmSkillLibraryRepository
사용자가 로드한 스킬·룰 파일을 DB에 저장하기 위한 저장소 구현이다. SQLite, PostgreSQL, Oracle, MSSQL provider 분기를 포함한다.
HikariCP 5.1.0 + sqlite-jdbc 3.45.2.0 의존성, `AppSettings` DB 설정 필드, `SkillFile.libraryFileId` 모델 필드가 모두 추가되어 컴파일은 통과한다.
단, `AppContext`나 UI에서 아직 사용되지 않는다 — DB 기반 스킬 라이브러리 연결은 남아 있는 TODO다.

### DevHotReloader
`-Pdev` 플래그로 실행 시 활성화.  
`src/main/resources/` 하위의 `.fxml`, `.css`, `lib/def/*.json`을 WatchService로 감시하고  
변경 감지 시 FXML은 Scene 전체 재로드, CSS는 스타일시트만 교체한다.

---

## UI 구조

```
main.fxml (MainController)
  ├─ 좌측: ListView<ServiceInstance>  ← 검색 필터 포함, ServiceListCell 렌더링
  ├─ 중앙: dashboardPane (BorderPane)  ← 서비스 미선택 시 기본
  │     ├─ 요약 레이블 (전체/실행중/오류)
  │     ├─ 시스템 리소스 패널 (CPU/메모리/JVM 게이지)
  │     └─ FlowPane  ← 서비스 카드 목록 (메모리 바 + Canvas 스파크라인 포함)
  ├─ 중앙: detailTabPane (TabPane)     ← 서비스 선택 시 표시
  │     ├─ 개요 탭  (상태·PID·포트·업타임·시작/중지/재시작)
  │     ├─ 로그 탭  (실시간 로그·필터·자동 스크롤·인코딩 선택)
  │     ├─ 설정 탭  (argValues·envVars 편집·저장)
  │     └─ 설치 탭  (git clone·의존성 설치·진행률)
  └─ 팝업 다이얼로그
        ├─ BuiltinServicesController  ← builtin 서비스 선택
        ├─ BuiltinServiceSetupDialog  ← builtin 최적화 설정
        ├─ AddServiceDialog           ← 서비스 추가/수정 (YAML 가져오기·Groovy 커스터마이징 포함)
        ├─ ServiceDetailDialog        ← 서비스 상세·수정
        ├─ SettingsDialog             ← 앱 설정
        └─ HelpDialog                 ← 도움말

dashboard.fxml (DashboardController)
  ├─ 서비스 카드 목록 (FlowPane)
  │     ├─ 상태 점 + 이름 + 상태 텍스트 + 포트
  │     ├─ 메모리 ProgressBar + 수치 레이블
  │     └─ Canvas 스파크라인 (최근 20개 RSS 샘플)
  └─ 전체 시작 / 전체 중지 / 새로고침 버튼

llm-skills.fxml (LlmSkillsController)
  ├─ 설치 탭 (LlmSkillsInstallController)
  │     ├─ tools.json 기반 도구/팩 선택
  │     ├─ 설치 대상 미리보기
  │     ├─ 기존 파일 .llm-backup/<timestamp>/ 백업 후 설치
  │     └─ projects.json 설치 히스토리 저장
  └─ 로드 탭 (LlmSkillsLoadController)
        ├─ 외부 디렉토리 스캔
        ├─ 스킬·룰 파일 선택 및 내용 미리보기
        └─ 선택 파일을 대상 프로젝트로 상대 경로 유지 복사
```

---

## 설정 우선순위

```
CLI 인수 (--key=value)
  └─▷ settings.json (GUI 저장, ~/llm-services/settings.json)
        └─▷ application.yml (배포 기본값)
```

주요 CLI 오버라이드 키:

| CLI 키 | 설명 |
|--------|------|
| `--api.server.enabled=true` | 내장 API 서버 활성화 |
| `--api.server.port=9090` | API 서버 포트 |
| `--runtime.python=python3` | Python 실행 명령어 |
| `--runtime.java-home=C:\Java\jdk21` | JAVA_HOME |
| `--install.base=D:\llm-services` | 서비스 기본 설치 루트 |
| `--monitor.health-check-interval=5` | 헬스체크 주기 (초) |
| `--skill-library.db.provider=postgresql` | 스킬 라이브러리 DB 종류 (sqlite/postgresql/oracle/mssql) |
| `--skill-library.db.url=jdbc:...` | 스킬 라이브러리 JDBC URL |
| `--skill-library.db.username=...` | 스킬 라이브러리 DB 사용자명 |
| `--skill-library.db.password=...` | 스킬 라이브러리 DB 비밀번호 |

---

## 스레딩 모델

| 스레드 | 역할 |
|--------|------|
| JavaFX Application Thread | UI 렌더링, 이벤트 처리. `Platform.runLater()`로만 UI 접근 |
| `start-{name}` | 서비스 프로세스 실행 및 종료 코드 대기 |
| `stop-{name}` | 프로세스 종료 처리 (taskkill / destroy) |
| `log-stdout-{name}` / `log-stderr-{name}` | 프로세스 출력 스트리밍 → pendingLogs 큐에 적재 |
| `log-flusher` | 100ms 주기로 pendingLogs 큐를 드레인 → JavaFX 스레드에 일괄 반영 |
| `uptime-timer` | 1초 주기 업타임 레이블 갱신 |
| `health-monitor` | intervalSeconds(기본 10초) 주기 HTTP 헬스체크 + 메모리 수집 |
| `system-monitor` | CPU·메모리 수집 |

---

## 데이터 영속성

모든 런타임 설정 파일은 `~/llm-services/` 하위에 통일 관리된다 (`PlatformUtil.getAppHome()`).

| 파일 | 관리 클래스 | 내용 |
|------|------------|------|
| `~/llm-services/services.json` | `ServiceRegistry` | 사용자가 추가한 서비스 정의 목록 |
| `~/llm-services/settings.json` | `AppSettingsRepository` | 앱 설정 (API 서버 포트, 런타임 명령어, 헬스체크 주기 등) |
| `~/llm-services/projects.json` | `ProjectRegistry` | LLM 스킬 설치 프로젝트 목록 |
| `~/llm-services/app.log` | Logback | 앱 구동 로그 (7일 롤링) |

### 경로 변수 (`PlatformUtil.resolvePath`)

`lib/def/*.json`의 `installDir` 등 경로 필드에 변수를 사용할 수 있다.

| 변수 | 치환값 |
|------|--------|
| `${user.home}` | 사용자 홈 디렉토리 |
| `${llm.home}` | `~/llm-services` (앱 홈) |

팝업 표시 전 `BuiltinServiceSetupController`가 `resolvePath()`를 호출해 절대 경로로 변환하며, `services.json`에는 치환된 절대 경로가 저장된다.

---

## 번들 MCP 서버

`lib/` 디렉토리에 실행 가능한 MCP 서버 JAR이 포함된다.

| 파일 | 설명 |
|------|------|
| `lib/sql-gen-mcp-1.0.0-SNAPSHOT.jar` | SQL 생성 MCP 서버 |
| `lib/swagger-mcp-server.jar` | Swagger/OpenAPI MCP 서버 |

---

## LLM 스킬 리소스

`src/main/resources/llm-skills/` 하위에 AI 도구별 스킬 파일이 포함된다.

```
llm-skills/
  ├─ claude/commands/       ← Claude Code 슬래시 커맨드
  ├─ copilot/               ← GitHub Copilot 스킬
  ├─ cursor/rules/          ← Cursor 규칙
  └─ gemini/                ← Gemini 스킬
```

`LlmSkillInstaller`가 선택한 AI 도구의 스킬 파일을 프로젝트 디렉토리에 설치한다.
로드 탭은 별도 내장 리소스가 아니라 사용자가 선택한 외부 디렉토리의 파일을 대상 프로젝트로 복사한다.

---

## 빌드 상태 (2026-06-07)

`./gradlew build` 정상 통과.

이전에 빌드를 막던 세 가지 문제가 모두 해소되었다.

| 해소 항목 | 변경 내용 |
|----------|----------|
| Gradle 의존성 | HikariCP 5.1.0 + sqlite-jdbc 3.45.2.0 추가 |
| `AppSettings` | `getSkillLibraryDb*()` / `setSkillLibraryDb*()` getter·setter 추가 |
| `SkillFile` | `libraryFileId` 필드 + getter/setter 추가 |

현재 실행 가능한 UI 흐름은 `LlmSkillsInstallController`의 내장 팩 설치와 `LlmSkillsLoadController`의 파일 복사 로드이다. DB 기반 스킬 라이브러리(`LlmSkillLibraryRepository`)는 `AppContext` 및 UI 연결 작업이 남아 있다.

---

## 개발 모드 (`./gradlew runDev`)

```
-Pdev 플래그 감지
  └─ DevHotReloader.start()
        ├─ WatchService: src/main/resources/**/*.fxml, *.css
        │    변경 감지 → applyScene() 재호출 (Scene 전체 재생성)
        ├─ WatchService: src/main/resources/**/*.css
        │    변경 감지 → reloadCss() (스타일시트만 교체)
        └─ WatchService: lib/def/*.json
             변경 감지 → BuiltinServiceLoader 캐시 무효화
```

`-javaagent:hotswap-agent.jar` 병행 사용 시 Java 클래스 변경도 재시작 없이 반영 가능.  
단, `@FXML` 필드가 변경된 경우에는 앱 재시작 필요.
