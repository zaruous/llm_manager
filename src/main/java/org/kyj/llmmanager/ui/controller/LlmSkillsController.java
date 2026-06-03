/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.TabPane;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * LLM 스킬 & 룰 팝업 최상위 컨테이너 컨트롤러.
 * 설치 탭({@link LlmSkillsInstallController})과
 * 로드 탭({@link LlmSkillsLoadController})을 TabPane으로 감싸 하나의 창으로 제공한다.
 *
 * 각 탭의 실제 로직은 서브 컨트롤러가 담당하며,
 * FXMLLoader가 fx:include fx:id 규칙에 따라 자동으로 주입한다.
 */
public class LlmSkillsController implements Initializable {

    /** 설치 / 로드 탭을 담는 최상위 TabPane. */
    @FXML private TabPane tabPane;

    /** fx:id="install" 에 대응하는 서브 컨트롤러 (llm-skills-install.fxml). */
    @FXML private LlmSkillsInstallController installController;

    /** fx:id="load" 에 대응하는 서브 컨트롤러 (llm-skills-load.fxml). */
    @FXML private LlmSkillsLoadController loadController;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 서브 컨트롤러는 FXMLLoader가 자동으로 초기화한다
    }
}
