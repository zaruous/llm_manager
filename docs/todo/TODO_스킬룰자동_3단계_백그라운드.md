# TODO — 스킬/룰 import 3단계: 백그라운드 실행

## 목표

큰 디렉토리 또는 네트워크 드라이브를 import해도 JavaFX 화면이 멈추지 않게 한다.

## 수정 대상 파일

- `src/main/java/org/kyj/llmmanager/ui/controller/LlmSkillsLoadController.java`
- `src/main/resources/org/kyj/llmmanager/llm-skills-load.fxml`

## 선택적 분리 후보

- `src/main/java/org/kyj/llmmanager/service/LlmSkillImportService.java` 신규 생성

## 주요 변경

- 스캔과 복사를 JavaFX `Task` 기반 백그라운드 작업으로 이동
- 진행률, 현재 처리 파일, 취소 버튼 추가
- 스캔/복사 중 버튼 비활성화
- 완료, 취소, 오류 시 UI 상태 복원
- 오류는 파일별로 누적해 결과 요약에 표시
- import 로직이 커지면 `LlmSkillImportService`로 파일 스캔/충돌 계산/복사/백업 책임 분리

## 세부 작업

- [ ] `scanTask` 생성: 파일 탐색, 제외 판별, 상태 계산
- [ ] `importTask` 생성: 백업, 복사, 건너뜀, 오류 수집
- [ ] `ProgressBar` 또는 `ProgressIndicator` 추가
- [ ] 현재 처리 중인 파일 경로 표시 라벨 추가
- [ ] 취소 버튼 추가 및 `Task.cancel()` 연결
- [ ] 스캔/복사 중 소스/대상 선택, 스캔, 가져오기 버튼 비활성화
- [ ] 완료/실패/취소 콜백에서 UI 상태 복원
- [ ] 경로 판별과 충돌 계산 로직을 컨트롤러 밖으로 옮길지 검토

## 완료 기준

- 수천 개 파일이 있는 디렉토리를 스캔해도 창 이동, 탭 전환, 취소 버튼이 응답한다
- 복사 중 취소 요청이 가능하다
- 오류가 한 파일에서 발생해도 전체 결과 요약에서 어떤 파일이 실패했는지 알 수 있다
