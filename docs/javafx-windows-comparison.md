# JavaFX vs 다른 Windows 데스크톱 기술

LLM Manager와 같이 **Windows(및 Linux) 현장 PC**에서 동작하는 운영·관리용 데스크톱을 만들 때, JavaFX를 Win32/C++·WPF·Qt·Electron 등과 비교할 때의 장단점을 정리한다.

---

## 비교 대상

| 기술 | 대표 스택 | 특징 |
|------|-----------|------|
| **Win32 / C++** | MSVC, WinAPI | OS와 가장 가깝고 성능·통합 최상 |
| **WPF / WinForms** | .NET | Microsoft 공식 UI, Windows 전용 |
| **Qt** | C++ / Python | 크로스 플랫폼 네이티브급 UI |
| **Electron / Tauri + 웹** | HTML/CSS/JS | UI 생산성·웹 인력 활용 |
| **Swing** | Java | 레거시 Java UI (JavaFX 이전 세대) |
| **JavaFX** | Java 17+ | FXML·CSS·Scene Graph, OpenJFX |

---

## JavaFX의 장점

### 1. 백엔드·도구와 스택 통일

- MCP 서버, 임베딩 API, Spring/Javalin 등 **이미 Java/Python으로 돌아가는 서비스**를 관리하는 앱은, 프로세스 기동·HTTP 헬스·로그 파이프를 Java 한 프로세스에서 다루기 쉽다.
- LLM Manager처럼 **Groovy 커스터마이저**, Jackson/YAML, 동일 JVM에서 내장 API를 띄우는 패턴과 잘 맞는다.

### 2. 크로스 플랫폼 (Windows 우선, Linux 병행)

- 개발·CI는 Linux, 배포는 Windows인 경우가 많다. **동일 코드로 jpackage/msi(Windows)와 AppImage/deb(Linux)** 를 맞추기 수월하다.
- FXML·리소스 경로·`PlatformUtil` 분기만 정리하면 OS별 차이를 코드 한 벌로 관리할 수 있다.

### 3. UI 구조: FXML + CSS + Scene Graph

- **FXML**로 레이아웃과 컨트롤러를 분리 → 디자이너·개발자 역할 분담, `runDev` 핫 리로드로 화면 수정 속도 향상.
- **CSS**로 테마·간격·색을 일괄 조정 (웹과 유사한 스타일링 경험).
- **Observable / Property**로 서비스 상태·로그·메모리 샘플을 UI에 바인딩하기 좋다 (실시간 대시보드·목록 갱신).

### 4. JVM 생태계

- OSHI(시스템 메트릭), Javalin(REST), LangChain4j 등 **성숙한 라이브러리**를 그대로 사용.
- GC·스레드 모델이 익숙하면 장기 실행 관리 앱(트레이 상주, 다수 자식 프로세스 모니터링)을 안정적으로 설계하기 쉽다.

### 5. 배포·보안 (엔터프라이즈 관점)

- **jpackage**로 JDK 번들·시작 메뉴·업데이트 경로를 표준화 (자세한 내용은 [패키징.md](./패키징.md)).
- 코드 서명·내부 CA·방화벽 정책과 함께 **단일 실행 파일/설치 패키지**로 배포하기 좋다.
- Electron 대비 **런타임 크기·메모리**가 보통 유리한 편 (앱 규모에 따라 다름).

### 6. Swing 대비

- 하드웨어 가속 Scene Graph, 현대적 컨트롤·WebView, 애니메이션 API.
- 새 프로젝트는 Swing보다 **JavaFX + FXML**이 유지보수·채용 측면에서 낫다.

---

## JavaFX의 단점

### 1. Windows “네이티브” 느낌·OS 통합

- **Fluent Design / WinUI 11** 수준의 시스템 일관성은 WPF·WinUI가 유리하다.
- 알림 센터, 점프 리스트, 최신 Windows 11 메뉴 스타일 등은 **직접 연동하거나 AWT(SystemTray) 혼용**이 필요 (LLM Manager는 트레이에 AWT 사용).

### 2. 메모리·시작 시간

- **JVM + JavaFX 모듈** 부트스트랩으로 Electron만큼 무겁지는 않아도, 순수 Win32/WPF보다 **콜드 스타트·RSS**는 큰 편.
- 다수의 무거운 자식 프로세스(Python GPU 서버 등)를 띄우는 관리 앱에서는 UI 프로세스 메모리도 합산되어 관리 대상이 된다.

### 3. 생태계·인력

- Windows 전용 팀은 **C# / WPF** 인력이 더 흔하다.
- JavaFX 전문 UI 개발자는 상대적으로 적고, 예제·서드파티 컴포넌트는 **웹·Electron·Qt**보다 적다.

### 4. 하드웨어·미디어·JNI

- DirectX 전용 기능, 일부 드라이버/SDK는 **JNI 또는 별도 네이티브 DLL**이 필요할 수 있다.
- 고성능 3D·비디오 파이프라인은 Qt/네이티브가 유리한 경우가 많다.

### 5. 배포·업데이트

- jpackage로 해결 가능하지만, **.NET ClickOnce / MSIX 자동 업데이트**만큼 익숙하지 않은 팀도 있다.
- OpenJFX 모듈 경로(`--module-path`) 설정 실수 시 현장 PC에서만 재현되는 오류가 날 수 있다 → 빌드 스크립트·문서화 필수.

### 6. 웹 UI와의 UX 격차

- 사내 포털이 **React/Vue**라면, Electron/Tauri로 UI를 웹과 맞추는 선택이 설득력 있을 수 있다.
- JavaFX WebView는 **임베디드 HTML**에는 쓰이지만, 최신 웹 프레임워크 전체를 대체하기엔 한계가 있다.

---

## 요약 비교 (Windows 운영·관리 앱 기준)

| 항목 | JavaFX | WPF/WinForms | Qt | Electron |
|------|--------|--------------|-----|----------|
| Windows 네이티브 UX | 보통 | 우수 | 우수 | 보통(웹 스타일) |
| Linux 동시 지원 | 우수 | 없음 | 우수 | 우수 |
| Java 백엔드와 통합 | **우수** | 별도 프로세스 | 별도 | Node/별도 |
| 실시간 UI·바인딩 | 우수 | 우수 | 우수 | 우수 |
| 설치 크기·메모리 | 중간 | 중간~작음 | 중간 | 큼 |
| UI 개발 속도(웹 팀) | 중간 | 중간 | 중간 | **우수** |
| 장기 상주·트레이 앱 | 적합 | 적합 | 적합 | 적합(무거움) |

---

## 의사결정 체크리스트

다음에 **해당이 많으면 JavaFX(현재 LLM Manager 방향)가 합리적**이다.

- [ ] 관리 대상 서비스가 Java/Python CLI·HTTP 위주이고, UI에서 프로세스·로그·설정을 다룬다.
- [ ] Windows 현장 PC와 **Linux 개발/서버 환경**을 같은 UI 코드로 맞춰야 한다.
- [ ] FXML 분리·Observable·내장 REST API 등 **단일 JVM 앱**으로 묶고 싶다.
- [ ] 사내 표준이 **JDK 17+ / jpackage**이고, Electron 수준의 Chromium 번들이 부담이다.

다음에 **해당이 많으면 다른 스택을 검토**한다.

- [ ] Windows 11 네이티브 UX·MSIX 생태계가 최우선이다 → **WPF / WinUI**.
- [ ] UI를 사내 웹 포털과 **100% 동일한 컴포넌트**로 가져가야 한다 → **Electron / Tauri + 웹**.
- [ ] C++ 장비 SDK·실시간 3D HMI가 핵심이다 → **Qt / Win32**.
- [ ] 설치 용량·메모리가 극도로 제한된 임베디드 Windows이다 → **Win32 / .NET AOT** 등.

---

## LLM Manager에 대한 시사점

이 저장소는 **JavaFX 21 + FXML + 시스템 트레이(AWT) + jpackage** 조합으로, “로컬 LLM·MCP 인프라를 운영자가 GUI로 다루는” 요구에 맞게 설계되어 있다. Windows 전용 화려한 셸보다 **프로세스·로그·헬스·스킬 설치** 일관성과 **Java 생태계 재사용**이 이득인 영역이다.

관련 문서:

- [llm-manager/architecture.md](./llm-manager/architecture.md) — 레이어·스레드 모델
- [패키징.md](./패키징.md) — jpackage·JavaFX 모듈 경로
