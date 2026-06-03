/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.model;

/**
 * 서비스 인스턴스의 생명주기 상태.
 * UI 표시용 라벨과 색상을 함께 보유한다.
 */
public enum ServiceStatus {
    NOT_INSTALLED("미설치", "#888888"),
    INSTALLING("설치중", "#FFA500"),
    INSTALLED("설치됨", "#5599FF"),
    STARTING("시작중", "#AADDFF"),
    RUNNING("실행중", "#44BB44"),
    STOPPING("종료중", "#FFAA44"),
    STOPPED("중지됨", "#AAAAAA"),
    ERROR("오류", "#EE4444");

    /** 화면에 표시되는 상태 한국어 이름 */
    private final String label;

    /** 상태 표시 색상 (CSS hex) */
    private final String color;

    ServiceStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String getLabel() { return label; }
    public String getColor() { return color; }
}
