/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.util;

import org.apache.commons.exec.CommandLine;
import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.util.ArrayList;
import java.util.Arrays;
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
                sb.append(" ").append(spec.getFlag()).append(quoteIfNeeded(value));
            } else {
                // 표준 CLI 스타일: --flag value
                sb.append(" ").append(spec.getFlag()).append(" ").append(quoteIfNeeded(value));
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
        if (command == null || command.isBlank()) {
            return List.of();
        }
        CommandLine parsed = CommandLine.parse(command);
        List<String> parts = new ArrayList<>();
        parts.add(unquote(parsed.getExecutable()));
        Arrays.stream(parsed.getArguments()).map(CommandBuilder::unquote).forEach(parts::add);
        return parts;
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\'", "'");
        }
        return value;
    }

    private static String quoteIfNeeded(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuote = value.chars().anyMatch(Character::isWhitespace)
                || value.contains("\"")
                || value.contains("'");
        if (!needsQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
