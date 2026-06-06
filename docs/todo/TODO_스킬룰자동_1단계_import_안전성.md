# TODO — 스킬/룰 import 1단계: 안전성 우선 보강

## 목표

현재 `로드` 탭의 기본 동작은 유지하되, 파일 핸들 누수와 실수로 인한 파일 손실을 먼저 막는다.

## 수정 대상 파일

- `src/main/java/org/kyj/llmmanager/ui/controller/LlmSkillsLoadController.java`
- `src/main/java/org/kyj/llmmanager/model/LoadFileEntry.java`

## 주요 변경

- `Files.walk(sourceRoot)`를 try-with-resources로 감싸 디렉토리 핸들 누수 제거
- 제외 경로 판별을 절대 경로 기준에서 `sourceRoot.relativize(path)` 기준으로 변경
- 확장자 중심 필터에 더해 `references/`, `scripts/`, `assets/` 경로 의미를 인식
- `targetRoot`가 실제 디렉토리인지 검증하고, 없으면 생성 여부를 명확히 처리
- 대상 경로가 `targetRoot` 밖으로 벗어나지 않도록 `normalize()` 후 검증
- 복사 전 충돌 파일 목록을 계산해 미리보기 영역에 표시
- 기본 import 동작은 기존 파일을 보존하고, 덮어쓰기는 명시 선택 후 처리
- `LoadFileEntry`에 대상 경로와 충돌 여부를 담을 수 있는 필드 추가
- 민감 파일 후보(`.env`, `*.key`, `*.pem`, `*pat*.txt`, `*token*`)와 캐시 파일(`__pycache__`, `*.pyc`)은 기본 제외

## 세부 작업

- [ ] `onScan()`에서 `try (Stream<Path> paths = Files.walk(sourceRoot))` 형태로 변경
- [ ] `isExcluded(Path path)`에 전달하는 경로를 `sourceRoot.relativize(path)`로 변경
- [ ] `isSkillRuleFile(Path path)`를 `classifyImportFile(Path relativePath)` 형태로 확장
- [ ] `**/SKILL.md`, `.cursor/rules/**`, `.cursor/commands/**`, `.cursor/agents/**`, `.claude/commands/**`는 기본 포함
- [ ] `**/references/**`는 `.md/.txt/.json/.yaml/.yml/.xml/.csv/.toml` 등 문서/데이터 파일 기본 포함
- [ ] `**/scripts/**` 및 스킬 폴더 하위 `.py/.js/.ts/.ps1/.sh`는 포함 후보로 분류
- [ ] `**/assets/**` 및 스킬 폴더 하위 `.png/.jpg/.jpeg/.svg/.pdf/.docx/.xlsx/.pptx`는 포함 후보로 분류
- [ ] `.env`, `*.key`, `*.pem`, `*pat*.txt`, `*token*`, `__pycache__`, `*.pyc`는 기본 제외
- [ ] `Path targetRoot = Path.of(targetPath).toAbsolutePath().normalize()` 적용
- [ ] `dest = targetRoot.resolve(entry.getRelativePath()).normalize()` 후 `dest.startsWith(targetRoot)` 검증
- [ ] 대상 파일이 이미 있으면 `LoadFileEntry`에 충돌 상태 저장
- [ ] 충돌 파일이 있고 백업/덮어쓰기 옵션이 없으면 복사하지 않고 건너뜀
- [ ] import 결과를 `신규`, `건너뜀`, `오류`로 구분해 표시

## 완료 기준

- 반복 스캔 후에도 Windows에서 소스 디렉토리 삭제/이동이 막히지 않는다
- 소스 루트 상위 경로명이 `build` 또는 `target`이어도 내부 파일 스캔이 정상 동작한다
- `references/`의 보조 문서와 `scripts/`, `assets/`의 필요한 파일이 누락되지 않는다
- 민감 파일과 캐시 파일은 기본 import 후보에서 제외된다
- 기존 파일은 사용자 확인 없이 덮어쓰지 않는다
- 경로 조작으로 대상 프로젝트 밖에 파일을 쓸 수 없다
