# TODO Index — LLM 스킬 & 룰 자동 세팅

> 전역 인덱스: [TODO_INDEX.md](TODO_INDEX.md)  
> 이 문서는 `LLM 스킬 & 룰 자동 세팅` 주제의 기능별 인덱스다.

## 배경 / 핵심 관점

LLM 도구마다 규칙 파일의 위치와 형식이 완전히 다르다.

| 도구 | 읽는 위치 |
|------|-----------|
| Claude Code | `CLAUDE.md`, `.claude/commands/*.md` |
| Cursor | `.cursor/rules/*.mdc`, `.cursor/commands/*.md` |
| Gemini CLI | `GEMINI.md`, `.gemini/` |
| GitHub Copilot | `.github/copilot-instructions.md` |
| Codex (OpenAI) | `AGENTS.md` |

Cursor로 작성한 규칙을 Claude에서 쓰려면 포맷과 구조 자체를 재작성해야 한다.  
**이 변환·최적화 작업은 LLM이 담당**하고, 이 앱은 이미 최적화된 결과물을 프로젝트에 배포하는 역할만 한다.

---

## 역할 분리

```
[LLM이 할 일]                         [이 앱이 할 일]
Cursor 규칙 → Claude 형식으로 변환      최적화 완료된 파일 묶음을
포맷 재구성, 내용 재작성          →     지정 프로젝트에 배포(설치)
```

---

## 현재 구조의 문제

- `tools.json`과 스킬 파일이 `src/main/resources/` (classpath 내장)에 있어  
  새 스킬 셋을 추가하려면 파일 복사 + tools.json 수정 + **재빌드** 필요
- 관리 UI 없음 — 개발자가 직접 JSON을 편집해야 함

---

## 목표 구조

`lib/skills/`를 파일시스템 기반 스킬 저장소로 사용한다.  
`lib/def/`가 서비스 정의를 담당하는 것과 동일한 패턴.

```
lib/
  def/              ← 기존 서비스 정의 (변경 없음)
  skills/           ← 신규: 스킬 저장소 루트
    tools.json      ← 파일 → targetPath 매핑 테이블 (파일시스템에서 읽음)
    cursor/
      rules/
        *.mdc
      commands/
        *.md
    claude/
      CLAUDE.md
      commands/
        *.md
    gemini/
      GEMINI.md
    copilot/
      copilot-instructions.md
```

---

## tools.json 역할 재정의

도구 간 변환 로직 없음. 순수하게 **"파일 → 설치 대상 경로(targetPath)" 매핑 테이블**.

```json
{
  "id": "claude",
  "name": "Claude Code",
  "packs": [
    {
      "id": "claude-base",
      "name": "CLAUDE.md 기본 규칙",
      "files": [
        { "sourcePath": "claude/CLAUDE.md", "targetPath": "CLAUDE.md", "template": true }
      ]
    }
  ]
}
```

`sourcePath`는 `lib/skills/` 기준 상대 경로.  
`resourcePath`(classpath) 방식에서 `sourcePath`(파일시스템) 방식으로 전환.

---

## 특정 디렉토리 import 기능 검토

현재 `로드` 탭은 소스 디렉토리를 재귀 스캔한 뒤, 선택한 파일을 대상 프로젝트에
상대 경로 그대로 복사한다. 기능 방향은 맞지만, 실제 import 기능으로 쓰기에는
덮어쓰기, 경로 안전성, UI 응답성 보강이 필요하다.

### 현재 동작

- 소스 디렉토리 선택 후 `.md`, `.mdc`, `.json`, `.yaml`, `.yml`, `.txt`, `.toml`, `.xml` 파일만 탐색
- `.git`, `node_modules`, `target`, `build`, `.gradle`, `.idea`, `.llm-backup*` 경로 제외
- 스캔된 파일은 기본 선택 상태로 표시
- 선택한 파일을 대상 프로젝트에 `sourceRoot` 기준 상대 경로 그대로 복사
- 기존 대상 파일은 `REPLACE_EXISTING`으로 무조건 덮어씀

### 보강 필요 사항

- `Files.walk(sourceRoot)` 스트림을 닫지 않아 Windows에서 디렉토리 핸들이 남을 수 있음
- 제외 경로 판단이 절대 경로 전체를 기준으로 동작해, 상위 경로명이 `build` 또는 `target`이면 정상 파일도 제외될 수 있음
- import 실행 시 기존 파일을 무조건 덮어써 `CLAUDE.md`, `.cursor/rules/*.mdc`, `.github/copilot-instructions.md` 등이 손실될 수 있음
- 스캔, 미리보기, 복사가 JavaFX UI 스레드에서 동기 실행되어 큰 디렉토리에서 화면이 멈출 수 있음
- 미리보기가 파일 전체를 UTF-8로 읽어 큰 파일에서 메모리와 응답성 문제가 생길 수 있음
- `references/`, `scripts/`, `assets/`처럼 스킬 본문을 보조하는 폴더가 있는데, 현재 확장자 중심 필터만으로는 일부 필요한 파일이 누락될 수 있음

### import 분류 정책 초안

확장자만으로 포함 여부를 판단하지 않고, 경로 의미를 함께 본다.

| 분류 | 경로 예시 | 기본 정책 |
|------|-----------|-----------|
| 규칙/명령/에이전트 | `.cursor/rules/**`, `.cursor/commands/**`, `.cursor/agents/**`, `.claude/commands/**` | 기본 포함 |
| 스킬 본문 | `**/SKILL.md` | 기본 포함 |
| references | `**/references/**` | 문서/데이터 파일 기본 포함 |
| scripts | `**/scripts/**`, 스킬 폴더 아래 `.py/.js/.ts/.ps1/.sh` | 포함 후보, 실행 코드 경고 표시 |
| assets | `**/assets/**`, 스킬 폴더 아래 `.png/.jpg/.svg/.pdf/.docx/.xlsx/.pptx` | 포함 후보, 바이너리/크기 표시 |
| 민감/캐시 | `.env`, `*.key`, `*.pem`, `*pat*.txt`, `*token*`, `__pycache__`, `*.pyc`, `.git`, `node_modules` | 기본 제외 |

---

## 세부 TODO 파일

상세 구현 계획은 단계별 파일로 분리한다. 이 문서는 전체 방향과 링크만 유지한다.

| 단계 | 문서 | 목적 |
|------|------|------|
| 1단계 | [TODO_스킬룰자동_1단계_import_안전성.md](TODO_스킬룰자동_1단계_import_안전성.md) | 현재 import 동작을 유지하면서 파일 핸들, 경로, 덮어쓰기 위험을 먼저 줄인다 |
| 2단계 | [TODO_스킬룰자동_2단계_import_UX.md](TODO_스킬룰자동_2단계_import_UX.md) | 신규/충돌/덮어쓰기 상태를 import 전에 판단할 수 있게 UI를 정리한다 |
| 3단계 | [TODO_스킬룰자동_3단계_백그라운드.md](TODO_스킬룰자동_3단계_백그라운드.md) | 스캔/복사를 백그라운드 작업으로 옮겨 JavaFX 화면 멈춤을 제거한다 |
| 4단계 | [TODO_스킬룰자동_4단계_lib-skills.md](TODO_스킬룰자동_4단계_lib-skills.md) | classpath 내장 스킬을 파일시스템 기반 `lib/skills/` 저장소로 전환한다 |

---

## 보류 항목

- 관리 UI (tools.json을 앱 내에서 편집) — 우선순위 낮음, 추후 검토
- 도구 간 스킬 마이그레이션 기능 — 이 앱의 범위 밖 (LLM 담당)
