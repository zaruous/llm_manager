# LLM Manager — 프로젝트 가이드

## 프로젝트 개요

JavaFX 기반 LLM 서비스 관리 데스크톱 앱.
- 서비스 등록·시작·중지·설치·로그 모니터링
- 개발 모드 핫 리로드 (FXML/CSS/DEF 파일 감시)
- 기본 제공 서비스 정의 (`service-packs/*.yml`)와 사용자 서비스 (`~/llm-services/services.json`) 분리
- LLM 스킬 팩 설치와 외부 디렉토리 스킬·룰 파일 로드
- 플러그인 시스템: Cursor 에이전트 러너 포함

## 주요 경로

| 경로 | 역할 |
|------|------|
| `src/main/java/org/kyj/llmmanager/` | Java 소스 루트 |
| `src/main/resources/org/kyj/llmmanager/` | FXML / CSS / 도움말 리소스 |
| `src/main/resources/llm-skills/` | Claude/Copilot/Cursor/Gemini/Wiki-Agent 스킬 팩 리소스 |
| `plugins/wiki-agent/` | LLM Wiki Agent 플러그인 (manifest + 업스트림 Python 도구) |
| `service-packs/` | 기본 제공 서비스 YAML (배포 포함). `ServicePackLoader`가 로드 |
| `bin/` | 배포·설치 PowerShell 스크립트 |
| `lib/hotswap/hotswap-agent.jar` | 개발용 HotswapAgent (gitignore) |
| `lib/*.jar` | 번들 JAR (gitignore — 100 MB 초과) |
| `~/llm-services/services.json` | 사용자 서비스 목록 (런타임) |
| `~/llm-services/settings.json` | 앱 설정 |
| `~/llm-services/projects.json` | LLM 스킬 설치 히스토리 |

## 실행

```bash
# 프로덕션
./gradlew run

# 개발 모드 (FXML/CSS 핫 리로드 + HotswapAgent)
./gradlew downloadHotswapAgent   # 최초 1회
./gradlew runDev
```

## 배포 (jpackage)

```powershell
# 배포 이미지 생성 (Windows installer .exe)
./gradlew jpackageImage

# 산출물 위치
build/installer/LLMManager/
```

배포 구조:
```
LLMManager/
├── app/
│   ├── lib/           ← 앱 JAR + 의존성
│   ├── bin/           ← PowerShell 스크립트 (*.ps1)
│   └── service-packs/ ← 서비스 YAML 팩
└── runtime/
    └── bin/
        └── java.exe   ← jpackage가 제거하므로 doLast로 복사
```

> **주의**: jpackage는 번들 JRE에서 `java.exe`를 제거한다. `build.gradle`의 `copyJavaExeToRuntime()` 헬퍼가 빌드 JDK에서 `runtime/bin/`으로 복사한다.

## service-packs

`service-packs/*.yml`은 내장 서비스 정의 파일이다. `ServicePackLoader.resolvePacksDir()`이 두 단계로 경로를 탐색한다.

1. 실행 디렉토리의 `service-packs/` (개발 모드)
2. JAR 위치에서 상위 2단계까지 `service-packs/` 탐색 (배포 모드)

각 YAML에는 선택적으로 `groovyScript`를 포함할 수 있다. 스크립트 바인딩:

| 변수 | 설명 |
|------|------|
| `service` | `ServiceDefinition` 인스턴스 (변경 가능) |
| `os` | `"windows"` / `"linux"` / `"mac"` |
| `userHome` | 사용자 홈 디렉토리 |
| `arch` | `"x86_64"` 등 |
| `env(name)` | 환경변수 조회 클로저 |

## 설정 아키텍처

### 설정 3계층

| 계층 | 저장 위치 | 역할 |
|------|-----------|------|
| 전역 앱 설정 | `AppSettings` 필드 (`~/llm-services/settings.json`) | 앱 전체 기본값 (모델명, API 키 등) |
| 플러그인 전역 설정 | `AppSettings.pluginSettings[pluginId][key]` | 플러그인 기본값 — **서비스 신규 등록 시 초기값 주입에만 사용** |
| 서비스 인스턴스 설정 | `ServiceDefinition.argValues` (`~/llm-services/services.json`) | 서비스별 단일 진실 원천 — 등록 후에는 여기만 갱신 |

### 원칙

**1. 플러그인 전역 설정은 생성 시 기본값 전용**

`BuiltinServiceSetupController.setup()`이 서비스 등록 화면을 열 때 `pluginSettings`에서 초기값을 읽어 `argValues`에 주입한다. 등록 완료 후에는 `ServiceDefinition.argValues`가 단일 정보 원천이며, 플러그인 전역 설정을 다시 읽지 않는다.

```
신규 등록 시:  pluginSettings[key]  →  argValues[key]  (초기값 복사)
등록 이후:     argValues[key]  (단일 원천, 서비스 인스턴스마다 독립)
```

**2. 서비스 인스턴스 식별 — `ServiceDefinition.packId`**

- YAML 템플릿 def: `id="<팩명>"`, `packId=null`
- 등록된 인스턴스: `id=UUID`, `packId="<팩명>"`
- `packId`는 `buildDefinition()`에서 `sourceDef.getId()`를 복사해 설정한다
- `ServiceRegistry.findByPackId()`로 동일 팩에서 생성된 인스턴스 목록을 조회한다

**3. 플러그인-서비스 연동 — `linkedServiceType` / `linkedServiceId`**

- `plugin.json` 최상위 `linkedServiceType` 필드로 연동할 서비스 팩을 선언한다
- `PluginManager.getCommands(ServiceRegistry)`는 해당 `packId` 서비스가 **정확히 1개**일 때만 `PluginCommandContribution.linkedServiceId`를 채운다
- 0개 또는 2개 이상이면 `linkedServiceId=null` — 플러그인 전역 설정으로 폴백한다

---

## 현재 구현 기능

`./gradlew build` 정상 통과 (2026-06-24 기준).

| 기능 영역 | 주요 클래스·파일 | 비고 |
|-----------|-----------------|------|
| 서비스 관리 | `ServicePackLoader`, `ServiceRegistry`, `ServiceRunner` | `service-packs/*.yml` 기반 등록·시작·중지·로그 |
| 스킬 팩 설치 | `SkillPackInstaller`, `tools.json` | Claude/Copilot/Cursor/Gemini/Wiki-Agent |
| 플러그인 시스템 | `PluginManager`, `PluginCommandExecutor` | `plugins/` 디렉토리 declarative 플러그인, Cursor 에이전트 러너 |
| LLM Wiki Agent | `plugins/wiki-agent/`, `WikiWorkspaceInitializer` | ingest/query/browse/health; 서비스별 워크스페이스(`argValues`) |
| 시스템 모니터 | `SystemMonitorService` | 관리 메모리(RSS) 게이지, 2초 주기 갱신 |
| 스킬 라이브러리 | `LlmSkillLibraryRepository` | HikariCP + SQLite; UI "로드" 탭은 파일 복사 (DB 미연결) |

**배포 주의사항**: jpackage는 번들 JRE에서 `java.exe`를 제거한다. `build.gradle`의 `copyJavaExeToRuntime()`이 빌드 JDK에서 `runtime/bin/`으로 복사한다. WebView 사용 시 `--add-opens javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED`이 applicationDefaultJvmArgs·startScripts·jpackage 세 곳에 모두 필요하다.

---

## 주석 작성 규칙 (Java)

> 이 프로젝트의 모든 Java 코드는 아래 규칙에 따라 주석을 작성한다.

### 1. 클래스 — Javadoc 필수

```java
/**
 * [한 줄 역할 요약 — 무엇을 하는 클래스인지]
 *
 * [필요 시 두 번째 줄: 동작 방식·제약·협력 객체 설명]
 */
public class FooService { ... }
```

- **반드시 포함**: 이 클래스가 하는 일 (역할)
- **선택 포함**: 생명주기, 스레드 안전성, 외부 의존성

### 2. 필드 — 한 줄 Javadoc

```java
/** 서비스 ID → 인스턴스 맵. 중복 생성 방지를 위해 ConcurrentHashMap 사용. */
private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

/** null이면 미실행 상태. setProcess() 호출 시 pid도 함께 갱신. */
private Process process;
```

- 타입·이름으로 명백한 필드는 왜(WHY)·제약·특이사항 위주로 작성
- 단순 getter용 필드(`private String name;`)는 생략 가능

### 3. 메서드 — Javadoc + @param/@return

```java
/**
 * startCommand에 ArgSpec 값들을 이어붙여 실행 가능한 명령어를 반환한다.
 * BOOLEAN 타입은 true일 때만 플래그를 추가하고, 나머지는 --flag value 형식.
 *
 * @param def 명령어를 조립할 서비스 정의
 * @return 완성된 CLI 명령어 문자열
 */
public static String buildStartCommand(ServiceDefinition def) { ... }
```

- **반드시 포함**: 무엇을 하는지 (1줄), 비자명한 동작 설명
- **반드시 포함**: `@param` (2개 이상일 때), `@return` (void 제외)
- **선택 포함**: `@throws` (체크 예외), 사전조건·사후조건

### 4. 분기 (if / switch) — 의도가 불명확할 때만

```java
// BOOLEAN 타입은 값 없이 플래그만 추가
if ("BOOLEAN".equals(spec.getType())) {
    if ("true".equalsIgnoreCase(value)) sb.append(" ").append(spec.getFlag());
} else {
    sb.append(" ").append(spec.getFlag()).append(" ").append(value);
}
```

```java
// Windows는 taskkill /F로 강제 종료 (destroy()가 자식 프로세스를 남길 수 있음)
if (PlatformUtil.isWindows()) {
    Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/PID", ...});
} else {
    process.destroy();
}
```

- **주석 대상**: OS 분기, 타입 분기, 상태 분기처럼 WHY가 코드에서 안 보이는 경우
- **주석 불필요**: `if (value == null) return;` 처럼 의도가 자명한 가드절

### 5. 반복문 (for / while) — 비자명한 순서·조건만

```java
// stop 후 프로세스가 완전히 종료될 때까지 최대 4초 대기
for (int i = 0; i < 20; i++) {
    if (instance.getStatus() == ServiceStatus.STOPPED) break;
    Thread.sleep(200);
}
```

```java
// 5000줄 초과 시 앞 1000줄 제거 — 메모리 과다 사용 방지
if (logs.size() > 5000) logs.remove(0, 1000);
```

- **주석 대상**: 매직 넘버의 의미, 타임아웃 이유, 순서가 중요한 반복
- **주석 불필요**: `for (ArgSpec spec : def.getArgSpecs())` 같이 단순 순회

### 6. 인라인 주석 — 절제

```java
pb.redirectErrorStream(false);  // stdout/stderr를 별도로 읽어야 색상 구분 가능
```

- 코드 바로 위 또는 끝에 `//`로 작성
- 코드가 이미 말하는 것을 반복하지 않는다
- 라이브러리 제약·회피책·숨겨진 전제조건처럼 독자가 놀랄 수 있는 부분에만 작성

### 요약 판단 기준

| 상황 | 주석 필요? |
|------|-----------|
| 클래스 선언 | **항상** |
| public 메서드 | **항상** |
| private 유틸 메서드 | 로직이 복잡하면 |
| 필드 (자명하지 않은 것) | **항상** |
| if/for (WHY가 자명) | 불필요 |
| if/for (WHY가 불명확) | **필요** |
| 매직 넘버 | **항상** |
| 라이브러리 제약·버그 회피 | **항상** |
