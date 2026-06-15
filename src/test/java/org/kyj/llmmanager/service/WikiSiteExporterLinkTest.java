/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kyj.llmmanager.service.WikiSiteExporter.ExportOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 내보낸 HTML 사이트의 링크 무결성을 검증한다.
 *
 * 모든 HTML 파일을 순회하며 href/src 속성값이 실제로 존재하는 파일을
 * 가리키는지 확인한다. 외부 URL·앵커(#)·data URI는 제외한다.
 */
class WikiSiteExporterLinkTest {

    // ─────────────────────────────────────────────
    // 워크스페이스 구성 헬퍼
    // ─────────────────────────────────────────────

    /**
     * 모든 카테고리와 교차 위키링크가 포함된 워크스페이스를 만든다.
     *
     * 링크 관계:
     *   index.md          → [[Overview]], [[Alpha]], [[Core]]
     *   overview.md       → [[Ref]]
     *   entities/Alpha.md → [[Beta]], [[Core]], [[Ref]]
     *   entities/Beta.md  → [[Alpha]]   (상호 참조)
     *   concepts/Core.md  → [[Alpha]]
     *   sources/Ref.md    → (링크 없음)
     */
    private Path buildWorkspace(Path root) throws IOException {
        Path wiki = root.resolve("wiki");
        for (String dir : List.of("entities", "concepts", "sources", "syntheses")) {
            Files.createDirectories(wiki.resolve(dir));
        }

        write(wiki.resolve("index.md"),
                "---\ntitle: 홈\n---\n# Index\n\n[[Overview]] · [[Alpha]] · [[Core]]");
        write(wiki.resolve("overview.md"),
                "# Overview\n\n참고: [[Ref]]");
        write(wiki.resolve("entities/Alpha.md"),
                "---\ntype: entity\nlast_updated: 2026-06\n---\n# Alpha\n\n[[Beta]] 참고. 개념은 [[Core]]. 출처는 [[Ref]].");
        write(wiki.resolve("entities/Beta.md"),
                "# Beta\n\n[[Alpha]]와 연관.");
        write(wiki.resolve("concepts/Core.md"),
                "# Core Concept\n\n핵심 개념. 엔티티: [[Alpha]]");
        write(wiki.resolve("sources/Ref.md"),
                "# Ref\n\n기본 출처 문서.");
        return root;
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────
    // 링크 추출 유틸
    // ─────────────────────────────────────────────

    /** HTML에서 href·src 속성 값을 모두 추출한다. */
    private static List<String> extractLinkValues(String html) {
        List<String> links = new ArrayList<>();
        Matcher m = Pattern.compile("(?:href|src)=\"([^\"]+)\"").matcher(html);
        while (m.find()) links.add(m.group(1));
        return links;
    }

    /**
     * 외부 URL·앵커·data URI를 제외한 순수 상대경로 링크만 반환한다.
     * 검증 대상: 실제로 파일이 존재해야 하는 링크.
     */
    private static List<String> filterRelativeLinks(List<String> links) {
        return links.stream()
                .filter(l -> !l.startsWith("http"))
                .filter(l -> !l.startsWith("//"))
                .filter(l -> !l.startsWith("#"))
                .filter(l -> !l.startsWith("data:"))
                .filter(l -> !l.startsWith("mailto:"))
                .filter(l -> !l.isBlank())
                .toList();
    }

    /**
     * HTML 파일 기준으로 상대경로를 해석해 절대 경로를 반환한다.
     */
    private static Path resolve(Path htmlFile, String relLink) {
        // 쿼리·앵커 제거
        String clean = relLink.split("[?#]")[0];
        return htmlFile.getParent().resolve(clean).normalize();
    }

    /**
     * 출력 디렉토리 내 모든 HTML 파일 경로를 반환한다.
     */
    private static List<Path> allHtmlFiles(Path outputDir) throws IOException {
        try (Stream<Path> walk = Files.walk(outputDir)) {
            return walk.filter(p -> p.toString().endsWith(".html"))
                       .filter(Files::isRegularFile)
                       .toList();
        }
    }

    // ─────────────────────────────────────────────
    // 핵심: 전체 링크 무결성 검사
    // ─────────────────────────────────────────────

    /**
     * 모든 HTML 파일의 모든 상대 링크(href/src)가 실제 파일을 가리키는지 검증한다.
     * 하나라도 깨진 링크가 있으면 실패한다.
     */
    @Test
    void allRelativeLinks_resolveToExistingFiles(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        List<String> broken = new ArrayList<>();

        for (Path htmlFile : allHtmlFiles(output)) {
            String html  = Files.readString(htmlFile, StandardCharsets.UTF_8);
            List<String> links = filterRelativeLinks(extractLinkValues(html));

            for (String link : links) {
                Path target = resolve(htmlFile, link);
                if (!Files.exists(target)) {
                    broken.add(htmlFile.getFileName() + " → " + link + " (해석됨: " + target + ")");
                }
            }
        }

        assertTrue(broken.isEmpty(),
                "깨진 링크 " + broken.size() + "개 발견:\n" + String.join("\n", broken));
    }

    // ─────────────────────────────────────────────
    // 네비게이션 링크 — 루트 vs 카테고리 페이지
    // ─────────────────────────────────────────────

    @Test
    void rootPage_navLinksUseRootRelativePaths(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html  = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        List<String> navLinks = filterRelativeLinks(extractLinkValues(html))
                .stream().filter(l -> l.endsWith(".html")).toList();

        // 루트 페이지에서 카테고리 페이지 링크는 "entities/Beta.html" 형태
        // "../entities/Beta.html" 형태면 상위로 나가므로 잘못된 것
        List<String> dotDotNavLinks = navLinks.stream()
                .filter(l -> l.startsWith("../"))
                .filter(l -> !l.contains("assets/"))  // assets는 제외
                .toList();
        assertTrue(dotDotNavLinks.isEmpty(),
                "루트 페이지 nav에 ../ 링크가 있음: " + dotDotNavLinks);
    }

    @Test
    void categoryPage_navLinksUseDotDotPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // entities/Alpha.html에서 다른 카테고리로 가는 nav 링크
        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);

        // entities 페이지에서 sources 페이지로의 링크는 ../sources/Ref.html 이어야 함
        assertTrue(html.contains("../sources/Ref.html"),
                "entities 페이지에서 sources 링크가 ../sources/ 형태여야 함");
        // entities 페이지에서 루트 페이지로의 링크는 ../index.html 이어야 함
        assertTrue(html.contains("../index.html"),
                "entities 페이지에서 index 링크가 ../index.html 이어야 함");
        // 이중 카테고리(sources/sources/) 없어야 함
        assertFalse(html.contains("sources/sources/"),
                "이중 카테고리 경로 발생");
        assertFalse(html.contains("entities/entities/"),
                "이중 카테고리 경로 발생");
    }

    @Test
    void allCategoryPages_navLinksResolveToActualFiles(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // 카테고리 디렉토리 내 모든 페이지 검사
        for (Path htmlFile : allHtmlFiles(output)) {
            if (htmlFile.getParent().equals(output)) continue; // 루트 페이지 제외

            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            List<String> navLinks = filterRelativeLinks(extractLinkValues(html))
                    .stream().filter(l -> l.endsWith(".html")).toList();

            for (String link : navLinks) {
                Path target = resolve(htmlFile, link);
                assertTrue(Files.exists(target),
                        htmlFile.getFileName() + "의 nav 링크 " + link + " → " + target + " 파일 없음");
            }
        }
    }

    // ─────────────────────────────────────────────
    // 위키링크(WikiLink) 변환 결과 검증
    // ─────────────────────────────────────────────

    @Test
    void wikiLink_fromRootToCategory_correctHref(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // index.md의 [[Alpha]] → index.html에서 entities/Alpha.html
        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("entities/Alpha.html"),
                "루트→카테고리 위키링크가 entities/Alpha.html 이어야 함");
        // 실제로 파일이 존재해야 함
        assertTrue(Files.exists(output.resolve("entities/Alpha.html")));
    }

    @Test
    void wikiLink_fromCategoryToRoot_correctHref(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // overview.md의 [[Ref]] → overview.html에서 sources/Ref.html
        String html = Files.readString(output.resolve("overview.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("sources/Ref.html"),
                "루트 페이지의 위키링크는 category/page.html 형태여야 함");
        assertTrue(Files.exists(resolve(output.resolve("overview.html"), "sources/Ref.html")));
    }

    @Test
    void wikiLink_fromCategoryToSameCategory_correctHref(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // entities/Alpha.md의 [[Beta]] → entities/Alpha.html에서 ../entities/Beta.html
        //   또는 Beta.html (같은 디렉토리이므로)
        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        // Beta.html을 포함하는 href가 존재해야 함
        assertTrue(html.contains("Beta.html"),
                "같은 카테고리 위키링크에 Beta.html 참조 없음");
        // 그 href가 실제 파일로 해석되어야 함
        Matcher m = Pattern.compile("href=\"([^\"]*Beta\\.html)\"").matcher(html);
        assertTrue(m.find(), "Beta.html href 없음");
        Path betaTarget = resolve(output.resolve("entities/Alpha.html"), m.group(1));
        assertTrue(Files.exists(betaTarget),
                "Beta.html href(" + m.group(1) + ")이 실제 파일로 해석되지 않음: " + betaTarget);
    }

    @Test
    void wikiLink_fromCategoryToDifferentCategory_correctHref(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // entities/Alpha.md의 [[Ref]] → entities/Alpha.html에서 ../sources/Ref.html
        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("href=\"([^\"]*Ref\\.html)\"").matcher(html);
        assertTrue(m.find(), "Ref.html href 없음");
        Path refTarget = resolve(output.resolve("entities/Alpha.html"), m.group(1));
        assertTrue(Files.exists(refTarget),
                "Ref.html href(" + m.group(1) + ")이 실제 파일로 해석되지 않음: " + refTarget);
    }

    @Test
    void wikiLink_missingTarget_noDeadHtmlHref(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        write(wiki.resolve("index.md"), "# Index\n\n[[없는페이지]] 참조.");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        // 없는 페이지는 <a href>가 아닌 <span>으로 처리되어야 함
        // href="#" 또는 href가 없어야 함 → .html 링크가 없어야 함
        assertFalse(html.contains("없는페이지.html"),
                "없는 페이지에 대한 .html href가 생성되면 안됨");
    }

    @Test
    void wikiLink_withAlias_usesLabelNotTargetName(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki.resolve("entities"));
        write(wiki.resolve("index.md"), "# Index\n\n[[Alpha|알파 엔티티]]");
        write(wiki.resolve("entities/Alpha.md"), "# Alpha");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        // 링크 텍스트는 "알파 엔티티", href는 Alpha.html을 가리켜야 함
        assertTrue(html.contains("알파 엔티티"), "alias 텍스트 없음");
        assertTrue(html.contains("Alpha.html"),  "alias 링크 href 없음");
    }

    // ─────────────────────────────────────────────
    // 자산 링크 (style.css, nav.js)
    // ─────────────────────────────────────────────

    @Test
    void assetLinks_inRootPage_resolveToFiles(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        for (String link : filterRelativeLinks(extractLinkValues(html))) {
            if (link.contains("assets/")) {
                Path target = resolve(output.resolve("index.html"), link);
                assertTrue(Files.exists(target),
                        "루트 페이지 자산 링크 " + link + " → " + target + " 없음");
            }
        }
    }

    @Test
    void assetLinks_inCategoryPage_resolveToFiles(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        for (Path htmlFile : allHtmlFiles(output)) {
            if (htmlFile.getParent().equals(output)) continue; // 카테고리 페이지만

            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            for (String link : filterRelativeLinks(extractLinkValues(html))) {
                if (link.contains("assets/")) {
                    Path target = resolve(htmlFile, link);
                    assertTrue(Files.exists(target),
                            htmlFile.getFileName() + " 자산 링크 " + link + " → " + target + " 없음");
                }
            }
        }
    }

    @Test
    void assetBase_depth0_noPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // 루트 페이지: href="assets/style.css" (../ 없음)
        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("\"assets/style.css\"") || html.contains("href=\"assets/"),
                "루트 페이지 스타일시트가 assets/ 로 시작해야 함");
        assertFalse(html.contains("href=\"../assets/"),
                "루트 페이지에 ../assets/ 가 있으면 안됨");
    }

    @Test
    void assetBase_depth1_dotDotPrefix(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        // 카테고리 페이지: href="../assets/style.css"
        String html = Files.readString(output.resolve("entities/Alpha.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("\"../assets/style.css\"") || html.contains("href=\"../assets/"),
                "카테고리 페이지 스타일시트가 ../assets/ 로 시작해야 함");
        assertFalse(html.contains("href=\"assets/style.css\""),
                "카테고리 페이지에 ../ 없는 assets/ 링크가 있으면 안됨");
    }

    // ─────────────────────────────────────────────
    // 이미지 링크
    // ─────────────────────────────────────────────

    @Test
    void localImage_srcResolvesToCopiedFile(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        // 더미 PNG 생성
        byte[] pngHeader = {(byte)0x89, 0x50, 0x4E, 0x47};
        Files.write(wiki.resolve("diagram.png"), pngHeader);
        write(wiki.resolve("index.md"), "# Index\n\n![다이어그램](diagram.png)");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);

        // <img src="..."> 에서 경로 추출
        Matcher m = Pattern.compile("src=\"([^\"]+\\.png)\"").matcher(html);
        assertTrue(m.find(), "img src 없음");
        Path imgTarget = resolve(output.resolve("index.html"), m.group(1));
        assertTrue(Files.exists(imgTarget),
                "이미지 src " + m.group(1) + " → " + imgTarget + " 파일 없음");
    }

    @Test
    void externalImage_srcUnchanged(@TempDir Path tmp) throws IOException {
        Path wiki = tmp.resolve("ws/wiki");
        Files.createDirectories(wiki);
        write(wiki.resolve("index.md"), "# Index\n\n![외부](https://example.com/img.png)");
        Path output = tmp.resolve("out");

        new WikiSiteExporter().export(tmp.resolve("ws"), output, ExportOptions.defaults(), null);

        String html = Files.readString(output.resolve("index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("https://example.com/img.png"),
                "외부 이미지 URL이 변경되면 안됨");
    }

    // ─────────────────────────────────────────────
    // 검색 인덱스 내 href
    // ─────────────────────────────────────────────

    @Test
    void searchIndex_hrefValuesResolveFromOutputRoot(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        ExportOptions opts = new ExportOptions("dark", true, true, false, false, false);
        new WikiSiteExporter().export(workspace, output, opts, null);

        Path indexFile = output.resolve("assets/search-index.json");
        assertTrue(Files.isRegularFile(indexFile), "search-index.json 없음");

        String json = Files.readString(indexFile, StandardCharsets.UTF_8);

        // "href":"..." 값 추출
        Matcher m = Pattern.compile("\"href\":\"([^\"]+)\"").matcher(json);
        List<String> broken = new ArrayList<>();
        while (m.find()) {
            String href = m.group(1);
            Path target = output.resolve(href);
            if (!Files.exists(target)) broken.add(href + " → " + target);
        }
        assertTrue(broken.isEmpty(),
                "search-index.json의 깨진 href " + broken.size() + "개:\n" + String.join("\n", broken));
    }

    // ─────────────────────────────────────────────
    // active 링크 자기 참조
    // ─────────────────────────────────────────────

    @Test
    void activeNavLink_alwaysResolvesToSameFile(@TempDir Path tmp) throws IOException {
        Path workspace = buildWorkspace(tmp.resolve("ws"));
        Path output    = tmp.resolve("out");
        new WikiSiteExporter().export(workspace, output, ExportOptions.defaults(), null);

        for (Path htmlFile : allHtmlFiles(output)) {
            String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
            // active 링크의 href 추출
            Matcher m = Pattern.compile("href=\"([^\"]+)\" class=\"active\"").matcher(html);
            if (!m.find()) continue; // active 링크 없으면 다음 파일

            String href = m.group(1);
            Path target = resolve(htmlFile, href);
            assertTrue(Files.exists(target),
                    htmlFile.getFileName() + "의 active 링크 " + href + " 가 존재하지 않음: " + target);
            // active 링크가 자기 자신을 가리켜야 함
            assertEquals(htmlFile.toAbsolutePath().normalize(),
                    target.toAbsolutePath().normalize(),
                    htmlFile.getFileName() + "의 active 링크가 자기 자신을 가리켜야 함");
        }
    }
}
