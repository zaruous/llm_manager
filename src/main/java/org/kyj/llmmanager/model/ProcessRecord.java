/*
 * 작성자 : kyj
 * 작성일 : 2026-06-13
 */
package org.kyj.llmmanager.model;

/**
 * 에이전트 실행 이력 레코드.
 *
 * %TEMP%/llm-manager/records/ 에 JSON으로 영속화한다.
 * 실행 중에는 status=RUNNING, 종료 시 최종 상태로 갱신된다.
 * Jackson 역직렬화를 위해 public 필드 + 기본 생성자 방식을 사용한다.
 */
public class ProcessRecord {

    /** 실행 상태. */
    public enum Status {
        /** 에이전트가 실행 중. */
        RUNNING,
        /** 정상 종료 (exit=0, success=true). */
        COMPLETED,
        /** 오류 종료 (exit≠0 또는 success=false). */
        FAILED,
        /** 사용자 중지. */
        CANCELLED,
        /** 타임아웃으로 강제 종료. */
        TIMEOUT
    }

    /** node 프로세스 PID. */
    public long pid;

    /** 실행 추적 키 (activeProcesses 맵 키와 동일 — 주로 command id). */
    public String trackKey;

    /** wiki.ingest / wiki.query / cursor.runAgent 등 command id. */
    public String commandId;

    /** 에이전트가 실행된 워크스페이스 경로. */
    public String cwd;

    /** 전달된 프롬프트 앞 200자 요약 — 전문 저장 시 파일이 비대해짐. */
    public String promptSummary;

    /** 실행 상태 이름 (Status enum). */
    public String status;

    /** 실행 시작 시각 (ISO_LOCAL_DATE_TIME). */
    public String startTime;

    /** 실행 종료 시각 (ISO_LOCAL_DATE_TIME). null = 실행 중. */
    public String endTime;

    /** 프로세스 종료 코드. null = 실행 중. */
    public Integer exitCode;

    /** 최종 결과 메시지 (최대 500자). null = 실행 중. */
    public String result;

    /** 실행 소요 시간(ms). null = 실행 중. */
    public Long durationMs;

    /** Jackson 역직렬화용 기본 생성자. */
    public ProcessRecord() {
    }

    /**
     * 실행 시작 시 RUNNING 레코드를 생성한다.
     *
     * @param pid node 프로세스 PID
     * @param trackKey 추적 키
     * @param commandId 실행된 command id
     * @param cwd 워크스페이스 경로
     * @param promptSummary 프롬프트 요약
     * @param startTime 시작 시각 (ISO_LOCAL_DATE_TIME)
     * @return RUNNING 상태 레코드
     */
    public static ProcessRecord running(long pid, String trackKey, String commandId,
                                        String cwd, String promptSummary, String startTime) {
        ProcessRecord r = new ProcessRecord();
        r.pid = pid;
        r.trackKey = trackKey;
        r.commandId = commandId;
        r.cwd = cwd;
        r.promptSummary = promptSummary;
        r.status = Status.RUNNING.name();
        r.startTime = startTime;
        return r;
    }

    /**
     * 실행 종료 시 최종 상태로 갱신한 사본을 반환한다.
     *
     * @param finalStatus 최종 상태
     * @param exitCode 프로세스 종료 코드
     * @param result 결과 메시지 (null 가능)
     * @param endTime 종료 시각 (ISO_LOCAL_DATE_TIME)
     * @param durationMs 소요 시간(ms)
     * @return 갱신된 레코드
     */
    public ProcessRecord finalize(Status finalStatus, int exitCode, String result,
                                  String endTime, long durationMs) {
        ProcessRecord r = new ProcessRecord();
        r.pid = this.pid;
        r.trackKey = this.trackKey;
        r.commandId = this.commandId;
        r.cwd = this.cwd;
        r.promptSummary = this.promptSummary;
        r.status = finalStatus.name();
        r.startTime = this.startTime;
        r.endTime = endTime;
        r.exitCode = exitCode;
        r.result = result != null && result.length() > 500
                ? result.substring(0, 500) + "…" : result;
        r.durationMs = durationMs;
        return r;
    }
}
