# 개발 작업 메모리

> 이 문서는 주요 작업 내역·기술 결정·삽질 기록을 정리한다.  
> 다음 세션에서 맥락 파악용으로 활용.

---

## 세션 1 (2026-06-03)

### 작업 목록

| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | 아키텍처 문서 최신화 | `docs/llm-manager/architecture.md` | ✅ |
| 2 | 설정 탭 GridPane 정렬 | `MainController.java` | ✅ |
| 3 | 로그 탭 5000행 표시 제한 | `MainController.java` | ✅ |
| 4 | GitHub 초기 push (대용량 JAR 제거) | `.gitignore`, git history | ✅ |
| 5 | jpackage 빌드 태스크 추가 | `build.gradle` | ✅ |
| 6 | WiX 자동 다운로드 스크립트 | `bin/Download-WiX.ps1` | ✅ |
| 7 | buildExe.ps1 빌드 파이프라인 | `bin/buildExe.ps1` | ✅ |
| 8 | git tag v1.0.0 생성 | — | ✅ |
| 9 | README.md 작성 + 스크린샷 추가 | `README.md` | ✅ |
| 10 | 패키징 가이드 문서 | `docs/패키징.md` | ✅ |

---

## 코드 변경 상세

### MainController.java — buildArgForm() GridPane 전환

**변경 이유**: 설정 탭에서 ArgSpec 행마다 HBox를 사용하면 라벨·입력필드·설명 열이
행마다 제각각 정렬되는 문제.

**변경 내용**: `argsContainer` 자식으로 행별 HBox 대신 단일 GridPane 사용.

```
0열(라벨) : min/pref/max = 140px, NEVER grow
1열(컨트롤): min/pref/max = 200px, NEVER grow
2열(설명)  : ALWAYS grow, wrapText = true
```

---

### MainController.java — 로그 5000행 제한

**변경 이유**: TextArea는 무제한 누적 → 장시간 실행 서비스에서 메모리 과다 사용.

**구현**:
- `private int logLineCount = 0` 필드 추가
- `appendLogEntry()`: 행 추가 시 카운트 증가, 5000 초과 시 앞 1000행 제거(`logArea.getText()` → `substring`)
- `refreshLogs()`, `onClear()`: 카운트 리셋

> `ServiceInstance.addLog()`는 이미 5000행 제한이 있었지만 TextArea는 별도 처리 필요.

---

## GitHub push — 대용량 JAR 문제

### 문제

`lib/sql-gen-mcp-1.0.0-SNAPSHOT.jar` (239MB), `lib/swagger-mcp-server.jar` (254MB)가  
이미 초기 커밋에 포함되어 GitHub 100MB 제한 초과로 push 거절.

### 해결

```powershell
# git history 에서 JAR 제거
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch --index-filter \
  "git rm --cached --ignore-unmatch lib/sql-gen-mcp-*.jar lib/swagger-mcp-server.jar" \
  --prune-empty -- --all
```

`.gitignore` 수정: `lib/*.jar` 패턴 추가.

> 번들 JAR은 빌드 전 수동으로 `lib/` 에 배치 필요.

---

## jpackage 빌드 태스크

### 태스크 구조

```
prepareJpackageInput (Copy)
  └─ installDist 결과물(JARs + def/) → build/jpackage-input/

jpackageImage (Exec)
  └─ jpackage --type app-image → build/installer/LLMManager/

jpackageExe (Exec)
  └─ jpackage --type exe → build/installer/LLMManager-x.x.x.exe
```

### BuiltinServiceLoader 경로 문제

`BuiltinServiceLoader`가 JAR 위치 기준 `../def/` (= JAR 옆 `def/` 디렉토리)를 탐색한다.  
`installDist`의 `lib/` 구조가 JAR + `def/` 를 같은 레벨에 두므로 별도 처리 없이 호환.

---

## WiX 관련 삽질 기록

### 오류 1: light.exe exit 311 (한글 description)

**증상**: `jpackageExe` 실행 시 `light.exe ... exited with 311 code`

**원인**: `--description`에 한글(`LLM 서비스 관리 데스크톱 앱`)을 넣으면  
Gradle JVM → jpackage → candle.exe 프로세스 간 문자열 전달 시 인코딩이 깨져  
WiX 소스가 오염됨 → light.exe 링크 실패.

**해결**: `--description "LLM Service Manager"` (영문으로 변경)

```groovy
// build.gradle — buildJpackageArgs()
'--description', 'LLM Service Manager',  // 영문 필수, 한글 금지
```

> `--vendor`, `--win-menu-group` 등 다른 옵션의 한글도 동일 문제 발생 가능. 영문 권장.

### 오류 2: PowerShell 5.1 파싱 오류 (UTF-8 BOM)

**증상**: 한글이 포함된 `.ps1` 파일을 `powershell.exe`(5.1)로 실행 시  
`Missing expression after unary operator '+'` 파싱 오류.

**원인**: PowerShell 5.1은 BOM 없는 UTF-8 파일의 한글을 시스템 인코딩(CP949 등)으로  
잘못 읽어 문자열 경계가 어긋남.

**해결**: 스크립트 파일을 **UTF-8 with BOM**으로 저장.

```powershell
$content = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($true))
```

> `pwsh`(PowerShell 7)는 BOM 없이도 UTF-8 정상 처리.

### 오류 3: bin/ .gitignore 예외 처리

**문제**: `.gitignore`에 `bin/`(디렉토리 전체 제외)가 있으면  
`!bin/*.ps1` 예외가 동작하지 않음.

**해결**: `bin/` → `bin/**` 으로 변경 (디렉토리 자체는 추적, 내용만 기본 제외).

```gitignore
bin/**
!bin/*.ps1
```

---

## bin/buildExe.ps1 파라미터 구조

```powershell
# 기본: 실행 파일 (app-image ZIP, WiX 불필요)
.\bin\buildExe.ps1

# 설치 파일 (EXE 인스톨러, WiX 자동 다운로드)
.\bin\buildExe.ps1 -Installer
```

| 모드 | Gradle 태스크 | 출력 | WiX |
|------|--------------|------|-----|
| 기본 | `jpackageImage` | `deploy\LLMManager-x.x.x.zip` | 불필요 |
| `-Installer` | `jpackageExe` | `deploy\LLMManager-x.x.x.exe` | 자동 다운로드 |

---

## 현재 파일 구조 (주요 추가/변경)

```
llm_manager/
├── bin/
│   ├── buildExe.ps1          ← 패키지 빌드 파이프라인 (UTF-8 BOM 필수)
│   └── Download-WiX.ps1      ← WiX 3.14.1 자동 다운로드 (UTF-8 BOM 필수)
├── deploy/                   ← 빌드 결과물 (Git 미포함, .gitignore)
│   └── WiX/                  ← WiX 바이너리 (Git 미포함, .gitignore)
├── docs/
│   ├── llm-manager/
│   │   ├── architecture.md   ← 최신화 완료
│   │   └── MAIN.png          ← 메인 화면 스크린샷
│   └── 패키징.md              ← 빌드 가이드
├── README.md                 ← 신규 작성
└── build.gradle              ← jpackage 태스크 추가
```

---

## 알려진 이슈 / TODO

- [ ] `lib/*.jar` Git 미포함 → 빌드 전 수동 배치 필요. Git LFS 도입 고려
- [ ] `--description` 등 jpackage 옵션은 영문만 사용 가능 (한글 인코딩 버그)
- [ ] `buildExe.ps1` 재시도 로직: Windows Defender 간섭 시 최대 2회 재시도
- [ ] 아이콘 파일 미설정 (`packaging/windows/LLMManager.ico` 추가 필요)
- [ ] `dev.md`, `,gitignore` 루트 미정리 파일 존재

---

## 세션 2 (2026-06-07)

### 코드 분석 및 문서 최신화

| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | 앱 홈 경로 문서 정정 (`~/.llm-manager` → `~/llm-services`) | `README.md`, `docs/llm-manager/architecture.md`, `CLAUDE.md` | ✅ |
| 2 | LLM 스킬 설치/로드 탭 흐름 문서화 | `README.md`, `docs/llm-manager/architecture.md`, `docs/llm-manager/service-configuration-flow.md` | ✅ |
| 3 | 현재 빌드 실패 원인 기록 | `README.md`, `docs/llm-manager/architecture.md`, `CLAUDE.md` | ✅ |

### 확인된 빌드 실패

`./gradlew build` 실행 결과 `LlmSkillLibraryRepository`에서 컴파일 실패.

| 원인 | 세부 내용 |
|------|----------|
| 의존성 누락 | `com.zaxxer.hikari.HikariConfig`, `HikariDataSource` 패키지를 찾지 못함 |
| 설정 모델 미연결 | `AppSettings`에 `getSkillLibraryDbProvider()`, `getSkillLibraryDbUrl()` 등 getter가 없음 |
| 모델 필드 미연결 | `SkillFile`에 `setLibraryFileId(long)`가 없음 |

`LlmSkillLibraryRepository`는 DB 기반 스킬 라이브러리 저장소로 보이지만 현재 `AppContext`나 UI 컨트롤러에서 사용되지 않는다. 현재 UI의 로드 탭은 선택 파일을 대상 프로젝트에 상대 경로 유지 복사한다.

---

## 세션 3 (2026-06-07)

### 작업 목록

| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | 빌드 실패 해소 (HikariCP + sqlite-jdbc 추가) | `build.gradle` | ✅ |
| 2 | AppSettings — skill library DB 설정 getter/setter 추가 | `AppSettings.java` | ✅ |
| 3 | SkillFile — libraryFileId 필드 추가 | `SkillFile.java` | ✅ |
| 4 | AppConfigLoader — skill-library.db YAML/CLI 파싱 추가 | `AppConfigLoader.java` | ✅ |
| 5 | LogService — 큐 + 주기적 플러셔 방식 전면 개선 | `LogService.java` | ✅ |
| 6 | ServiceDefinition — logCharset 필드 추가 | `ServiceDefinition.java` | ✅ |
| 7 | 로그 탭 인코딩 선택 ComboBox 추가 | `MainController.java`, `main.fxml` | ✅ |
| 8 | AppContext shutdown — logService.shutdown() 추가 | `AppContext.java` | ✅ |
| 9 | sql-gen-mcp.json — TEI/Ollama/OpenAI/vLLM/Chroma ArgSpec 추가 | `lib/def/sql-gen-mcp.json` | ✅ |
| 10 | swagger-mcp.json — Ollama/TEI/Chroma/pgvector/CodebaseMemory ArgSpec 추가 | `lib/def/swagger-mcp.json` | ✅ |
| 11 | 문서 최신화 | `CLAUDE.md`, `architecture.md`, `service-configuration-flow.md`, `memory.md` | ✅ |

### 변경 상세

#### LogService 개선 — 큐 + 주기적 플러셔

**변경 이유**: 로그 폭주 시 `Platform.runLater()`가 행 수만큼 쌓여 JavaFX Application Thread를 과부하.

**구현**:
- `ConcurrentLinkedQueue<QueuedLog>` — reader 스레드가 여기에 적재
- `ScheduledExecutorService flusher` — 100ms 주기로 큐 감시
- `scheduleFlush()` — `AtomicBoolean flushScheduled`로 중복 runLater 방지
- `flushNow()` — 한 번에 최대 1000건 처리, 인스턴스별로 묶어 `addAll()` 호출
- `trimBeforeAdd()` — 5000행 초과 시 1000행 제거 (기존 `ServiceInstance` 책임을 LogService로 이관)
- `resolveCharset()` — `ServiceDefinition.logCharset` 값으로 스트림 디코딩 charset 결정

#### 서비스별 로그 인코딩 선택

**변경 이유**: Windows 환경에서 Python/Java 프로세스가 시스템 코드페이지(CP949)로 출력하는 경우 한글이 깨지는 문제.

**구현**:
- `ServiceDefinition.logCharset` 필드 추가 (JSON 직렬화 포함)
- `main.fxml` 로그 탭 툴바에 `logEncodingCombo` ComboBox 추가
- 선택지: 시스템 기본값 / UTF-8 / MS949 / EUC-KR / ISO-8859-1
- 변경 즉시 `ServiceRegistry.update(def)`로 services.json에 저장
- 이미 실행 중인 서비스는 다음 시작부터 적용 (시스템 로그 메시지로 안내)

#### 서비스 정의 ArgSpec 확장

| 파일 | 추가 ArgSpec 카테고리 |
|------|----------------------|
| `sql-gen-mcp.json` | TEI model-name/api-key, 임베딩 batch-size, Ollama/OpenAI/vLLM 임베딩 provider 설정, ChromaDB 컬렉션·테넌트·데이터베이스 설정 |
| `swagger-mcp.json` | Ollama/TEI 임베딩 provider 설정, memory 스토어 파일 경로, Chroma/pgvector 벡터 스토어 설정, Codebase Memory MCP URL·타임아웃 설정 |

모든 신규 ArgSpec은 `enabled: false`로 기본 비활성화.
