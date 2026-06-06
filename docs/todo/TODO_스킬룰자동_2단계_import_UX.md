# TODO — 스킬/룰 import 2단계: UX 정리

## 목표

사용자가 어떤 파일이 신규 생성되고, 어떤 파일이 기존 파일과 충돌하는지 import 전에 판단할 수 있게 한다.

## 수정 대상 파일

- `src/main/resources/org/kyj/llmmanager/llm-skills-load.fxml`
- `src/main/java/org/kyj/llmmanager/ui/controller/LlmSkillsLoadController.java`
- `src/main/java/org/kyj/llmmanager/model/LoadFileEntry.java`

## 주요 변경

- `로드` 버튼 명칭을 `가져오기` 또는 `Import` 의미가 드러나는 이름으로 정리
- 스캔 결과에 신규 파일 수, 충돌 파일 수, 제외 파일 수를 함께 표시
- 파일 목록에 상태 표시 추가: `신규`, `충돌`, `덮어쓰기`, `건너뜀`, `실행 코드`, `바이너리`, `민감 제외`
- 파일 목록에 분류 표시 추가: `rule`, `command`, `agent`, `skill`, `reference`, `script`, `asset`, `config`
- 백업 옵션 체크박스 추가: `기존 파일 백업 후 덮어쓰기`
- 덮어쓰기 허용 여부를 사용자가 명시적으로 선택하도록 UI 상태 제어
- 큰 파일 미리보기는 앞부분 일부만 표시하고 전체 크기를 안내
- import 결과 요약을 `신규/덮어쓰기/건너뜀/오류`로 분리 표시
- `scripts/` 파일은 실행 코드임을 표시하고 기본 선택 여부를 정책으로 결정
- `assets/` 파일은 바이너리/대용량 여부를 표시

## 세부 작업

- [ ] `llm-skills-load.fxml` 하단 버튼 텍스트 변경
- [ ] `llm-skills-load.fxml`에 백업 옵션 체크박스 추가
- [ ] `llm-skills-load.fxml`에 상태/요약 라벨 추가
- [ ] `LoadFileEntry`에 `size`, `targetPath`, `status`, `category`, `warning` 필드 추가
- [ ] `ListView` 표시 문자열에 상태와 상대 경로가 함께 보이도록 cell factory 보강
- [ ] `reference/script/asset` 분류별 표시 텍스트와 색상 정책 정의
- [ ] 민감 제외 파일 수를 스캔 요약에 표시
- [ ] 미리보기 파일 크기 제한값 정의
- [ ] 제한 초과 파일은 `[파일 크기 N bytes, 앞 M bytes만 표시]` 형식으로 안내
- [ ] 바이너리 파일은 텍스트 미리보기 대신 파일 크기와 분류만 표시
- [ ] 실행 코드 파일은 미리보기 상단에 `실행 가능한 코드입니다` 경고 표시
- [ ] import 완료 메시지를 결과 유형별로 묶어서 표시

## 완료 기준

- 사용자는 import 전에 어떤 파일이 기존 파일과 충돌하는지 볼 수 있다
- 사용자는 각 파일이 rule/command/agent/skill/reference/script/asset 중 무엇인지 알 수 있다
- 실행 코드와 바이너리 파일은 일반 문서와 구분되어 보인다
- 백업 없이 덮어쓰는 동작이 UI에서 명확히 드러난다
- 큰 파일을 선택해도 미리보기 때문에 화면이 느려지지 않는다
