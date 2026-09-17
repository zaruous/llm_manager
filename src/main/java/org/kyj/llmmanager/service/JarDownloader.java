/*
 * 작성자 : kyj
 * 작성일 : 2026-09-12
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CancellationException;

/**
 * JAR 파일을 URL에서 내려받아 지정한 디렉토리에 저장한다.
 *
 * <p>설치 탭의 자동 설치와 서비스 설정 다이얼로그의 '다운로드' 버튼이 같은 동작을
 * 공유하도록 분리했다. UI에 의존하지 않으므로 호출하는 쪽에서 {@link Progress}로
 * 진행 상황을 받아 원하는 방식으로 표시하면 된다.
 */
public class JarDownloader {

    /** 진행 상황 콜백. 호출 스레드(대개 백그라운드 스레드)에서 실행된다. */
    public interface Progress {
        /**
         * 진행 로그 한 줄.
         *
         * @param message 표시할 메시지
         */
        void onLog(String message);

        /**
         * 진행률 갱신.
         *
         * @param ratio 0.0~1.0. 전체 크기를 알 수 없으면 -1 (부정확 진행)
         */
        void onProgress(double ratio);
    }

    /**
     * 다운로드 취소 신호. 호출하는 쪽에서 만들어 넘기고, 취소 버튼에서 {@link #cancel()}을
     * 호출하면 된다.
     *
     * <p>HTTP 본문 스트림의 {@code read()}는 스레드 인터럽트에 확실히 반응하지 않으므로
     * 인터럽트 대신 플래그를 읽기 루프에서 확인한다. 한 번에 64KB씩 읽으므로 취소는
     * 거의 즉시 반영된다.
     */
    public static class Cancellation {
        private volatile boolean cancelled;

        /** 진행 중인 다운로드를 취소한다. 이미 끝났으면 아무 효과가 없다. */
        public void cancel() { cancelled = true; }

        /**
         * @return 취소가 요청되었으면 true
         */
        public boolean isCancelled() { return cancelled; }
    }

    /** 진행률을 5% 단위로만 알린다 — 매 청크마다 알리면 UI 이벤트가 넘친다. */
    private static final int PERCENT_STEP = 5;

    /** 읽기 버퍼 크기 (64KB) */
    private static final int BUFFER_SIZE = 1 << 16;

    /**
     * JAR을 내려받아 {@code targetDir}에 저장한다. 호출 스레드에서 동기 실행되므로
     * UI 스레드에서 직접 호출하면 안 된다.
     *
     * <p>받는 중에는 {@code .part} 임시 파일에 쓰고 완료 후 정식 이름으로 옮긴다.
     * 중간에 실패하면 정식 파일이 만들어지지 않으므로, 깨진 JAR이 설치된 것으로
     * 오인되지 않는다.
     *
     * @param url       JAR 다운로드 URL (리다이렉트 허용)
     * @param targetDir 저장할 디렉토리. 없으면 생성한다.
     * @param progress  진행 상황 콜백. null이면 무시한다.
     * @return 저장된 JAR 경로
     * @throws IOException          HTTP 오류 응답, URL 형식 오류, 파일 쓰기 실패 시
     * @throws InterruptedException 다운로드 중 스레드가 인터럽트된 경우
     */
    public Path download(String url, Path targetDir, Progress progress)
            throws IOException, InterruptedException {
        return download(url, targetDir, progress, null);
    }

    /**
     * 취소 가능한 다운로드. 동작은 {@link #download(String, Path, Progress)}와 같고,
     * {@code cancel}이 취소 상태가 되면 받다 만 {@code .part} 파일을 지우고
     * {@link CancellationException}을 던진다.
     *
     * @param url       JAR 다운로드 URL (리다이렉트 허용)
     * @param targetDir 저장할 디렉토리. 없으면 생성한다.
     * @param progress  진행 상황 콜백. null이면 무시한다.
     * @param cancel    취소 신호. null이면 취소 불가.
     * @return 저장된 JAR 경로
     * @throws IOException           HTTP 오류 응답, URL 형식 오류, 파일 쓰기 실패 시
     * @throws InterruptedException  다운로드 중 스레드가 인터럽트된 경우
     * @throws CancellationException 취소가 요청된 경우
     */
    public Path download(String url, Path targetDir, Progress progress, Cancellation cancel)
            throws IOException, InterruptedException {

        String fileName = fileNameFromUrl(url);
        Path dest = targetDir.resolve(fileName);
        Path part = targetDir.resolve(fileName + ".part");

        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
            log(progress, "디렉토리 생성: " + targetDir);
        }

        log(progress, "다운로드: " + url);
        log(progress, "   →  " + dest);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)   // GitHub 릴리즈는 CDN으로 리다이렉트된다
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "llm-manager")
                .GET()
                .build();

        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            try (InputStream body = res.body()) { body.readAllBytes(); }   // 연결 정리
            throw new IOException("다운로드 실패 — HTTP " + res.statusCode() + " : " + url);
        }

        long total = res.headers().firstValueAsLong("content-length").orElse(-1L);
        long done = 0;
        int lastPercent = -1;

        if (total <= 0) notifyProgress(progress, -1);

        try (InputStream in = res.body();
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancel != null && cancel.isCancelled()) {
                    throw new CancellationException("다운로드를 취소했습니다.");
                }
                out.write(buf, 0, n);
                done += n;

                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent >= lastPercent + PERCENT_STEP) {
                        lastPercent = percent;
                        notifyProgress(progress, percent / 100.0);
                        log(progress, "  " + percent + "% ("
                                + toMb(done) + "/" + toMb(total) + " MB)");
                    }
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }

        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        notifyProgress(progress, 1.0);
        log(progress, "다운로드 완료 (" + toMb(Files.size(dest)) + " MB)");
        return dest;
    }

    /**
     * 다운로드 URL의 마지막 경로 세그먼트를 파일명으로 사용한다.
     *
     * @param url 다운로드 URL
     * @return JAR 파일명
     * @throws IOException URL 끝이 .jar 파일명이 아닐 때
     */
    public String fileNameFromUrl(String url) throws IOException {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException e) {
            throw new IOException("다운로드 URL 형식이 올바르지 않습니다: " + url);
        }
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (!name.toLowerCase().endsWith(".jar")) {
            throw new IOException("다운로드 URL이 .jar 파일을 가리키지 않습니다: " + url);
        }
        return name;
    }

    /**
     * 자동 다운로드 실패 시 사용자에게 보여줄 안내문을 만든다.
     *
     * <p>사내망 프록시 등으로 앱의 HttpClient 연결이 막혀도(예: {@code Permission denied: getsockopt})
     * 브라우저로는 같은 URL을 받을 수 있는 경우가 많다. 원인만 보여주면 사용자가 막히므로,
     * 직접 내려받아 설치 경로에 넣는 우회 절차를 함께 알려준다.
     *
     * @param cause     실패 원인 예외
     * @param url       시도한 다운로드 URL
     * @param targetDir JAR을 넣어야 할 설치 경로
     * @return 여러 줄 안내문
     */
    public static String failureMessage(Throwable cause, String url, Path targetDir) {
        String reason = cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
        return "실패: " + reason + "\n"
                + "브라우저에서 아래 URL로 직접 내려받을 수 있습니다:\n"
                + "  " + url + "\n"
                + "받은 JAR을 설치 경로에 넣고 저장(추가)하면 '설치됨'으로 인식되어 시작할 수 있습니다:\n"
                + "  " + targetDir;
    }

    private static long toMb(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static void log(Progress progress, String message) {
        if (progress != null) progress.onLog(message);
    }

    private static void notifyProgress(Progress progress, double ratio) {
        if (progress != null) progress.onProgress(ratio);
    }
}
