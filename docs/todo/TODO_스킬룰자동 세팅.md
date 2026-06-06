# TODO — LLM 스킬 & 룰 자동 세팅

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

## 구현 태스크

- [ ] `lib/skills/` 디렉토리 구조 생성 및 기존 classpath 스킬 파일 이전
- [ ] `tools.json` 파일시스템 기반으로 재작성 (`sourcePath` 방식)
- [ ] `LlmSkillInstaller` — classpath 로딩 제거, 파일시스템 로딩으로 전환  
  (`BuiltinServiceLoader.resolveDefDir()` 패턴 적용)
- [ ] `build.gradle` — `lib/skills/` 를 배포 패키지에 포함 (`lib/def` 방식 동일)
- [ ] 교육자료(MESPlus) 스킬 파일 `lib/skills/` 하위에 추가

---

## 보류 항목

- 관리 UI (tools.json을 앱 내에서 편집) — 우선순위 낮음, 추후 검토
- 도구 간 스킬 마이그레이션 기능 — 이 앱의 범위 밖 (LLM 담당)
