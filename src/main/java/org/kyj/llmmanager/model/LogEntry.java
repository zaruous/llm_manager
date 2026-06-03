/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 서비스 프로세스의 단일 로그 항목.
 * 생성 시각·출력 종류(STDOUT/STDERR/SYSTEM)·메시지를 담는다.
 */
public class LogEntry {

    /** 로그 시각 표시 형식 (HH:mm:ss) */
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 출력 종류: STDOUT(표준출력) / STDERR(에러출력) / SYSTEM(앱 내부 메시지) */
    public enum Level { STDOUT, STDERR, SYSTEM }

    /** 로그 생성 시각 */
    private final LocalDateTime time;

    /** 출력 종류: STDOUT(표준출력) / STDERR(에러출력) / SYSTEM(앱 내부 메시지) */
    private final Level level;

    /** 로그 메시지 본문 */
    private final String message;

    public LogEntry(Level level, String message) {
        this.time = LocalDateTime.now();
        this.level = level;
        this.message = message;
    }

    public LocalDateTime getTime() { return time; }
    public Level getLevel() { return level; }
    public String getMessage() { return message; }

    public String getTimeString() { return FMT.format(time); }

    /**
     * 로그 레벨에 따른 표시 색상을 CSS hex 문자열로 반환.
     *
     * @return CSS hex 색상 문자열 (예: #FF8888)
     */
    public String getColor() {
        return switch (level) {
            case STDERR -> "#FF8888";
            case SYSTEM -> "#88AAFF";
            default -> "#CCCCCC";
        };
    }
}
