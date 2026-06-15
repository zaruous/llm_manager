# 위키 HTML·PDF 내보내기 기능 설계

## 기능 개요

WikiBrowserDialog에서 보는 위키 워크스페이스 콘텐츠를 외부에서 배포·공유 가능한
정적 HTML 사이트로 내보내고, 각 페이지를 PDF로도 저장하는 기능.

---

## 설계 결정

| 결정 항목 | 선택 | 이유 |
|----------|------|------|
| 마크다운 변환 | **commonmark-java** (서버사이드) | CDN 불필요, JS 없어도 읽힘, SEO 가능 |
| 기존 코드 재활용 | WikiBrowserDialog의 `pageIndex`, `convertWikiLinks`, `stripFrontmatterToMeta` | 링크 변환 로직이 이미 완성됨 |
| 위키링크 변환 | `[[WikiLink]]` → 상대 경로 `../entities/Foo.html` | 오프라인에서도 동작하는 사이트 |

---

## 산출물 구조

```
output/
├── index.html              ← overview.md or index.md
├── assets/
│   ├── style.css           ← help-template.html CSS 기반 + 라이트/다크 테마
│   ├── nav.js              ← 사이드바 토글, 검색
│   └── search-index.json   ← 제목·본문 인덱스 (옵션)
├── sources/
│   └── article-foo.html
├── entities/
│   └── JohnDoe.html
├── concepts/
│   └── RAG.html
├── syntheses/
│   └── q-what-is-rag.html
└── graph/
    └── graph.html          ← 존재 시 복사
```

각 페이지 레이아웃:
```
┌──────────────────────────────────────────────┐
│ [위키명]       [검색창]      [테마 토글]      │
├──────────┬───────────────────────────────────┤
│ 문서     │                                   │
│  overview│   # 페이지 제목                   │
│  index   │                                   │
│  log     │   > type: entity · last_updated:  │
│ sources  │                                   │
│  (12)    │   본문 내용...                    │
│ entities │                                   │
│  (23)    │   [[WikiLink]] → 클릭 가능 링크   │
│ concepts │                                   │
│  (8)     │                                   │
│ syntheses│                                   │
│  (4)     │                                   │
└──────────┴───────────────────────────────────┘
```

---

## 신규 클래스 설계

### WikiSiteExporter (서비스 계층)

```java
public class WikiSiteExporter {
    public record ExportOptions(
        String theme,           // "dark" | "light"
        boolean includeSearch,
        boolean includeGraph,
        boolean cleanOutput
    ) {}

    public record ExportResult(int pageCount, Path outputDir, Duration elapsed) {}

    /**
     * 워크스페이스의 위키 콘텐츠를 정적 HTML 사이트로 내보낸다.
     * @param workspace wiki/index.md가 있는 루트 디렉토리
     * @param outputDir HTML 파일을 쓸 출력 디렉토리
     * @param options   테마·검색·그래프 포함 여부
     * @param progress  진행 콜백 (현재파일명, 현재번호, 전체수)
     */
    public ExportResult export(Path workspace, Path outputDir,
                               ExportOptions options,
                               TriConsumer<String, Integer, Integer> progress)
                               throws IOException;
}
```

내부 파이프라인:
```
collectPages(wikiDir)          ← WikiBrowserDialog 스캔 로직 재활용
    ↓
buildPageIndex(pages)          ← 이름→경로 맵 (link 해석용)
    ↓
[각 페이지 처리]
    readMarkdown(page)
    stripFrontmatterToMeta()   ← 기존 메서드 추출
    convertWikiLinksToHtml()   ← [[Link]] → ../category/Page.html
    parseMarkdown(md)          ← commonmark-java (서버사이드)
    wrapInTemplate(html, nav)
    writeFile(outputDir/...)
    ↓
buildSearchIndex(pages)        ← 제목+본문 JSON (선택)
copyAssets(outputDir/assets/)
copyGraph(workspace/graph/)    ← 선택
```

### WikiExportDialog (UI)

WikiBrowserDialog 상단바에 "내보내기" 버튼 추가:

```
┌───────────────────────────────────────────────┐
│  위키 내보내기                                 │
├───────────────────────────────────────────────┤
│  출력 위치: [_______________________] [찾기]  │
│  ─────────────────────────────────────────     │
│  [✓] 사이드바 네비게이션 포함                 │
│  [✓] 검색 기능 포함                           │
│  [✓] 그래프 페이지 포함                       │
│  [ ] 기존 출력 초기화 후 생성                 │
│  ─────────────────────────────────────────     │
│  테마:  (●) 다크   ( ) 라이트                 │
│  ─────────────────────────────────────────     │
│  예상 페이지: 47개                            │
│                         [취소] [내보내기]     │
└───────────────────────────────────────────────┘
```

진행 중 → 완료:
```
내보내기 중...  entities/JohnDoe.html  ██████░░  32/47
완료: 47페이지 → C:\...\wiki-export\   [폴더 열기] [닫기]
```

### site-template.html (신규 리소스)

기존 `help-template.html`에서 분기:
- `__MARKDOWN_JSON__` 플레이스홀더 제거 → 이미 변환된 `__CONTENT_HTML__` 사용
- `__NAV_HTML__`, `__PAGE_TITLE__`, `__ASSET_BASE__` 플레이스홀더 추가
- 라이트/다크 CSS 변수 분리 (`data-theme="dark|light"`)
- `<details open>/<summary>` 기반 사이드바 (JS 없이도 동작)

---

## 보완 설계

### 1. WikiBrowserDialog 코드 중복 해소 — WikiMarkdownUtils 추출

`WikiBrowserDialog`의 private 메서드들을 공통 유틸로 추출해 `WikiSiteExporter`와 공유한다.

```
org.kyj.llmmanager.service.WikiMarkdownUtils
  ├── collectPages(wikiDir) → List<PageEntry>
  ├── buildPageIndex(pages) → Map<String, Path>
  ├── stripFrontmatterToMeta(md) → String
  ├── normalizeKey(name) → String
  └── readFrontmatterValue(file, key) → String
```

`WikiBrowserDialog`와 `WikiSiteExporter` 모두 `WikiMarkdownUtils`를 사용.
`convertWikiLinks`는 변환 대상(wiki: 스킴 vs 상대 HTML 경로)이 달라 각 클래스에 별도 유지.

### 2. 이미지 파일 처리

마크다운 파싱 중 이미지 경로를 수집하고 `output/images/`로 복사, 경로를 재매핑한다.

```
마크다운 내 이미지: ![설명](../raw/screenshot.png)
                          ↓
output/images/screenshot.png 로 복사
HTML 내 경로 재매핑: <img src="../images/screenshot.png">
```

`WikiSiteExporter`에 `collectAndCopyImages(md, sourcePage, outputDir)` 메서드 추가.
commonmark-java의 `ImageVisitor`로 `![]()` 노드를 순회해 경로 수집.

### 3. 파일명 슬러그 변환 규칙

한글·공백·특수문자 파일명은 **그대로 유지**한다 (슬러그화하지 않음).
`[[WikiLink]]` → HTML 경로 변환 시 `URLEncoder`로 인코딩해 링크 안전성 보장.

```java
// 예: entities/내 문서.html → href="../entities/%EB%82%B4%20%EB%AC%B8%EC%84%9C.html"
String encoded = URLEncoder.encode(stem + ".html", StandardCharsets.UTF_8)
                           .replace("+", "%20");
```

`pageIndex` 키는 기존과 동일하게 `normalizeKey()`(소문자+공백 정규화)로 유지.

### 4. 취소 후 부분 파일 처리 — 임시 디렉토리 방식

출력을 임시 디렉토리에 먼저 생성하고, 완료 시 목표 디렉토리로 이동(rename)한다.

```
내보내기 진행 중: output/.export-tmp-<timestamp>/  ← 여기에 씀
완료:             output/.export-tmp-.../ → output/ 로 atomic rename
취소:             output/.export-tmp-.../ 삭제 (원본 output/ 보존)
```

`cleanOutput: true`일 때는 rename 전 기존 output 삭제.

### 5. cleanOutput 안전장치

출력 경로 선택 시 다음 두 가지를 검사한다.

```java
// 워크스페이스 하위 경로 선택 시 경고
if (outputDir.startsWith(workspace)) {
    // 경고 다이얼로그: "출력 경로가 워크스페이스 안입니다. 계속하면 위키 수집 대상에 포함될 수 있습니다."
}
// 출력 경로가 비어 있지 않고 cleanOutput=true 시 확인
if (cleanOutput && Files.exists(outputDir) && hasFiles(outputDir)) {
    // 확인 다이얼로그: "기존 파일 N개가 삭제됩니다. 계속하시겠습니까?"
}
```

### 6. Level 2 PrinterJob — PDF 프린터 자동 탐색

OS 프린터 목록에서 PDF 관련 프린터를 먼저 탐색하고, 없으면 인쇄 대화상자를 표시한다.

```java
private void exportCurrentPageToPdf() {
    // "PDF", "pdf" 키워드를 포함한 프린터 자동 탐색
    Printer pdfPrinter = Printer.getAllPrinters().stream()
        .filter(p -> p.getName().toLowerCase().contains("pdf"))
        .findFirst()
        .orElse(null);

    PrinterJob job = pdfPrinter != null
        ? PrinterJob.createPrinterJob(pdfPrinter)
        : PrinterJob.createPrinterJob();

    if (job == null) return;
    // pdfPrinter 없으면 OS 인쇄 대화상자 표시
    if (pdfPrinter == null && !job.showPrintDialog(stage)) return;

    job.getPrinter().createPageLayout(
        Paper.A4, PageOrientation.PORTRAIT, Printer.MarginType.DEFAULT);
    webView.getEngine().print(job);
    job.endJob();
}
```

### 7. parallelStream() → 명시적 ExecutorService

`ForkJoinPool.commonPool()` 대신 전용 스레드 풀을 사용해 JavaFX와의 간섭을 방지한다.

```java
ExecutorService pool = Executors.newFixedThreadPool(
    Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
try {
    List<Future<?>> futures = pages.stream()
        .map(page -> pool.submit(() -> renderAndWrite(page, pageIndex, outputDir, counter, progress)))
        .toList();
    for (Future<?> f : futures) f.get();  // 취소 전파: task.cancel() → thread interrupt
} finally {
    pool.shutdownNow();
}
```

### 8. 사이드바 현재 페이지 강조

정적 HTML이므로 `nav.js`에서 `window.location.pathname`을 파싱해 현재 링크에 `active` 클래스를 추가한다.

```javascript
// nav.js
(function () {
  var current = window.location.pathname.replace(/.*\//, '');
  document.querySelectorAll('.sidebar a').forEach(function (a) {
    if (a.getAttribute('href').endsWith(current)) {
      a.classList.add('active');
      // 부모 <details> 열기
      var details = a.closest('details');
      if (details) details.open = true;
    }
  });
})();
```

### 9. 부분 실패 처리 — 스킵 + 완료 보고

페이지 렌더링 실패 시 해당 페이지만 스킵하고 전체를 계속 진행한다.
`ExportResult`에 실패 목록을 추가해 완료 다이얼로그에 표시한다.

```java
public record ExportResult(
    int pageCount,
    int failCount,
    List<String> failedPages,   // 실패한 페이지 경로 목록
    Path outputDir,
    Duration elapsed
) {}
```

완료 다이얼로그:
```
완료: 44페이지 성공, 3페이지 실패
  ⚠ entities/깨진파일.html — 읽기 오류: ...
  ⚠ concepts/특수문자?.html — 경로 오류: ...
                    [실패 목록 복사] [폴더 열기] [닫기]
```

### 10. 출력 경로 설정 영속화

마지막 선택한 출력 경로를 `AppSettingsRepository`에 `wiki.exportDir` 키로 저장한다.

```java
// WikiExportDialog 내보내기 완료 후
AppContext.getInstance().getAppSettingsRepository()
    .setPluginSetting("wiki-agent", "wiki.exportDir", outputDir.toString());

// 다음 열기 시 기본값 복원
String lastExportDir = settings.getPluginSetting("wiki-agent", "wiki.exportDir", "");
```

### 11. `__ASSET_BASE__` 깊이 계산 로직

페이지가 위치한 depth에 따라 `../` 접두어 수를 계산해 `__ASSET_BASE__`에 주입한다.

```java
/**
 * outputDir 기준 page 파일의 상대 깊이로 assets 루트까지의 상대 경로를 반환한다.
 * 예: index.html → "assets/",  entities/Foo.html → "../assets/"
 */
private String assetBase(Path outputRoot, Path pageFile) {
    Path rel = outputRoot.relativize(pageFile.getParent());
    int depth = rel.getNameCount();
    // outputRoot 직속(depth=0 또는 빈 경로)이면 "assets/"
    if (depth == 0 || rel.toString().isEmpty()) return "assets/";
    return "../".repeat(depth) + "assets/";
}
```

### 12. 코드 블록 구문 하이라이팅

commonmark-java는 언어 클래스만 추가하므로 하이라이팅 라이브러리를 `assets/`에 번들한다.
외부 CDN 없이 동작해야 하므로 `highlight.min.js` + 테마 CSS를 로컬 복사한다.

```
assets/
├── highlight.min.js        ← highlight.js (MIT 라이선스, ~100KB)
└── highlight-theme.css     ← github-dark / github 테마 (다크/라이트 분기)
```

`site-template.html`에서 `<link>`와 `<script>` 태그 추가 후 `hljs.highlightAll()` 호출.

### 13. `cleanOutput: false` 시 stale HTML 파일 제거

재내보내기 시 현재 위키 페이지 목록에 없는 기존 HTML 파일(orphan)을 감지해 삭제한다.

```java
// 내보내기 완료 후 orphan 정리
Set<Path> expected = pages.stream()
    .map(p -> resolveOutputPath(outputRoot, p))
    .collect(Collectors.toSet());
Files.walk(outputRoot)
    .filter(f -> f.toString().endsWith(".html"))
    .filter(f -> !expected.contains(f))
    .filter(f -> !f.equals(outputRoot.resolve("index.html"))) // 루트 index는 보존
    .forEach(f -> Files.deleteIfExists(f));
```

### 14. `graph/` 디렉토리 전체 복사

`graph.html`만 복사하면 D3.js 데이터 파일 등 참조 자산이 누락돼 그래프가 작동하지 않는다.
`graph/` 디렉토리 전체를 `output/graph/`로 복사한다.

```java
private void copyGraph(Path workspace, Path outputDir) throws IOException {
    Path src = workspace.resolve("graph");
    if (!Files.isDirectory(src)) return;
    Path dst = outputDir.resolve("graph");
    try (Stream<Path> walk = Files.walk(src)) {
        walk.forEach(s -> {
            Path d = dst.resolve(src.relativize(s));
            if (Files.isDirectory(s)) Files.createDirectories(d);
            else Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
        });
    }
}
```

### 15. 이미지 파일명 충돌 처리

서로 다른 디렉토리에 같은 이름의 이미지가 있을 경우 `output/images/`에서 덮어쓰기 충돌이 발생한다.
원본 경로의 상대 경로를 보존하거나, 카테고리 접두어를 파일명에 포함한다.

```
원본: sources/image.png  → output/images/sources/image.png
원본: entities/image.png → output/images/entities/image.png
```

즉, `output/images/` 아래에 원본 경로 구조를 그대로 유지해 충돌을 방지한다.

### 16. `ExportOptions` record — `includeNav` 필드 추가

UI 다이얼로그에 "사이드바 네비게이션 포함" 체크박스가 있지만 `ExportOptions` record에 누락됐다.

```java
public record ExportOptions(
    String theme,
    boolean includeNav,      // 추가
    boolean includeSearch,
    boolean includeGraph,
    boolean cleanOutput
) {}
```

`includeNav: false`일 때 `wrapInTemplate()`에서 `__NAV_HTML__`을 빈 문자열로 치환하고
사이드바 CSS 영역을 숨긴다.

### 17. 페이지 제목(`__PAGE_TITLE__`) 결정 로직

`<title>` 태그와 사이드바 표시에 사용할 페이지 제목 결정 우선순위를 명시한다.

```
1순위: frontmatter title: 값
2순위: 마크다운 본문 첫 번째 # 제목 추출
3순위: 파일명 stem (확장자 제거)
```

```java
private String resolveTitle(Path file, String markdownBody) {
    String fm = WikiMarkdownUtils.readFrontmatterValue(file, "title");
    if (fm != null && !fm.isBlank()) return fm;
    // 본문 첫 # 헤딩 추출
    Matcher h1 = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(markdownBody);
    if (h1.find()) return h1.group(1).trim();
    return WikiMarkdownUtils.stem(file);
}
```

### 18. 취소 시 `InterruptedException` → 정리 작업 연결

`task.cancel()` → Task 스레드 인터럽트 → `Future.get()` `InterruptedException` 흐름에서
임시 디렉토리 정리까지 이어지는 예외 처리 흐름을 명시한다.

```java
ExecutorService pool = Executors.newFixedThreadPool(threads);
Path tmpDir = outputDir.getParent().resolve(".export-tmp-" + System.currentTimeMillis());
try {
    List<Future<?>> futures = pages.stream()
        .map(p -> pool.submit(() -> renderAndWrite(p, ...)))
        .toList();
    for (Future<?> f : futures) {
        try { f.get(); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();  // 인터럽트 상태 복원
            throw e;                             // 외부 catch로 전파
        }
    }
    Files.move(tmpDir, outputDir, StandardCopyOption.REPLACE_EXISTING);
} catch (InterruptedException | CancellationException e) {
    // 취소: 임시 디렉토리 정리
    deleteQuietly(tmpDir);
} catch (Exception e) {
    deleteQuietly(tmpDir);
    throw e;
} finally {
    pool.shutdownNow();
}
```

### 19. 리포트 파일 포함 여부 결정

`health-report.md` / `lint-report.md`는 WikiBrowserDialog에서 "리포트" 카테고리로 표시된다.
내보내기 시 기본 포함하고, `ExportOptions`에 `includeReports` 옵션으로 제어한다.

산출물 구조 추가:
```
output/
└── reports/
    ├── health-report.html
    └── lint-report.html
```

사이드바에도 "리포트" 섹션 추가.

### 20. 완료 후 "브라우저에서 열기" 버튼

완료 다이얼로그에 "브라우저에서 열기" 버튼을 추가해 `output/index.html`을 OS 기본 브라우저로 즉시 연다.

```java
openBrowserBtn.setOnAction(e ->
    Desktop.getDesktop().browse(outputDir.resolve("index.html").toUri()));
```

완료 다이얼로그 최종 형태:
```
완료: 44페이지 성공, 3페이지 실패
  ⚠ entities/깨진파일.html — 읽기 오류
         [실패 목록 복사] [브라우저에서 열기] [폴더 열기] [닫기]
```

---

## 의존성 추가

```groovy
// build.gradle — commonmark-java (마크다운 서버사이드 파싱)
implementation 'org.commonmark:commonmark:0.22.0'
implementation 'org.commonmark:commonmark-ext-gfm-tables:0.22.0'
implementation 'org.commonmark:commonmark-ext-autolink:0.22.0'
```

약 500KB 추가, MIT 라이선스.

---

## PDF 저장 기능

### Level 1: HTML 페이지 안에 "PDF로 저장" 버튼 (가장 간단)

내보낸 HTML 페이지에 `window.print()` 버튼을 넣고, `@media print` CSS로 인쇄 레이아웃 최적화.
브라우저의 "PDF로 저장" 프린터를 활용한다.

```css
/* site-template.html에 추가 */
@media print {
  .sidebar, .export-btn { display: none; }
  body { background: white; color: black; font-size: 11pt; }
  a { color: black; }
  pre, code { border: 1px solid #ccc; }
  h1, h2, h3 { page-break-after: avoid; }
  pre { page-break-inside: avoid; }
}
```

```html
<button class="export-btn" onclick="window.print()">🖨️ PDF 저장</button>
```

- 별도 라이브러리 불필요
- Chrome/Edge "PDF로 저장" → 품질 좋은 PDF
- 한계: 브라우저 없이는 불가, 일괄 변환 안 됨

### Level 2: 앱 내 직접 PDF 저장 (JavaFX PrinterJob)

WikiBrowserDialog에 "PDF 저장" 버튼 → 현재 WebView 내용을 직접 출력:

```java
private void exportCurrentPageToPdf() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("PDF 저장");
    chooser.getExtensionFilters().add(
        new FileChooser.ExtensionFilter("PDF 파일", "*.pdf"));
    chooser.setInitialFileName(stem(currentPage) + ".pdf");
    File out = chooser.showSaveDialog(stage);
    if (out == null) return;

    PrinterJob job = PrinterJob.createPrinterJob();
    if (job != null) {
        PageLayout layout = job.getPrinter().createPageLayout(
            Paper.A4, PageOrientation.PORTRAIT, Printer.MarginType.DEFAULT);
        webView.getEngine().print(job);
        job.endJob();
    }
}
```

- 추가 의존성 없음 (JavaFX 내장)
- 한계: 다크 배경 그대로 출력됨 → `@media print` CSS 필요, 현재 페이지만 가능

### Level 3: 일괄 HTML→PDF 변환 (openhtmltopdf)

WikiSiteExporter에 전체 위키 일괄 변환 옵션 추가:

```groovy
// build.gradle
implementation 'com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10'
implementation 'com.openhtmltopdf:openhtmltopdf-svg-support:1.0.10'
```

```java
public void exportPageToPdf(String htmlContent, Path outputPdf) throws IOException {
    try (OutputStream os = Files.newOutputStream(outputPdf)) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.withHtmlContent(htmlContent, outputPdf.getParent().toUri().toString());
        builder.toStream(os);
        builder.run();
    }
}
```

- 일괄 변환 가능 (전체 위키 → 개별 PDF)
- 브라우저 불필요
- 약 8MB 의존성 추가 (pdfbox)

### PDF 방식 비교

| 목적 | 방식 |
|------|------|
| 현재 페이지를 빠르게 PDF로 | Level 1 (HTML 안 버튼, `window.print()`) |
| 앱에서 현재 페이지 직접 저장 | Level 2 (JavaFX PrinterJob) |
| 전체 위키 일괄 PDF 변환 | Level 3 (openhtmltopdf) |

**추천**: Level 1 + Level 2 조합.
- HTML 내보내기 시 `@media print` CSS는 어차피 필요
- JavaFX PrinterJob은 추가 의존성 없음

---

## 구현 순서

| 단계 | 작업 | 비고 |
|------|------|------|
| 1 | `WikiSiteExporter` 핵심 로직 | commonmark-java, 링크 변환 |
| 2 | `site-template.html` + CSS | help-template.html 기반 확장, @media print 포함 |
| 3 | `WikiExportDialog` | 버튼·진행바 |
| 4 | WikiBrowserDialog에 "내보내기" + "PDF 저장" 버튼 연결 | |
| 5 | 검색 인덱스 JSON + nav.js | 선택적 |

---

## 완료 체크리스트

### 공통 유틸 추출 (#1)

- [ ] `WikiMarkdownUtils` 클래스 신규 작성
  - [ ] `collectPages(wikiDir)` — 카테고리별 마크다운 파일 수집
  - [ ] `buildPageIndex(pages)` — 이름→경로 맵 (normalizeKey 기준)
  - [ ] `stripFrontmatterToMeta(md)` — frontmatter 제거, type·last_updated 메타 유지
  - [ ] `normalizeKey(name)` — 소문자·공백 정규화
  - [ ] `readFrontmatterValue(file, key)` — frontmatter 단일 값 추출
  - [ ] `stem(file)` — 파일명 확장자 제거
- [ ] `WikiBrowserDialog` → `WikiMarkdownUtils` 사용으로 리팩토링 (기존 private 메서드 교체)

### HTML 내보내기

- [ ] `build.gradle`에 commonmark-java 의존성 추가
  - [ ] `org.commonmark:commonmark:0.22.0`
  - [ ] `org.commonmark:commonmark-ext-gfm-tables:0.22.0`
  - [ ] `org.commonmark:commonmark-ext-autolink:0.22.0`
- [ ] `WikiSiteExporter` 클래스 작성
  - [ ] `ExportOptions` record — `includeNav`, `includeSearch`, `includeGraph`, `includeReports`, `cleanOutput`, `theme` 필드 (#16, #19)
  - [ ] `ExportResult` record — `pageCount`, `failCount`, `List<String> failedPages`, `outputDir`, `elapsed` 필드 (#9)
  - [ ] `collectPages()` / `buildPageIndex()` — `WikiMarkdownUtils` 위임
  - [ ] `resolveTitle(file, body)` — frontmatter title → 첫 `#` 헤딩 → stem 순 결정 (#17)
  - [ ] `assetBase(outputRoot, pageFile)` — depth 기반 `../` 상대 경로 계산 (#11)
  - [ ] `convertWikiLinksToHtml()` — `[[Link]]` → 상대 HTML 경로 + `URLEncoder` 인코딩 (#3)
  - [ ] `parseMarkdown()` — commonmark-java 렌더링
  - [ ] `collectAndCopyImages()` — 이미지 경로 수집·`output/images/<원본상대경로>/` 복사·경로 재매핑 (#2, #15)
  - [ ] `buildNavigation()` — 사이드바 HTML 조각 생성 (리포트 섹션 포함, #19)
  - [ ] `buildSearchIndex()` — 제목+본문 JSON (모든 페이지 완료 후 실행)
  - [ ] `copyAssets()` — CSS/JS/highlight.js 정적 자산 복사 (#12)
  - [ ] `copyGraph()` — `graph/` 디렉토리 전체 복사 (#14)
  - [ ] `removeOrphanHtmlFiles()` — 현재 페이지 목록에 없는 기존 HTML 삭제 (#13)
  - [ ] `export()` — 전체 파이프라인 + 진행 콜백
- [ ] `site-template.html` 신규 리소스 작성
  - [ ] `__CONTENT_HTML__`, `__NAV_HTML__`, `__PAGE_TITLE__`, `__ASSET_BASE__` 플레이스홀더
  - [ ] 라이트/다크 CSS 변수 (`data-theme` 분기)
  - [ ] `<details>/<summary>` 기반 사이드바 (JS 없이도 동작)
  - [ ] `@media print` CSS 포함 (사이드바·버튼 숨김, 흰 배경, page-break 규칙)
  - [ ] highlight.js 로드 + `hljs.highlightAll()` 호출 (#12)
- [ ] `assets/` 정적 자산 준비
  - [ ] `style.css` — help-template.html CSS 기반 확장
  - [ ] `highlight.min.js` + `highlight-theme-dark.css` / `highlight-theme-light.css` (#12)
  - [ ] `nav.js`
    - [ ] `window.location.pathname` → 현재 페이지 사이드바 `.active` 강조 (#8)
    - [ ] 해당 `<details>` 자동 열기
    - [ ] 검색 인덱스 연동 (선택)
- [ ] `WikiExportDialog` 작성
  - [ ] 출력 디렉토리 선택 (DirectoryChooser)
  - [ ] 마지막 선택 경로 `wiki.exportDir`로 기본값 복원 (#10)
  - [ ] 옵션 체크박스 (네비게이션·검색·그래프·리포트·초기화) (#16, #19)
  - [ ] 테마 선택 (다크/라이트)
  - [ ] 예상 페이지 수 표시
  - [ ] 출력 경로가 워크스페이스 하위일 때 경고 표시 (#5)
  - [ ] `cleanOutput=true` + 기존 파일 존재 시 삭제 확인 다이얼로그 (#5)
  - [ ] 진행바 + 현재 파일명 표시 (`progressProperty` / `messageProperty` 바인딩)
  - [ ] "중단" 버튼 (`task.cancel()`)
  - [ ] 완료 후 성공·실패 통계 + "브라우저에서 열기" + "폴더 열기" 버튼 (#9, #20)
  - [ ] 완료 후 `wiki.exportDir` 설정 저장 (#10)
- [ ] WikiBrowserDialog 상단바에 "내보내기" + "PDF 저장" 버튼 추가

### 비동기 처리

- [ ] `WikiSiteExporter.export()`를 `Task<ExportResult>`로 감싸 백그라운드 스레드 실행
  - [ ] `progressBar.progressProperty().bind(task.progressProperty())`
  - [ ] `statusLabel.textProperty().bind(task.messageProperty())`
  - [ ] `task.setOnSucceeded` / `setOnFailed` / `setOnCancelled` 핸들러 등록
- [ ] 페이지별 렌더링·파일 쓰기를 명시적 `ExecutorService`로 병렬화 (#7)
  - [ ] `Executors.newFixedThreadPool(availableProcessors - 1)` 전용 풀
  - [ ] `pageIndex`·`pageIndex` 읽기 전용 — 스레드 공유 안전 확인
  - [ ] 진행 콜백 내부 `Platform.runLater()` 래핑
  - [ ] `AtomicInteger`로 진행 카운터 관리
  - [ ] 페이지별 실패 시 스킵 + `CopyOnWriteArrayList`로 실패 목록 누적 (#9)
- [ ] 취소·예외 시 임시 디렉토리 정리 흐름 구현 (#4, #18)
  - [ ] 내보내기 중: `output/.export-tmp-<timestamp>/`에 쓰기
  - [ ] 완료: 임시 디렉토리 → 목표 디렉토리 `Files.move` (REPLACE_EXISTING)
  - [ ] `InterruptedException` catch → `Thread.currentThread().interrupt()` + `pool.shutdownNow()` + tmpDir 삭제
  - [ ] 기타 예외 catch → tmpDir 삭제 후 재throw
  - [ ] `finally`: `pool.shutdownNow()` 보장
- [ ] 순서 의존성 보장
  - [ ] `collectPages()` + `buildPageIndex()` 완료 후 병렬 렌더링 시작
  - [ ] 모든 페이지 완료 후 `removeOrphanHtmlFiles()` → `buildSearchIndex()` → `copyAssets()` 실행

### PDF 저장

- [ ] **Level 1** — `site-template.html` `@media print` CSS (위 HTML 내보내기 항목에 포함)
  - [ ] `🖨️ PDF 저장` 버튼 (`window.print()`) 추가
- [ ] **Level 2** — WikiBrowserDialog "PDF 저장" 버튼
  - [ ] PDF 키워드 포함 프린터 자동 탐색 → 없으면 OS 인쇄 대화상자 표시 (#6)
  - [ ] `PrinterJob` + `WebView.getEngine().print()` 구현
- [ ] *(선택)* **Level 3** — `openhtmltopdf` 일괄 변환
  - [ ] `build.gradle`에 `openhtmltopdf-pdfbox:1.0.10` 의존성 추가
  - [ ] `WikiSiteExporter.exportPageToPdf()` 구현
  - [ ] `WikiExportDialog`에 "PDF도 함께 내보내기" 옵션 추가

### 검증

- [ ] 워크스페이스 각 카테고리(sources/entities/concepts/syntheses) 페이지 정상 렌더링 확인
- [ ] `[[WikiLink]]` → 상대 HTML 경로 변환 정확성 확인
- [ ] 한글·공백 파일명 URL 인코딩 후 링크 정상 동작 확인 (#3)
- [ ] 이미지 `output/images/<원본상대경로>/` 복사 및 경로 재매핑 확인 (#2, #15)
- [ ] 같은 파일명 이미지(다른 디렉토리) 충돌 없이 복사 확인 (#15)
- [ ] `assetBase()` — index.html·카테고리 페이지별 상대 경로 정확성 확인 (#11)
- [ ] 페이지 제목 결정 우선순위 동작 확인 (frontmatter → # 헤딩 → stem) (#17)
- [ ] 없는 페이지 링크 처리 (dead link 표시) 확인
- [ ] 사이드바 현재 페이지 강조 + `<details>` 자동 열기 확인 (#8)
- [ ] 다크/라이트 테마 전환 확인
- [ ] 코드 블록 구문 하이라이팅 동작 확인 (다크/라이트 테마 분기) (#12)
- [ ] 리포트 페이지(health-report, lint-report) 내보내기 포함 확인 (#19)
- [ ] 재내보내기 시 orphan HTML 파일 삭제 확인 (#13)
- [ ] `graph/` 디렉토리 전체 복사 후 그래프 정상 동작 확인 (#14)
- [ ] 출력 경로 = 워크스페이스 하위 선택 시 경고 표시 확인 (#5)
- [ ] `cleanOutput=true` 시 삭제 확인 다이얼로그 표시 확인 (#5)
- [ ] 내보내기 중 "중단" → 임시 디렉토리 정리·원본 보존 확인 (#4, #18)
- [ ] 일부 페이지 렌더링 실패 시 스킵 후 완료 보고 확인 (#9)
- [ ] 대용량 워크스페이스(50페이지 이상) UI 블로킹 없음 확인 (#7)
- [ ] "브라우저에서 열기" 버튼 → index.html 정상 오픈 확인 (#20)
- [ ] `@media print` CSS + 브라우저 PDF 저장 결과 확인
- [ ] Level 2 PDF 프린터 자동 탐색 및 저장 결과 확인 (#6)
- [ ] 재실행 시 `wiki.exportDir` 기본값 복원 확인 (#10)
