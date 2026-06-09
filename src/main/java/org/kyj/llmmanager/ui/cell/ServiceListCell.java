/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.cell;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.ServiceInstance;
import org.kyj.llmmanager.model.ServiceStatus;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.function.Consumer;

/**
 * 서비스 목록(ListView)의 각 행을 렌더링하는 셀.
 * 상태 색상 점, 이름, 상태 텍스트, 포트를 표시한다.
 * 서비스 상태 변경 시 자동으로 화면을 갱신한다.
 */
public class ServiceListCell extends ListCell<ServiceInstance> {

    /** 셀 전체 레이아웃 컨테이너 */
    private final HBox container;
    /** 서비스 상태를 색으로 표시하는 원형 도형 */
    private final Circle statusDot;
    /** 서비스 이름 레이블 */
    private final Label nameLabel;
    /** 상태 텍스트 레이블 (예: 실행중, 중지됨) */
    private final Label statusLabel;
    /** 포트 번호 레이블 (포트 없는 서비스는 숨김) */
    private final Label portLabel;
    /**
     * 서비스 상태 변경 시 셀을 갱신하는 리스너.
     * 재사용 시 이전 리스너를 반드시 제거해야 메모리 누수를 방지한다.
     */
    private ChangeListener<ServiceStatus> statusListener;
    /** 현재 이 셀에 바인딩된 ServiceInstance. 재활용 시 이전 리스너를 해제하는 데 필요. */
    private ServiceInstance boundItem;
    /** 제거 요청 시 호출할 콜백. MainController가 실제 제거 로직을 담당한다. */
    private final Consumer<ServiceInstance> onRemove;

    /**
     * @param onRemove 제거 메뉴 선택 시 호출할 콜백 (확인 다이얼로그 포함)
     */
    public ServiceListCell(Consumer<ServiceInstance> onRemove) {
        this.onRemove = onRemove;

        statusDot = new Circle(5);
        nameLabel = new Label();
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        portLabel = new Label();
        portLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");

        VBox textBox = new VBox(2, nameLabel, statusLabel, portLabel);
        container = new HBox(10, statusDot, textBox);
        container.setAlignment(Pos.CENTER_LEFT);
        container.setPadding(new Insets(8, 12, 8, 12));

        MenuItem renameItem = new MenuItem("이름 변경");
        renameItem.setOnAction(e -> handleRename());

        MenuItem removeItem = new MenuItem("제거");
        removeItem.setOnAction(e -> {
            ServiceInstance inst = getItem();
            if (inst != null) onRemove.accept(inst);
        });

        setContextMenu(new ContextMenu(renameItem, new SeparatorMenuItem(), removeItem));
    }

    /**
     * TextInputDialog로 새 이름을 입력받아 서비스 정의와 저장소에 반영한다.
     */
    private void handleRename() {
        ServiceInstance inst = getItem();
        if (inst == null) return;

        TextInputDialog dialog = new TextInputDialog(inst.getDefinition().getName());
        dialog.setTitle("이름 변경");
        dialog.setHeaderText(null);
        dialog.setContentText("새 이름:");
        dialog.showAndWait().ifPresent(newName -> {
            if (newName.isBlank()) return;
            inst.getDefinition().setName(newName.trim());
            AppContext.getInstance().getServiceRegistry().update(inst.getDefinition());
            // 셀 이름 레이블 즉시 갱신
            nameLabel.setText(newName.trim());
        });
    }

    /**
     * ListView 셀 재사용 시 호출. 이전 리스너를 제거하고 새 항목에 바인딩한다.
     *
     * @param item  바인딩할 ServiceInstance (비어 있으면 null)
     * @param empty 셀이 비어 있는지 여부
     */
    @Override
    protected void updateItem(ServiceInstance item, boolean empty) {
        super.updateItem(item, empty);

        // Remove old listener
        if (boundItem != null && statusListener != null) {
            boundItem.statusProperty().removeListener(statusListener);
            boundItem = null;
            statusListener = null;
        }

        if (empty || item == null) {
            setGraphic(null);
            return;
        }

        bindItem(item);
        setGraphic(container);
    }

    /**
     * 새 ServiceInstance를 셀에 연결하고 상태 변경 리스너를 등록한다.
     *
     * @param item 바인딩할 ServiceInstance
     */
    private void bindItem(ServiceInstance item) {
        boundItem = item;
        updateDisplay(item.getStatus(), item);

        statusListener = (obs, oldStatus, newStatus) -> updateDisplay(newStatus, item);
        item.statusProperty().addListener(statusListener);
    }

    /**
     * 현재 상태에 따라 색상 점, 라벨, 포트를 업데이트한다.
     *
     * @param status 표시할 서비스 상태
     * @param item   표시 대상 ServiceInstance
     */
    private void updateDisplay(ServiceStatus status, ServiceInstance item) {
        nameLabel.setText(item.getDefinition().getName());
        statusLabel.setText(status.getLabel());
        try {
            statusDot.setFill(Color.web(status.getColor()));
        } catch (Exception e) {
            statusDot.setFill(Color.GRAY);
        }
        Integer port = item.getDefinition().getPort();
        portLabel.setText(port != null ? ":" + port : "");
        portLabel.setVisible(port != null);
    }
}
