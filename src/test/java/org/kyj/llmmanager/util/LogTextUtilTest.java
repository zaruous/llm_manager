/*
 * 작성자 : kyj
 * 작성일 : 2026-09-28
 */
package org.kyj.llmmanager.util;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.LogEntry;
import org.kyj.llmmanager.model.ServiceInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 로그 탭 묶음 렌더링 유틸과 로그 버퍼 상한 상수를 검증한다.
 */
class LogTextUtilTest {

    private static List<LogEntry> entries(String... messages) {
        List<LogEntry> list = new ArrayList<>();
        for (String m : messages) list.add(new LogEntry(LogEntry.Level.STDOUT, m));
        return list;
    }

    @Test
    void renderJoinsEveryEntryWithTimestampAndNewline() {
        LogTextUtil.Rendered r = LogTextUtil.render(entries("a", "b", "c"), null);
        assertEquals(3, r.lines());
        String[] lines = r.text().split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].matches("\\[.+\\] a"), lines[0]);
        assertTrue(r.text().endsWith("\n"));
    }

    @Test
    void renderAppliesContainsFilterAndCountsOnlyMatches() {
        LogTextUtil.Rendered r = LogTextUtil.render(entries("SELECT 1", "warn", "SELECT 2"), "SELECT");
        assertEquals(2, r.lines());
        assertFalse(r.text().contains("warn"));
        // 공백 필터는 전체 통과
        assertEquals(3, LogTextUtil.render(entries("SELECT 1", "warn", "SELECT 2"), "  ").lines());
    }

    @Test
    void renderOfEmptyInputIsEmpty() {
        LogTextUtil.Rendered r = LogTextUtil.render(List.of(), null);
        assertEquals(0, r.lines());
        assertEquals("", r.text());
    }

    @Test
    void dropLeadingLinesRemovesExactlyNLines() {
        assertEquals("c\n", LogTextUtil.dropLeadingLines("a\nb\nc\n", 2));
        assertEquals("a\nb\nc\n", LogTextUtil.dropLeadingLines("a\nb\nc\n", 0));
        // 줄 수가 부족하면 빈 문자열 (기존 구현은 남은 텍스트를 통째로 남겨 줄 수 계산이 어긋났다)
        assertEquals("", LogTextUtil.dropLeadingLines("a\nb\n", 5));
    }

    @Test
    void trimKeepsTextAreaLineCountConsistent() {
        // MainController.appendLogEntries의 잘라내기 규칙: 상한 초과 시 MAX - TRIM 행으로 맞춘다
        int max = ServiceInstance.MAX_LOG_LINES, trim = ServiceInstance.LOG_TRIM_LINES;
        StringBuilder sb = new StringBuilder();
        int count = max + 700;
        for (int i = 0; i < count; i++) sb.append("line ").append(i).append('\n');
        int drop = count - (max - trim);
        String kept = LogTextUtil.dropLeadingLines(sb.toString(), drop);
        assertEquals(max - trim, kept.split("\n").length);
        assertTrue(kept.startsWith("line " + drop + "\n"));
    }

    @Test
    void serviceInstanceBufferHonoursSharedLimit() {
        ServiceInstance inst = new ServiceInstance(new org.kyj.llmmanager.model.ServiceDefinition());
        for (int i = 0; i <= ServiceInstance.MAX_LOG_LINES; i++) {
            inst.addLog(new LogEntry(LogEntry.Level.STDOUT, "m" + i));
        }
        assertEquals(ServiceInstance.MAX_LOG_LINES + 1, inst.getLogs().size());
        inst.addLog(new LogEntry(LogEntry.Level.STDOUT, "overflow"));
        assertEquals(ServiceInstance.MAX_LOG_LINES + 2 - ServiceInstance.LOG_TRIM_LINES, inst.getLogs().size());
        assertEquals("m" + ServiceInstance.LOG_TRIM_LINES, inst.getLogs().get(0).getMessage());
    }
}
