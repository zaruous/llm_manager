# LLM Manager — 프로젝트 가이드

## 프로젝트 개요

JavaFX 기반 LLM 서비스 관리 데스크톱 앱.
- 서비스 등록·시작·중지·설치·로그 모니터링
- 개발 모드 핫 리로드 (FXML/CSS/DEF 파일 감시)
- 기본 제공 서비스 정의 (`lib/def/`)와 사용자 서비스 (`~/llm-services/services.json`) 분리
- LLM 스킬 팩 설치와 외부 디렉토리 스킬·룰 파일 로드

## 주요 경로

| 경로 | 역할 |
|------|------|
| `src/main/java/org/kyj/llmmanager/` | Java 소스 루트 |
| `src/main/resources/org/kyj/llmmanager/` | FXML / CSS / 도움말 리소스 |
| `src/main/resources/llm-skills/` | Claude/Copilot/Cursor/Gemini 스킬 팩 리소스 |
| `lib/def/` | 기본 제공 서비스 JSON (배포 포함) |
| `lib/hotswap/hotswap-agent.jar` | 개발용 HotswapAgent (gitignore) |
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

## 빌드 상태

`./gradlew build` 정상 통과 (2026-06-07 기준).

- HikariCP 5.1.0 + sqlite-jdbc 3.45.2.0 의존성 추가로 `LlmSkillLibraryRepository` 컴파일 해소
- `AppSettings`에 `getSkillLibraryDb*()` getter 추가 완료
- `SkillFile`에 `libraryFileId` 필드 및 getter/setter 추가 완료

현재 UI의 "로드" 탭은 선택 파일을 대상 프로젝트에 복사한다 (DB 저장은 미연결).

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
