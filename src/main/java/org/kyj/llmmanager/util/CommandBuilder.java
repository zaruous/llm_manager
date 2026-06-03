/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.util;

import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServiceDefinition과 ArgSpec을 기반으로 최종 실행 명령어 문자열을 조립한다.
 */
public class CommandBuilder {

    /**
     * startCommand에 활성화된 ArgSpec 값들을 CLI 플래그로 이어붙여 완성된 명령어를 반환한다.
     * BOOLEAN 타입은 true일 때만 플래그를 추가하고,
     * 나머지 타입은 --flag value 형식으로 추가한다.
     *
     * @param def 서비스 정의
     * @return 완성된 실행 명령어 문자열
     */
    public static String buildStartCommand(ServiceDefinition def) {
        StringBuilder sb = new StringBuilder(def.getStartCommand());
        Map<String, String> argValues = def.getArgValues();

        for (ArgSpec spec : def.getArgSpecs()) {
            if (!spec.isEnabled()) continue;
            String value = argValues.getOrDefault(spec.getName(), spec.getDefaultValue());
            if (value == null || value.isBlank()) continue;

            if ("BOOLEAN".equals(spec.getType())) {
                if ("true".equalsIgnoreCase(value)) {
                    sb.append(" ").append(spec.getFlag());
                }
            } else if (spec.getFlag().endsWith("=")) {
                // Spring Boot / JVM 스타일: --key=value (플래그 끝이 = 이면 값을 바로 붙임)
                sb.append(" ").append(spec.getFlag()).append(value);
            } else {
                // 표준 CLI 스타일: --flag value
                sb.append(" ").append(spec.getFlag()).append(" ").append(value);
            }
        }
        return sb.toString();
    }

    /**
     * 셸 명령어 문자열을 ProcessBuilder가 받을 수 있는 토큰 리스트로 분리한다.
     * 큰따옴표·작은따옴표 안의 공백은 분리하지 않는다.
     *
     * @param command 분리할 명령어 문자열
     * @return 토큰 리스트
     */
    public static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (char c : command.toCharArray()) {
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (c == ' ') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) parts.add(current.toString());
        return parts;
    }
}
