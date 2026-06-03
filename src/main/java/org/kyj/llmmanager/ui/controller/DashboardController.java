/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.controller;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.ServiceInstance;
import org.kyj.llmmanager.model.ServiceStatus;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.List;

/**
 * 대시보드 컨트롤러.
 * 등록된 서비스 목록을 카드 형태로 표시하고, 상태 변경을 실시간으로 반영한다.
 * 각 카드에서 서비스를 직접 시작·중지할 수 있다.
 */
public class DashboardController {

    /** 요약 헤더 - 전체 서비스 수 */
    @FXML private Label totalLabel;
    /** 요약 헤더 - 실행 중 서비스 수 */
    @FXML private Label runningLabel;
    /** 요약 헤더 - 중지된 서비스 수 */
    @FXML private Label stoppedLabel;
    /** 요약 헤더 - 오류 서비스 수 */
    @FXML private Label errorLabel;
    /** 서비스 카드를 배치하는 FlowPane */
    @FXML private FlowPane cardPane;

    @FXML
    public void initialize() {
        buildCards();
    }

    /**
     * 현재 서비스 목록을 기반으로 카드를 생성하고 배치한다.
     * 각 카드는 ServiceInstance의 statusProperty를 구독해 실시간으로 갱신된다.
     */
    private void buildCards() {
        cardPane.getChildren().clear();
        List<ServiceInstance> instances =
                AppContext.getInstance().getProcessManager().getAllInstances();

        for (ServiceInstance inst : instances) {
            cardPane.getChildren().add(createCard(inst));
        }
        updateSummary(instances);
    }

    /**
     * 서비스 인스턴스 하나에 대한 카드 VBox를 생성한다.
     * statusProperty 리스너로 상태 변경 시 색상·버튼을 자동 갱신한다.
     *
     * @param inst 카드를 만들 서비스 인스턴스
     * @return 완성된 카드 VBox
     */
    private VBox createCard(ServiceInstance inst) {
        // ── 상태 표시 점 ──
        Circle dot = new Circle(6);
        applyDotColor(dot, inst.getStatus());

        // ── 서비스 이름 ──
        Label nameLabel = new Label(inst.getDefinition().getName());
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(160);

        HBox nameRow = new HBox(8, dot, nameLabel);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // ── 상태 텍스트 ──
        Label statusLabel = new Label(inst.getStatus().getLabel());
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #aaaaaa;");

        // ── 포트 ──
        Label portLabel = new Label(
                inst.getDefinition().getPort() != null
                        ? "Port: " + inst.getDefinition().getPort()
                        : "");
        portLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666688;");

        // ── 설명 ──
        Label descLabel = new Label(
                inst.getDefinition().getDescription() != null
                        ? inst.getDefinition().getDescription()
                        : "");
        descLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(180);

        // ── 시작/중지 버튼 ──
        Button actionBtn = new Button(actionLabel(inst.getStatus()));
        actionBtn.setStyle(actionStyle(inst.getStatus()));
        actionBtn.setMaxWidth(Double.MAX_VALUE);
        actionBtn.setOnAction(e -> handleAction(inst));

        // ── statusProperty 구독 → 카드 실시간 갱신 ──
        inst.statusProperty().addListener((obs, old, newStatus) -> {
            applyDotColor(dot, newStatus);
            statusLabel.setText(newStatus.getLabel());
            actionBtn.setText(actionLabel(newStatus));
            actionBtn.setStyle(actionStyle(newStatus));
            updateSummary(AppContext.getInstance().getProcessManager().getAllInstances());
        });

        // ── 카드 레이아웃 ──
        VBox card = new VBox(8, nameRow, statusLabel, portLabel, descLabel,
                new Separator(), actionBtn);
        card.setPadding(new Insets(14));
        card.setPrefWidth(210);
        card.setMaxWidth(210);
        card.setStyle(
                "-fx-background-color: #1e1e2e;" +
                "-fx-border-color: #2a2a3a;" +
                "-fx-border-radius: 6;" +
                "-fx-background-radius: 6;");
        VBox.setVgrow(descLabel, Priority.ALWAYS);

        return card;
    }

    /** 상태에 따라 상태 점 색상을 설정한다. */
    private void applyDotColor(Circle dot, ServiceStatus status) {
        try {
            dot.setFill(Color.web(status.getColor()));
        } catch (Exception ignored) {
            dot.setFill(Color.GRAY);
        }
    }

    /** 상태에 따라 시작/중지 버튼 레이블을 반환한다. */
    private String actionLabel(ServiceStatus status) {
        return status == ServiceStatus.RUNNING ? "■  중지" : "▶  시작";
    }

    /** 상태에 따라 버튼 인라인 스타일을 반환한다. */
    private String actionStyle(ServiceStatus status) {
        return status == ServiceStatus.RUNNING
                ? "-fx-background-color: #5a2d2d; -fx-text-fill: #dd8888; -fx-background-radius: 4;"
                : "-fx-background-color: #2d5a2d; -fx-text-fill: #88dd88; -fx-background-radius: 4;";
    }

    /**
     * 버튼 클릭 시 서비스 상태에 따라 시작 또는 중지를 실행한다.
     *
     * @param inst 대상 서비스 인스턴스
     */
    private void handleAction(ServiceInstance inst) {
        AppContext ctx = AppContext.getInstance();
        if (inst.getStatus() == ServiceStatus.RUNNING) {
            ctx.getProcessManager().stop(inst);
        } else if (inst.getStatus() == ServiceStatus.STOPPED
                || inst.getStatus() == ServiceStatus.INSTALLED
                || inst.getStatus() == ServiceStatus.ERROR) {
            ctx.getProcessManager().start(inst);
        }
    }

    /**
     * 요약 헤더(전체·실행중·중지·오류 수)를 갱신한다.
     *
     * @param instances 현재 서비스 인스턴스 목록
     */
    private void updateSummary(List<ServiceInstance> instances) {
        long running = instances.stream().filter(i -> i.getStatus() == ServiceStatus.RUNNING).count();
        long error   = instances.stream().filter(i -> i.getStatus() == ServiceStatus.ERROR).count();
        long stopped = instances.stream()
                .filter(i -> i.getStatus() == ServiceStatus.STOPPED
                        || i.getStatus() == ServiceStatus.INSTALLED).count();

        totalLabel  .setText("전체 " + instances.size() + "개");
        runningLabel.setText("실행 중 " + running + "개");
        stoppedLabel.setText("중지 " + stopped + "개");
        errorLabel  .setText("오류 " + error + "개");

        // 오류 없으면 오류 레이블 숨김
        errorLabel.setVisible(error > 0);
        errorLabel.setManaged(error > 0);
    }

    // =========================================================
    // 하단 버튼 액션
    // =========================================================

    /** 설치된 모든 서비스를 시작한다. */
    @FXML
    private void onStartAll() {
        AppContext ctx = AppContext.getInstance();
        ctx.getProcessManager().getAllInstances().stream()
                .filter(i -> i.getStatus() == ServiceStatus.STOPPED
                        || i.getStatus() == ServiceStatus.INSTALLED
                        || i.getStatus() == ServiceStatus.ERROR)
                .forEach(ctx.getProcessManager()::start);
    }

    /** 실행 중인 모든 서비스를 중지한다. */
    @FXML
    private void onStopAll() {
        AppContext ctx = AppContext.getInstance();
        ctx.getProcessManager().getAllInstances().stream()
                .filter(i -> i.getStatus() == ServiceStatus.RUNNING)
                .forEach(ctx.getProcessManager()::stop);
    }

    /** 카드를 다시 빌드해 최신 서비스 목록을 반영한다. */
    @FXML
    private void onRefresh() {
        buildCards();
    }
}
