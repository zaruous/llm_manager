# LLM Manager — 전체 아키텍처

## 개요

JavaFX 기반 LLM 서비스 관리 데스크톱 앱.  
로컬에서 실행하는 AI 서비스(임베딩 서버, MCP 서버 등)의 등록·시작·중지·로그 모니터링을 단일 UI로 제공한다.

---

## 레이어 구조

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                           │
│  MainController  DashboardController  각종 Dialog/Cell  │
├─────────────────────────────────────────────────────────┤
│                   AppContext (싱글톤)                    │
│         모든 서비스 객체를 생성·보관·UI에 제공           │
├──────────────┬──────────────┬───────────────────────────┤
│  Process     │  Persistence │  Support Services         │
│  ProcessManager             │  LogService               │
│  HealthMonitor              │  InstallationService      │
│              │ ServiceRegistry (services.json)          │
│              │ AppSettingsRepository (settings.json)    │
│              │ ProjectRegistry (projects.json)          │
│              │  SystemMonitorService                    │
│              │  EmbeddedApiServer                      │
│              │  LlmSkillInstaller                      │
├──────────────┴──────────────┴───────────────────────────┤
│                     Model Layer                         │
│  ServiceDefinition  ServiceInstance  ArgSpec            │
│  RuntimeType  ServiceStatus  AppSettings  SkillPack     │
└─────────────────────────────────────────────────────────┘
```

---

## 앱 시작 순서

```
main()
  ├─ AppConfigLoader.parseArgs()     ← CLI 인수 파싱 (dev 모드 플래그 등)
  ├─ AWT Toolkit 초기화             ← SystemTray 안정성 확보
  └─ JavaFX launch()
        │
        ▼
LlmManagerApp.start()
  ├─ NordDark 테마 적용
  ├─ AppContext.init()
  │     ├─ AppSettingsRepository.load()   ← settings.json
  │     ├─ ServiceRegistry.load()         ← services.json
  │     ├─ BuiltinServiceLoader 생성      ← lib/def/*.json (지연 로드)
  │     ├─ LogService / ProcessManager 생성
  │     ├─ InstallationService 생성
  │     ├─ HealthMonitor.start()          ← 30초 주기 HTTP 헬스체크
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
`~/.llm-manager/services.json` 영속 저장소.  
`add()` / `remove()` / `update()` 호출 시마다 즉시 파일에 저장한다.

### HealthMonitor
30초 주기로 등록된 서비스의 `healthCheckPath`에 HTTP GET을 보내 응답 여부로 상태를 갱신한다.

### LogService
`ProcessBuilder`의 stdout/stderr 스트림을 별도 스레드에서 읽어 `ServiceInstance.logs`에 추가한다.  
로그 항목은 `ObservableList<LogEntry>`로 UI와 실시간 바인딩된다.

### EmbeddedApiServer
Javalin 기반 내장 REST API 서버. 설정에서 활성화 시 기동.  
외부 도구(Claude Code 등)에서 서비스 목록·상태를 HTTP로 조회할 수 있다.

### SystemMonitorService
OSHI 라이브러리로 CPU 사용률·물리 메모리를 수집한다.  
`MainController`의 2초 주기 `Timeline`이 수집값을 읽어 대시보드 게이지에 표시한다.

### DevHotReloader
`-Pdev` 플래그로 실행 시 활성화.  
`src/main/resources/` 하위의 `.fxml`, `.css`, `lib/def/*.json`을 WatchService로 감시하고  
변경 감지 시 FXML은 Scene 전체 재로드, CSS는 스타일시트만 교체한다.

---

## UI 구조

```
main.fxml (MainController)
  ├─ 좌측: ListView<ServiceInstance>  ← 검색 필터 포함
  ├─ 중앙: dashboardPane (BorderPane)  ← 서비스 미선택 시 기본
  │     ├─ 요약 레이블 (전체/실행중/오류)
  │     ├─ 시스템 리소스 패널 (CPU/메모리/JVM)
  │     └─ FlowPane  ← 서비스 카드 목록
  ├─ 중앙: detailTabPane (TabPane)     ← 서비스 선택 시 표시
  │     ├─ 개요 탭  (상태·PID·포트·업타임·시작/중지/재시작)
  │     ├─ 로그 탭  (실시간 로그·필터·자동 스크롤)
  │     ├─ 설정 탭  (argValues·envVars 편집·저장)
  │     └─ 설치 탭  (git clone·의존성 설치·진행률)
  └─ 팝업 다이얼로그
        ├─ BuiltinServicesController  ← builtin 서비스 선택
        ├─ BuiltinServiceSetupDialog  ← builtin 최적화 설정
        ├─ AddServiceDialog           ← 범용 서비스 추가/수정
        ├─ ServiceDetailDialog        ← 서비스 상세·수정
        └─ SettingsDialog             ← 앱 설정
```

---

## 스레딩 모델

| 스레드 | 역할 |
|--------|------|
| JavaFX Application Thread | UI 렌더링, 이벤트 처리. `Platform.runLater()`로만 UI 접근 |
| `start-{name}` | 서비스 프로세스 실행 및 종료 코드 대기 |
| `stop-{name}` | 프로세스 종료 처리 (taskkill / destroy) |
| `log-stdout-{name}` / `log-stderr-{name}` | 프로세스 출력 스트리밍 |
| `uptime-timer` | 1초 주기 업타임 레이블 갱신 |
| `health-monitor` | 30초 주기 HTTP 헬스체크 |
| `system-monitor` | CPU·메모리 수집 |

---

## 데이터 영속성

모든 파일은 `~/llm-services/` 하위에 통일 관리된다 (`PlatformUtil.getAppHome()`).

| 파일 | 관리 클래스 | 내용 |
|------|------------|------|
| `~/llm-services/services.json` | `ServiceRegistry` | 사용자가 추가한 서비스 정의 목록 |
| `~/llm-services/settings.json` | `AppSettingsRepository` | 앱 설정 (테마, API 서버 포트 등) |
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
