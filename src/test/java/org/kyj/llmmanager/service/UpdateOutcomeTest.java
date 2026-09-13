/*
 * 작성자 : kyj
 * 작성일 : 2026-09-14
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpdateOutcome의 pending/result 마커 짝짓기와 판정 메시지를 검증한다.
 */
class UpdateOutcomeTest {

    private static void writeResult(Path dir, Map<String, String> values) throws IOException {
        Properties p = new Properties();
        p.putAll(values);
        try (OutputStream out = Files.newOutputStream(dir.resolve(UpdateOutcome.RESULT_FILE))) {
            p.store(out, null);
        }
    }

    @Test
    void consume_withoutPending_isEmpty(@TempDir Path dir) {
        assertTrue(UpdateOutcome.consume(dir, "1.2.1").isEmpty());
    }

    @Test
    void successWithMatchingVersion_isSuccess(@TempDir Path dir) throws IOException {
        UpdateOutcome.writePending(dir, "a1", "1.2.1", dir);
        writeResult(dir, Map.of("status", "success", "attemptId", "a1", "robocopyExit", "1"));

        Optional<UpdateOutcome.Report> r = UpdateOutcome.consume(dir, "1.2.1");

        assertTrue(r.isPresent());
        assertEquals(UpdateOutcome.Kind.SUCCESS, r.get().kind());
        assertTrue(r.get().message().contains("1.2.1"));
    }

    @Test
    void successButOldVersionRunning_isVersionMismatch(@TempDir Path dir) throws IOException {
        UpdateOutcome.writePending(dir, "a1", "1.2.1", dir);
        writeResult(dir, Map.of("status", "success", "attemptId", "a1"));

        UpdateOutcome.Report r = UpdateOutcome.consume(dir, "1.2.0").orElseThrow();

        assertEquals(UpdateOutcome.Kind.VERSION_MISMATCH, r.kind());
        assertTrue(r.message().contains("1.2.0") && r.message().contains("1.2.1"));
    }

    @Test
    void failedResult_reportsStepAndRobocopyExit(@TempDir Path dir) throws IOException {
        UpdateOutcome.writePending(dir, "a1", "1.2.1", dir);
        writeResult(dir, Map.of("status", "failed", "attemptId", "a1", "step", "robocopy", "robocopyExit", "16"));

        UpdateOutcome.Report r = UpdateOutcome.consume(dir, "1.2.0").orElseThrow();

        assertEquals(UpdateOutcome.Kind.FAILED, r.kind());
        assertTrue(r.message().contains("robocopy"));
        assertTrue(r.message().contains("16"));
        assertTrue(r.message().contains(UpdateOutcome.LOG_FILE));
    }

    @Test
    void resultFromAnotherAttempt_isIncomplete(@TempDir Path dir) throws IOException {
        // 사용자가 두 번 시도했을 때 이전 시도의 result를 새 시도의 결과로 오인하지 않아야 한다
        UpdateOutcome.writePending(dir, "second", "1.2.1", dir);
        writeResult(dir, Map.of("status", "success", "attemptId", "first"));

        UpdateOutcome.Report r = UpdateOutcome.consume(dir, "1.2.1").orElseThrow();

        assertEquals(UpdateOutcome.Kind.INCOMPLETE, r.kind());
    }

    @Test
    void noResultAtAll_isIncomplete(@TempDir Path dir) throws IOException {
        UpdateOutcome.writePending(dir, "a1", "1.2.1", dir);
        UpdateOutcome.Report r = UpdateOutcome.consume(dir, "1.2.0").orElseThrow();
        assertEquals(UpdateOutcome.Kind.INCOMPLETE, r.kind());
        assertTrue(r.message().contains("완료되지 않았습니다"));
    }

    @Test
    void consume_deletesMarkersSoItReportsOnce(@TempDir Path dir) throws IOException {
        UpdateOutcome.writePending(dir, "a1", "1.2.1", dir);
        writeResult(dir, Map.of("status", "success", "attemptId", "a1"));

        assertTrue(UpdateOutcome.consume(dir, "1.2.1").isPresent());

        assertFalse(Files.exists(dir.resolve(UpdateOutcome.PENDING_FILE)));
        assertFalse(Files.exists(dir.resolve(UpdateOutcome.RESULT_FILE)));
        assertTrue(UpdateOutcome.consume(dir, "1.2.1").isEmpty(), "두 번째 호출은 알릴 것이 없어야 한다");
    }

    @Test
    void writePending_removesStaleResultFromPreviousAttempt(@TempDir Path dir) throws IOException {
        writeResult(dir, Map.of("status", "success", "attemptId", "old"));
        UpdateOutcome.writePending(dir, "new", "1.2.1", dir);
        assertFalse(Files.exists(dir.resolve(UpdateOutcome.RESULT_FILE)));
        assertTrue(Files.exists(dir.resolve(UpdateOutcome.PENDING_FILE)));
    }

    @Test
    void sameVersion_ignoresPrefixAndWhitespace() {
        assertTrue(UpdateOutcome.sameVersion("v1.2.1", " 1.2.1 "));
        assertTrue(UpdateOutcome.sameVersion("V1.2.1", "1.2.1"));
        assertFalse(UpdateOutcome.sameVersion("1.2.1", "1.2.10"));
        assertFalse(UpdateOutcome.sameVersion(null, "1.2.1"));
    }
}
