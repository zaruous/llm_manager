# LLM Manager — 서비스 설정 흐름

## 서비스 정의 파일 3계층

LLM Manager는 서비스 정의를 **세 곳**에 나눠 관리한다.

```
lib/def/*.json               ← 1) 앱 내장 템플릿 (배포 포함, 읽기 전용)
service-packs/*.yml          ← 2) 사용자 커스텀 템플릿 (수동 임포트)
~/llm-services/services.json ← 3) 실제 등록된 서비스 목록 (앱이 자동 저장)
```

### 1. `lib/def/*.json` — 내장 서비스 템플릿

- **역할**: 앱이 기본 제공하는 서비스 정의 카탈로그
- **로드**: 앱 시작 시 `BuiltinServiceLoader`가 자동으로 읽어 "서비스 추가" 팝업에 표시
- **배포**: Gradle `distributions` 태스크로 배포 패키지에 포함됨
- **형식**: JSON (Jackson `ObjectMapper`로 `ServiceDefinition`에 역직렬화)
- **수정 주체**: 앱 개발자

현재 제공 파일:

| 파일 | 서비스 | 설치 방식 | 주요 ArgSpec |
|------|--------|-----------|-------------|
| `bge-m3.json` | BGE-M3 Embedding Server (Python/FastAPI) | git clone + pip install | — |
| `sql-gen-mcp.json` | SQL Gen MCP Server (Java) | `lib/` 번들 JAR 복사 | TEI/Ollama/OpenAI/vLLM 임베딩 provider, ChromaDB 벡터 스토어 |
| `swagger-mcp.json` | Swagger MCP Server (Java) | `lib/` 번들 JAR 복사 | Ollama/TEI 임베딩 provider, Chroma/pgvector 벡터 스토어, Codebase Memory MCP 연동 |

#### 경로 변수 (`installDir` 필드)

`lib/def/*.json`의 `installDir`에는 경로 변수를 사용할 수 있다.
`PlatformUtil.resolvePath()`가 팝업 표시 전에 실제 값으로 치환한다.

| 변수 | 치환값 |
|------|--------|
| `${user.home}` | 사용자 홈 디렉토리 (예: `C:\Users\KYJ`) |
| `${llm.home}` | LLM Manager 앱 홈 (`~/llm-services`) |

예시:
```json
"installDir": "${user.home}/llm-services/sql-gen-mcp"
→ C:\Users\KYJ\llm-services\sql-gen-mcp
```

#### `lib/*.jar` — 번들 JAR 파일

외부 git 저장소 없이 제공되는 Java 서비스 JAR을 `lib/` 폴더에 보관한다.

```
lib/
  def/                         ← 서비스 정의 JSON
  sql-gen-mcp-1.0.0-SNAPSHOT.jar
  swagger-mcp-server.jar
```

설치 시 `findBundledJar()`가 startCommand에서 JAR 이름을 추출해 `lib/` 폴더를 탐색하고,
발견하면 파일 선택 없이 자동으로 `installDir`로 복사한다.

### 2. `service-packs/*.yml` — 사용자 커스텀 템플릿

- **역할**: 팀 내부 또는 개인이 재사용하기 위해 YAML로 관리하는 서비스 정의 모음
- **로드**: "서비스 추가" 팝업 → **YAML 가져오기** 버튼으로 수동 임포트
- **로더**: `ServicePackLoader` (Jackson YAML)
- **특징**: `lib/def/`보다 Groovy 스크립트를 풍부하게 작성하는 경우가 많음

예시 (`bgem3-embedding.yml`):
```yaml
groovyScript: |
  service.installDir = env('INSTALL_BASE') ?: (userHome + '/llm-services/bgem3-pyserver')
  if (os == 'windows') { service.startCommand = 'python server.py' }
  else                 { service.startCommand = 'python3 server.py' }
```

### 3. `~/llm-services/services.json` — 실제 등록 서비스 목록

- **역할**: 사용자가 앱에 실제로 추가한 서비스의 영속 저장소
- **로드**: 앱 시작 시 `ServiceRegistry.load()`
- **저장**: 서비스 추가·수정·삭제 시마다 `ServiceRegistry.save()` 즉시 호출
- **내용**: 경로 변수가 이미 치환된 절대 경로와 사용자 설정값 포함
  - `installDir` (실제 설치 경로, 절대값)
  - `argValues` (포트, DB URL 등 사용자 설정값)
  - `envVars` (환경변수)

---

## 서비스 추가 전체 흐름

```
[서비스 추가 버튼]
        │
        ▼
BuiltinServiceLoader.loadAll()         ← lib/def/*.json 읽음
        │
        ▼
BuiltinServicesController (선택 팝업)
  ├─ builtin 선택 → BuiltinServiceSetupDialog   (최적화 팝업)
  │       └─ PlatformUtil.resolvePath()          ← ${user.home} 등 경로 변수 치환
  ├─ 직접 입력   → AddServiceDialog             (범용 팝업)
  └─ YAML 가져오기 → ServicePackLoader          (service-packs/*.yml)
        │
        ▼
사용자 입력 확인 (installDir, argValues 등)
        │
        ▼
ServiceCustomizer.apply()              ← Groovy 스크립트 실행 (있을 때)
        │
        ▼
ServiceRegistry.add()                  ← ~/llm-services/services.json 저장
ProcessManager.getOrCreate()           ← 메모리 인스턴스 생성
```

---

## 설치 흐름 (설치 탭 → ⬇ 설치 버튼)

```
[설치 버튼 클릭]
        │
        ├─ JAVA + repoUrl 없음 (sql-gen-mcp, swagger-mcp)
        │       │
        │       ├─ isInstalled() → JAR 이미 존재?  → "이미 설치됨" 안내
        │       │
        │       ├─ findBundledJar() → lib/<jar> 존재?
        │       │     Yes → 자동 복사 (파일 선택 불필요)
        │       │     No  → FileChooser로 JAR 선택
        │       │
        │       └─ installDir 생성(없으면) → JAR 복사 → INSTALLED 상태
        │
        └─ 그 외 (bge-m3 등, repoUrl 있음)
                │
                └─ git clone (repoUrl 있을 때)
                   → installCommands 순차 실행 (pip install 등)
                   → INSTALLED 상태
```

---

## 주요 클래스 역할 요약

| 클래스 | 위치 | 역할 |
|--------|------|------|
| `BuiltinServiceLoader` | `service/` | `lib/def/*.json` 로드 |
| `ServicePackLoader` | `service/` | YAML 서비스팩 파일 로드 |
| `ServiceRegistry` | `service/` | `~/llm-services/services.json` 영속 저장·로드 |
| `ServiceCustomizer` | `service/` | Groovy 스크립트 실행 (`service`, `os`, `env` 바인딩) |
| `InstallationService` | `service/` | git clone + installCommands + JAR 복사 판단 |
| `ProcessManager` | `service/` | 프로세스 시작·중지·재시작, 인스턴스 맵 관리 |
| `PlatformUtil` | `util/` | OS 감지, 앱 홈 경로, `resolvePath()` 경로 변수 치환 |
| `AddServiceDialog` | `ui/dialog/` | 범용 서비스 추가 팝업 (builtin → `BuiltinServiceSetupDialog`로 라우팅) |
| `BuiltinServiceSetupDialog` | `ui/dialog/` | builtin 서비스 전용 최적화 팝업 |

---

## LLM 스킬 & 룰 관리 흐름

서비스 정의 흐름과 별도로, LLM 스킬 관리는 `llm-skills.fxml` 팝업에서 두 탭으로 제공된다.

### 설치 탭

```
src/main/resources/llm-skills/tools.json
        │
        ▼
LlmSkillInstaller.loadTools()
        │
        ▼
LlmSkillsInstallController
  ├─ 도구 선택: Cursor IDE / Claude Code / Gemini CLI / GitHub Copilot
  ├─ 팩 선택: 도구별 packs 목록
  ├─ 미리보기: 설치 대상 파일과 신규/덮어쓰기 상태 표시
  ├─ 백업 설치: 기존 파일을 .llm-backup/<timestamp>/ 아래 복사
  └─ 설치: 템플릿 변수 치환 후 targetPath로 파일 저장
        │
        ▼
ProjectRegistry.save() → ~/llm-services/projects.json
```

템플릿 파일은 `{{projectName}}`, `{{language}}`, `{{author}}` 변수를 치환한다.

### 로드 탭

```
[소스 디렉토리 선택]
        │
        ▼
LlmSkillsLoadController.onScan()
  ├─ 재귀 탐색
  ├─ 제외: .git, node_modules, target, build, .gradle, .idea, .llm-backup*
  └─ 포함: .md, .mdc, .json, .yaml, .yml, .txt, .toml, .xml
        │
        ▼
파일 목록 체크박스 + 내용 미리보기
        │
        ▼
[로드] → 대상 프로젝트에 상대 경로 유지 복사 (기존 파일 덮어쓰기)
```

`LlmSkillLibraryRepository`는 DB 기반 스킬 라이브러리 저장소 구현을 포함한다. HikariCP 5.1.0, `AppSettings` DB 설정 필드, `SkillFile.libraryFileId` 연결이 완료되어 컴파일은 통과하지만 현재 UI 흐름에서는 아직 사용되지 않는다.
