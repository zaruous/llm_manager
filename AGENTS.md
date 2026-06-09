# LLM Manager — Codex 작업 규칙

## 코드 탐색

- 코드 요소 탐색은 가능하면 코드베이스 그래프 MCP 도구를 먼저 사용한다.
- 그래프 도구가 없거나 결과가 부족하면 `rg` / `rg --files`로 대체한다.
- 설정, 문서, FXML, CSS, JSON 같은 비코드 파일은 로컬 검색을 사용해도 된다.

## UI / 스타일링

- 새 JavaFX 화면, 팝업, 다이얼로그, 메뉴를 추가하거나 수정할 때는 기존 UI와 스타일을 먼저 확인한다.
- 비교 기준은 `src/main/resources/org/kyj/llmmanager/app.css`, 기존 `ui/dialog/*Dialog.java`, 관련 FXML 파일이다.
- 새 UI는 가능한 한 기존 CSS 클래스(`section-title`, `text-muted`, `text-success`, `text-danger`, `service-card` 등)를 재사용한다.
- inline style은 기존 컴포넌트와 맞추기 어렵거나 상태 색상처럼 국소적인 경우에만 사용한다.
- 팝업은 특별한 이유가 없으면 기존 다이얼로그처럼 `SceneFactory.create(...)`를 사용하고 `Modality.WINDOW_MODAL`을 적용한다.
- 하단 버튼 영역, 여백, `Separator`, `ScrollPane`, `GridPane` 열 구성은 기존 `SettingsDialog` 또는 `ServiceDetailDialog` 패턴을 따른다.
- 구현 후에는 화면을 띄우지 못하더라도 코드상 스타일 기준을 점검하고, 가능하면 `./gradlew.bat build`로 FXML 핸들러와 Java 컴파일을 확인한다.

## 주석

- Java 클래스와 public 메서드는 기존 `CLAUDE.md`의 주석 작성 규칙을 따른다.
- 비자명한 제약, OS 분기, 라이브러리 회피책, 매직 넘버에는 짧은 설명을 남긴다.
- 코드가 이미 말하는 내용을 반복하는 주석은 추가하지 않는다.
