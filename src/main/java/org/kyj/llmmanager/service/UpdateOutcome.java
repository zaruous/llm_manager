/*
 * 작성자 : kyj
 * 작성일 : 2026-09-14
 */
package org.kyj.llmmanager.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * 업데이트 시도의 사전 마커(pending)와 교체 스크립트가 남긴 결과 마커(result)를 읽고 써서,
 * 재기동된 앱이 지난 업데이트의 성공·실패를 사용자에게 알릴 수 있게 한다.
 *
 * <p>두 마커는 attemptId 로 짝을 맞춘다 — 사용자가 여러 번 시도했을 때 이전 시도의 낡은 result 를
 * 새 시도의 결과로 오인하지 않기 위해서다. 알림은 한 번만 뜨도록 {@link #consume} 이 마커를 지운다.
 */
public final class UpdateOutcome {

    /** 설치 직전에 앱이 쓰는 마커 — 어떤 버전을 기대하고 종료했는지 */
    public static final String PENDING_FILE = "pending.properties";
    /** 교체 스크립트가 마지막에 쓰는 마커 — 성공/실패와 단계 */
    public static final String RESULT_FILE = "result.properties";
    /** 교체 스크립트의 단계별 로그 */
    public static final String LOG_FILE = "update.log";

    /** 지난 업데이트 시도의 판정. */
    public enum Kind { SUCCESS, FAILED, INCOMPLETE, VERSION_MISMATCH }

    /**
     * 사용자에게 보여줄 판정 결과.
     *
     * @param kind            판정
     * @param expectedVersion 시도 당시 기대했던 버전
     * @param message         표시용 메시지 (로그 경로 포함)
     * @param logPath         스크립트 로그 경로
     */
    public record Report(Kind kind, String expectedVersion, String message, Path logPath) {}

    private UpdateOutcome() {}

    /**
     * 설치 스크립트를 띄우기 직전에 pending 마커를 기록한다. 이전 시도의 result 가 남아 있으면 지운다.
     *
     * @param dir             작업 디렉토리
     * @param attemptId       이번 시도 식별자
     * @param expectedVersion 설치 후 기대 버전 (v 접두사 없이)
     * @param installDir      교체 대상 설치 디렉토리
     * @throws IOException 파일 쓰기 실패 시
     */
    public static void writePending(Path dir, String attemptId, String expectedVersion, Path installDir)
            throws IOException {
        Files.createDirectories(dir);
        Files.deleteIfExists(dir.resolve(RESULT_FILE));
        Properties p = new Properties();
        p.setProperty("attemptId", attemptId);
        p.setProperty("expectedVersion", expectedVersion);
        p.setProperty("installDir", installDir.toString());
        p.setProperty("startedAt", java.time.Instant.now().toString());
        try (OutputStream out = Files.newOutputStream(dir.resolve(PENDING_FILE))) {
            p.store(out, "llm-manager update pending");
        }
    }

    /**
     * 앱 기동 시 호출. pending 마커가 있으면 result 와 대조해 판정을 만들고 두 마커를 지운다.
     *
     * @param dir            작업 디렉토리
     * @param currentVersion 현재 실행 중인 앱 버전 (app.properties 의 app.version)
     * @return 판정. pending 마커가 없으면 empty
     */
    public static Optional<Report> consume(Path dir, String currentVersion) {
        Path pendingFile = dir.resolve(PENDING_FILE);
        if (!Files.isRegularFile(pendingFile)) return Optional.empty();

        Properties pending = load(pendingFile);
        Properties result  = load(dir.resolve(RESULT_FILE));
        Path logPath = dir.resolve(LOG_FILE);
        String attemptId = pending.getProperty("attemptId", "");
        String expected  = pending.getProperty("expectedVersion", "?");

        Report report;
        // result 가 다른 시도의 것이면 이번 시도는 결과 없이 끝난 것으로 본다
        boolean sameAttempt = result != null && attemptId.equals(result.getProperty("attemptId"));
        if (!sameAttempt) {
            report = new Report(Kind.INCOMPLETE, expected,
                    "이전 업데이트(v" + expected + ")가 완료되지 않았습니다.\n"
                    + "업데이트 스크립트가 실행되지 않았거나 도중에 중단된 것으로 보입니다.\n로그: " + logPath,
                    logPath);
        } else if ("success".equals(result.getProperty("status"))) {
            if (sameVersion(currentVersion, expected)) {
                report = new Report(Kind.SUCCESS, expected,
                        "업데이트가 완료되었습니다 (v" + expected + ").", logPath);
            } else {
                report = new Report(Kind.VERSION_MISMATCH, expected,
                        "업데이트 스크립트는 성공을 기록했지만 실행 중인 버전이 v" + currentVersion
                        + " 입니다 (기대: v" + expected + ").\n"
                        + "다른 위치의 설치본이 실행되었을 수 있습니다.\n로그: " + logPath,
                        logPath);
            }
        } else {
            String step = result.getProperty("step", "?");
            String rc   = result.getProperty("robocopyExit");
            report = new Report(Kind.FAILED, expected,
                    "업데이트에 실패했습니다 (단계: " + step
                    + (rc != null ? ", robocopy exit=" + rc : "") + ").\n로그: " + logPath,
                    logPath);
        }

        // 한 번만 알리기 위해 마커를 지운다. 로그는 남긴다
        try {
            Files.deleteIfExists(pendingFile);
            Files.deleteIfExists(dir.resolve(RESULT_FILE));
        } catch (IOException ignored) {
            // 삭제 실패 시 다음 기동에 한 번 더 알림이 뜨는 정도라 치명적이지 않다
        }
        return Optional.of(report);
    }

    /**
     * 두 버전 문자열이 같은지 비교한다. 'v' 접두사와 앞뒤 공백은 무시한다.
     *
     * @param a 버전 문자열
     * @param b 버전 문자열
     * @return 같으면 true
     */
    static boolean sameVersion(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String v) {
        if (v == null) return "";
        String s = v.strip();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    /** properties 파일을 읽는다. 없거나 읽기 실패면 null. */
    private static Properties load(Path file) {
        if (!Files.isRegularFile(file)) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }
}
