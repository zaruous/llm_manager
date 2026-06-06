# TODO — 스킬/룰 import 4단계: lib/skills 저장소 연결

## 목표

classpath 내장 스킬 구조를 파일시스템 기반 `lib/skills/` 저장소로 옮기고, 특정 디렉토리 import 기능을 저장소 관리 흐름과 연결한다.

## 수정 대상 파일

- `build.gradle`
- `src/main/java/org/kyj/llmmanager/service/LlmSkillInstaller.java`
- `src/main/java/org/kyj/llmmanager/model/SkillFile.java`
- `src/main/resources/llm-skills/tools.json`
- `lib/skills/tools.json` 신규 생성
- `lib/skills/**` 신규 생성

## 선택적 수정 대상 파일

- `src/main/java/org/kyj/llmmanager/ui/controller/LlmSkillsInstallController.java`
- `src/main/java/org/kyj/llmmanager/ui/controller/LlmSkillsLoadController.java`
- `src/main/resources/org/kyj/llmmanager/llm-skills-install.fxml`
- `src/main/resources/org/kyj/llmmanager/llm-skills-load.fxml`

## 주요 변경

- import 대상을 프로젝트뿐 아니라 `lib/skills/` 저장소로도 선택 가능하게 확장
- `tools.json`을 classpath 리소스가 아니라 파일시스템 저장소에서 읽도록 전환
- `SkillFile.resourcePath` 중심 모델을 `sourcePath` 중심으로 전환
- 스킬 패키지 내부의 `references/`, `scripts/`, `assets/`를 정식 구성 요소로 인정
- 마이그레이션 기간 동안 `resourcePath`와 `sourcePath`를 모두 허용할지 결정
- `LlmSkillInstaller`가 `lib/skills/tools.json`을 우선 읽고, 필요 시 classpath `tools.json`로 fallback할지 결정
- `build.gradle` 배포 설정에 `lib/skills/` 포함
- 기존 `src/main/resources/llm-skills/**` 리소스를 `lib/skills/**`로 이전

## 권장 스킬 패키지 구조

```text
lib/skills/
  tools.json
  cursor/
    skills/
      skill-name/
        SKILL.md
        references/
          guide.md
          sample.json
        scripts/
          helper.py
          validate.js
        assets/
          template.xlsx
          screenshot.png
```

`tools.json`은 설치 대상 파일만 나열하되, 스킬 패키지 내부 보조 파일은 `SKILL.md` 기준 상대 참조가 깨지지 않도록 함께 설치하거나 함께 저장소에 유지한다.

## 세부 작업

- [ ] `lib/skills/` 디렉토리 구조 생성
- [ ] 기존 `src/main/resources/llm-skills/**` 파일을 `lib/skills/**`로 복사
- [ ] `tools.json`의 `resourcePath`를 `sourcePath`로 변경
- [ ] `SkillFile`에 `sourcePath` 필드 추가
- [ ] `SkillFile`에 `category` 또는 `kind` 필드 추가 검토 (`rule/command/agent/skill/reference/script/asset/config`)
- [ ] `LlmSkillInstaller`에 `resolveSkillsDir()` 추가
- [ ] `LlmSkillInstaller.loadTools()`를 파일시스템 우선 로딩으로 변경
- [ ] `LlmSkillInstaller.readResource()`를 `readSkillFile()` 방식으로 전환
- [ ] 스킬 패키지 import 시 `references/`, `scripts/`, `assets/`를 누락 없이 복사하는 정책 정의
- [ ] `scripts/` 파일은 설치 가능하되 실행하지 않는다는 원칙을 명시
- [ ] 민감 파일(`.env`, `*.key`, `*.pem`, `*pat*.txt`, `*token*`)은 `lib/skills` 저장소에도 기본 import 금지
- [ ] `build.gradle` distributions에 `lib/skills` 포함
- [ ] import 후 `tools.json` 후보 매핑 생성 또는 편집 후보 표시 검토
- [ ] `src/main/resources/llm-skills/**` 유지/삭제 정책 결정
- [ ] 단위 테스트 추가: sourcePath 로딩, fallback 정책, 변수 치환, 보조 폴더 복사 정책

## 완료 기준

- 새 스킬 파일을 추가하기 위해 앱을 재빌드하지 않아도 된다
- 배포 패키지에 `lib/skills/`가 포함된다
- 설치 탭과 import 탭이 같은 파일시스템 스킬 저장소를 기준으로 동작한다
- `references/`, `scripts/`, `assets/`가 포함된 스킬 패키지를 가져와도 참조 구조가 깨지지 않는다
