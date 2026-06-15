/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.ServiceDefinition;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * 기본 제공 서비스 선택 다이얼로그 컨트롤러.
 * lib/def/ 에서 로드된 서비스 목록을 표시하고 선택한 서비스로 추가 폼을 열거나
 * 직접 입력을 선택할 수 있다.
 */
public class BuiltinServicesController {

    /** 기본 제공 서비스 목록을 표시하는 ListView */
    @FXML private ListView<ServiceDefinition> builtinListView;
    /** 선택 서비스의 이름 레이블 */
    @FXML private Label detailName;
    /** 선택 서비스의 설명 레이블 */
    @FXML private Label detailDesc;
    /** 선택 서비스의 런타임 레이블 */
    @FXML private Label detailRuntime;
    /** 선택 서비스의 포트 레이블 */
    @FXML private Label detailPort;
    /** 선택 서비스의 저장소 URL 레이블 */
    @FXML private Label detailRepo;
    /** 선택 서비스의 시작 명령어 레이블 */
    @FXML private Label detailStartCmd;
    /** 선택 서비스의 설치 명령어 미리보기 */
    @FXML private TextArea detailInstallCmds;
    /** 선택 서비스의 실행 인수 목록 컨테이너 */
    @FXML private VBox detailArgsBox;
    /** '이 서비스로 추가' 버튼. 서비스가 선택되지 않으면 비활성. */
    @FXML private Button selectBtn;

    /** 사용자가 선택한 ServiceDefinition. 취소·직접입력이면 null. */
    private ServiceDefinition selected;
    /** 사용자가 '직접 입력' 버튼을 눌렀는지 여부 */
    private boolean manualRequested = false;

    @FXML
    public void initialize() {
        builtinListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServiceDefinition item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName());
                }
            }
        });

        builtinListView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, def) -> showDetail(def));
    }

    /**
     * 표시할 기본 제공 서비스 목록을 설정하고 첫 항목을 자동 선택한다.
     *
     * @param items 표시할 ServiceDefinition 목록
     */
    public void setItems(List<ServiceDefinition> items) {
        builtinListView.getItems().setAll(items);
        if (!items.isEmpty()) {
            builtinListView.getSelectionModel().selectFirst();
        }
    }

    /**
     * 선택한 ServiceDefinition의 상세 정보를 우측 패널에 표시한다.
     *
     * @param def 표시할 ServiceDefinition (null이면 selectBtn 비활성)
     */
    private void showDetail(ServiceDefinition def) {
        if (def == null) {
            selectBtn.setDisable(true);
            return;
        }
        selected = def;
        selectBtn.setDisable(false);

        detailName.setText(def.getName());
        detailDesc.setText(def.getDescription() != null ? def.getDescription() : "");
        detailRuntime.setText(def.getRuntimeType() != null ? def.getRuntimeType().name() : "-");
        detailPort.setText(def.getPort() != null ? String.valueOf(def.getPort()) : "-");
        detailRepo.setText(def.getRepoUrl() != null ? def.getRepoUrl() : "-");
        detailStartCmd.setText(def.getStartCommand() != null ? def.getStartCommand() : "-");

        detailInstallCmds.setText(
                def.getInstallCommands() != null && !def.getInstallCommands().isEmpty()
                        ? String.join("\n", def.getInstallCommands())
                        : "");

        detailArgsBox.getChildren().clear();
        if (def.getArgSpecs() != null) {
            for (ArgSpec spec : def.getArgSpecs()) {
                HBox row = new HBox(8);
                row.setPadding(new Insets(2, 0, 2, 0));
                String keyText = (spec.getFlag() != null && !spec.getFlag().isBlank())
                        ? spec.getFlag()
                        : spec.getName();
                Label flag = new Label(keyText);
                flag.setMinWidth(120);
                flag.setStyle("-fx-text-fill: #88aadd; -fx-font-family: monospace;");
                Label desc = new Label(spec.getDescription()
                        + " (기본값: " + spec.getDefaultValue() + ")");
                desc.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
                row.getChildren().addAll(flag, desc);
                detailArgsBox.getChildren().add(row);
            }
        }
    }

    /**
     * 선택한 서비스로 다이얼로그를 닫는다. getSelected()로 선택 결과를 가져갈 수 있다.
     */
    @FXML
    private void onSelect() {
        close();
    }

    /**
     * 직접 입력 모드를 요청하고 다이얼로그를 닫는다. isManualRequested()로 확인.
     */
    @FXML
    private void onManualInput() {
        manualRequested = true;
        selected = null;
        close();
    }

    @FXML
    private void onCancel() {
        selected = null;
        close();
    }

    private void close() {
        ((Stage) selectBtn.getScene().getWindow()).close();
    }

    public ServiceDefinition getSelected() { return selected; }
    public boolean isManualRequested() { return manualRequested; }
}
