/*
 * 작성자 : kyj
 * 작성일 : 2026-09-12
 */
package org.kyj.llmmanager.ui.controller;

import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 제거 확인 창의 응답 처리 규칙 검증.
 *
 * <p>취소했는데 제거가 진행되던 회귀를 막는다.
 */
class UninstallChoiceTest {

    @Test
    @DisplayName("'예'를 골라야만 제거한다")
    void yesDeletes() {
        assertEquals(MainController.UninstallChoice.DELETE,
                MainController.uninstallChoice(Optional.of(ButtonType.YES)));
    }

    @Test
    @DisplayName("취소는 제거하지 않는다")
    void cancelAborts() {
        assertEquals(MainController.UninstallChoice.ABORT,
                MainController.uninstallChoice(Optional.of(ButtonType.CANCEL)));
    }

    @Test
    @DisplayName("창을 그냥 닫으면 제거하지 않는다")
    void dismissAborts() {
        assertEquals(MainController.UninstallChoice.ABORT,
                MainController.uninstallChoice(Optional.empty()));
    }

    @Test
    @DisplayName("'아니오'도 제거하지 않는다 — 예전에는 파일만 남기고 제거로 처리했다")
    void noAborts() {
        assertEquals(MainController.UninstallChoice.ABORT,
                MainController.uninstallChoice(Optional.of(ButtonType.NO)));
    }
}
