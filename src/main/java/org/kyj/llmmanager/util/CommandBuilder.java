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
            if (spec.getFlag() == null || spec.getFlag().isBlank()) continue;
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

    /**
     * startCommand에서 {@code -jar} 다음 토큰(JAR 파일명)을 돌려준다.
     *
     * @param startCommand 시작 명령어. null 허용
     * @return JAR 파일명. {@code -jar} 토큰이 없으면 null
     */
    public static String jarFileName(String startCommand) {
        List<String> tokens = splitCommand(startCommand);
        for (int i = 0; i < tokens.size() - 1; i++) {
            if ("-jar".equalsIgnoreCase(tokens.get(i))) return tokens.get(i + 1);
        }
        return null;
    }

    /**
     * startCommand의 {@code -jar} 다음 토큰을 지정한 JAR 파일명으로 교체한 명령어를 반환한다.
     * 설치·직접 배치한 JAR이 설정에 등록된 파일명과 다를 때(버전 업데이트 등) 사용한다.
     *
     * @param startCommand 원본 시작 명령어
     * @param jarFileName  교체할 JAR 파일명
     * @return 교체된 명령어. -jar 토큰이 없거나 이미 같은 파일명이면 null(변경 불필요).
     */
    public static String withJarFileName(String startCommand, String jarFileName) {
        if (startCommand == null || startCommand.isBlank()) return null;
        List<String> tokens = new ArrayList<>(splitCommand(startCommand));
        for (int i = 0; i < tokens.size() - 1; i++) {
            if ("-jar".equalsIgnoreCase(tokens.get(i))) {
                if (jarFileName.equals(tokens.get(i + 1))) return null;
                // 공백 포함 파일명은 다시 토큰화될 때 깨지지 않도록 따옴표 처리
                tokens.set(i + 1, jarFileName.contains(" ")
                        ? "\"" + jarFileName + "\"" : jarFileName);
                return String.join(" ", tokens);
            }
        }
        return null;
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
