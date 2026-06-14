/*
 * 작성자 : kyj
 * 작성일 : 2026-06-13
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ingest 전처리 구간에서 플러그인의 extract_pages.py를 실행해 PDF를
 * 페이지 단위 텍스트(.md)·이미지(.png) 자산으로 분해한다.
 *
 * LLM 호출이 없는 로컬 변환이므로 API 키 없이 동작한다.
 * PyMuPDF 미설치·파싱 실패 시 IOException을 던지며, 호출자
 * (WikiIngestPlanner)는 이를 받아 단독 작업 모드로 폴백한다.
 */
public class WikiPageExtractor {
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 페이지 렌더는 페이지 수에 비례해 오래 걸릴 수 있음 — 넉넉한 상한 */
    private static final long TIMEOUT_MINUTES = 10;

    /** 플러그인의 tools 디렉토리 (extract_pages.py 위치) */
    private final Path toolsDir;

    /** 앱 설정의 Python 명령어. 비어 있으면 "python" */
    private final String pythonCommand;

    public WikiPageExtractor(Path toolsDir, String pythonCommand) {
        this.toolsDir = toolsDir;
        this.pythonCommand = pythonCommand == null || pythonCommand.isBlank()
                ? "python" : pythonCommand.trim();
    }

    /**
     * PDF를 페이지 자산으로 분해하고 manifest를 파싱해 반환한다.
     *
     * @param source 분해할 PDF 파일
     * @param outputDir 페이지 자산을 쓸 디렉토리 (없으면 생성됨)
     * @param onOutput 진행 라인 출력 콜백
     * @return 페이지 자산 목록 (페이지 순서대로)
     * @throws IOException 도구 실패·시간 초과·manifest 파싱 실패 시
     */
    public List<WikiIngestPlanner.PageAsset> extract(Path source, Path outputDir,
                                                     Consumer<String> onOutput)
            throws IOException, InterruptedException {
        Path script = toolsDir.resolve("extract_pages.py");
        if (!Files.isRegularFile(script)) {
            throw new IOException("extract_pages.py가 없습니다: " + script);
        }

        ProcessBuilder pb = new ProcessBuilder(pythonCommand, script.toString(),
                source.toString(), outputDir.toString());
        pb.redirectErrorStream(true);
        // Windows 콘솔 인코딩 문제 방지 — Python 출력은 항상 UTF-8
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");

        onOutput.accept("$ " + pythonCommand + " " + script.getFileName()
                + " " + source.getFileName() + " → " + outputDir);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                onOutput.accept(line);
            }
        }
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("페이지 추출 시간 초과 (" + TIMEOUT_MINUTES + "분)");
        }
        if (process.exitValue() != 0) {
            String tail = output.length() > 500
                    ? output.substring(output.length() - 500) : output.toString();
            throw new IOException("페이지 추출 실패 (exit=" + process.exitValue() + "): "
                    + tail.trim());
        }

        return parseManifest(outputDir.resolve("manifest.json"));
    }

    /** manifest.json을 페이지 자산 목록으로 파싱한다. */
    private List<WikiIngestPlanner.PageAsset> parseManifest(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("manifest.json이 생성되지 않았습니다: " + manifest);
        }
        JsonNode root = mapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        List<WikiIngestPlanner.PageAsset> pages = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            pages.add(new WikiIngestPlanner.PageAsset(
                    item.path("page").asInt(),
                    item.path("text").asText(),
                    item.path("image").asText(),
                    item.path("text_chars").asInt()));
        }
        if (pages.isEmpty()) {
            throw new IOException("추출된 페이지가 없습니다: " + manifest);
        }
        return pages;
    }
}
