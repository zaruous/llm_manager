/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

/**
 * 서비스가 실행될 런타임 종류.
 * CommandBuilder가 이 값을 참고해 실행 명령을 구성한다.
 */
public enum RuntimeType {
    PYTHON("Python"),
    NODE("Node.js"),
    JAVA("Java"),
    SHELL("Shell");

    /** UI 표시 이름 */
    private final String label;

    RuntimeType(String label) { this.label = label; }

    public String getLabel() { return label; }
}
