/*
 * 작성자 : kyj
 * 작성일 : 2026-09-28
 */
package org.kyj.llmmanager.util;

import org.kyj.llmmanager.model.LogEntry;

/**
 * 로그 탭 TextArea에 넣을 문자열을 묶음 단위로 만드는 순수 유틸.
 *
 * TextArea.appendText()는 호출마다 텍스트 변경 이벤트와 레이아웃 갱신이 따라와 비용이
 * 누적 텍스트 길이에 비례한다. 로그를 한 줄씩 붙이면 5000줄 되감기가 O(n²)이 되어
 * FX 스레드가 수 초 멈추므로(서비스 선택 시 "응답 없음"), 문자열을 먼저 합쳐 한 번에 넣는다.
 * JavaFX 의존이 없어 툴킷 없이 단위 테스트할 수 있다.
 */
public final class LogTextUtil {

    private LogTextUtil() {}

    /**
     * 합쳐진 로그 텍스트와 그 안의 줄 수.
     *
     * @param text  각 줄이 '\n'으로 끝나는 텍스트. 매칭 줄이 없으면 빈 문자열
     * @param lines text에 포함된 줄 수
     */
    public record Rendered(String text, int lines) {}

    /**
     * 로그 항목들을 "[시각] 메시지\n" 형식으로 이어 붙인다.
     * filter가 비어 있지 않으면 메시지에 filter를 포함한 항목만 남긴다 (대소문자 구분).
     *
     * @param entries 이어 붙일 로그 항목
     * @param filter  포함 검색어. null 또는 공백이면 전체
     * @return 합쳐진 텍스트와 줄 수
     */
    public static Rendered render(Iterable<? extends LogEntry> entries, String filter) {
        boolean filtering = filter != null && !filter.isBlank();
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (LogEntry entry : entries) {
            if (filtering && !entry.getMessage().contains(filter)) continue;
            sb.append('[').append(entry.getTimeString()).append("] ")
              .append(entry.getMessage()).append('\n');
            lines++;
        }
        return new Rendered(sb.toString(), lines);
    }

    /**
     * 텍스트 앞에서 lines줄을 잘라낸 나머지를 돌려준다. 줄 수가 부족하면 빈 문자열.
     *
     * @param text  각 줄이 '\n'으로 끝나는 텍스트
     * @param lines 잘라낼 줄 수
     * @return 앞 lines줄을 제거한 텍스트
     */
    public static String dropLeadingLines(String text, int lines) {
        int idx = 0;
        for (int i = 0; i < lines; i++) {
            int next = text.indexOf('\n', idx);
            if (next == -1) return "";
            idx = next + 1;
        }
        return text.substring(idx);
    }
}
