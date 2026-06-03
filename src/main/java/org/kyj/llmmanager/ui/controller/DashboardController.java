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
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
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

        // ── 메모리 ProgressBar + 수치 레이블 ──
        ProgressBar memBar = new ProgressBar(0);
        memBar.setMaxWidth(Double.MAX_VALUE);
        memBar.setPrefHeight(8);
        memBar.setStyle("-fx-accent: #5588bb; -fx-background-radius: 4; -fx-border-radius: 4;");

        Label memDetailLabel = new Label();
        memDetailLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #8888bb;");

        // ── Canvas 스파크라인 ──
        Canvas sparkline = new Canvas(182, 48);

        VBox memSection = new VBox(3, memBar, memDetailLabel, sparkline);
        // 항상 공간 유지
        resetMemSection(memBar, memDetailLabel, sparkline);

        // 메모리 갱신 함수 — ProgressBar·레이블·스파크라인 일괄 갱신
        Runnable updateMem = () -> {
            long rss     = inst.getMemoryBytes();
            long virtual = inst.getVirtualMemoryBytes();
            if (rss > 0 && inst.getStatus() == ServiceStatus.RUNNING) {
                memBar.setProgress(virtual > 0 ? (double) rss / virtual : 0);
                memBar.setStyle("-fx-accent: #5588bb; -fx-background-radius: 4; -fx-border-radius: 4;");
                memDetailLabel.setText(formatMemory(rss) + " / " + formatMemory(virtual));
                memDetailLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #8888bb;");
                drawSparkline(sparkline, inst.getMemoryHistory());
            } else {
                resetMemSection(memBar, memDetailLabel, sparkline);
            }
        };

        inst.memoryBytesProperty().addListener((obs, o, n) -> updateMem.run());

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
            if (newStatus != ServiceStatus.RUNNING) {
                inst.setMemoryBytes(0);
                resetMemSection(memBar, memDetailLabel, sparkline);
            }
            updateSummary(AppContext.getInstance().getProcessManager().getAllInstances());
        });

        // ── 카드 레이아웃 ──
        VBox card = new VBox(8, nameRow, statusLabel, portLabel, memSection, descLabel,
                new Separator(), actionBtn);
        card.setPadding(new Insets(14));
        card.setPrefWidth(230);
        card.setMaxWidth(230);
        card.setStyle(
                "-fx-background-color: #1e1e2e;" +
                "-fx-border-color: #2a2a3a;" +
                "-fx-border-radius: 6;" +
                "-fx-background-radius: 6;" +
                "-fx-cursor: hand;");
        VBox.setVgrow(descLabel, Priority.ALWAYS);

        // 카드 클릭 시 메모리 상세 팝업
        card.setOnMouseClicked(e -> showMemoryPopup(inst));

        return card;
    }

    /** 메모리 섹션을 미실행 플레이스홀더 상태로 초기화한다. */
    private void resetMemSection(ProgressBar bar, Label lbl, Canvas canvas) {
        bar.setProgress(0);
        bar.setStyle("-fx-accent: #3a3a4a; -fx-background-radius: 4; -fx-border-radius: 4;");
        lbl.setText("서비스 미실행");
        lbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #444455;");
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setStroke(Color.web("#2a2a3a"));
        gc.setLineWidth(1);
        gc.setLineDashes(4);
        gc.strokeLine(0, canvas.getHeight() / 2, canvas.getWidth(), canvas.getHeight() / 2);
        gc.setLineDashes(0);
    }

    /**
     * bytes를 사람이 읽기 쉬운 메모리 문자열로 변환한다.
     *
     * @param bytes 바이트 값
     * @return "1.2 GB" 형식 문자열
     */
    private String formatMemory(long bytes) {
        if (bytes <= 0) return "0 MB";
        if (bytes >= 1024L * 1024 * 1024)
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024)
            return String.format("%.0f MB", bytes / (1024.0 * 1024));
        return String.format("%.0f KB", bytes / 1024.0);
    }

    /**
     * RSS 이력 데이터를 Canvas에 채워진 스파크라인으로 그린다.
     * 데이터가 2개 미만이면 그리지 않는다.
     *
     * @param canvas  대상 Canvas
     * @param history RSS 이력 (bytes)
     */
    private void drawSparkline(Canvas canvas, List<Long> history) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);

        if (history.size() < 2) return;

        long max = history.stream().mapToLong(Long::longValue).max().orElse(1);
        if (max == 0) return;

        double xStep = w / (history.size() - 1);

        // 채우기
        gc.setFill(Color.web("#5588bb", 0.25));
        gc.beginPath();
        gc.moveTo(0, h);
        for (int i = 0; i < history.size(); i++) {
            double x = i * xStep;
            double y = h - (h * 0.9 * history.get(i) / max);
            gc.lineTo(x, y);
        }
        gc.lineTo(w, h);
        gc.closePath();
        gc.fill();

        // 꺾은선
        gc.setStroke(Color.web("#5588bb", 0.85));
        gc.setLineWidth(1.5);
        gc.beginPath();
        for (int i = 0; i < history.size(); i++) {
            double x = i * xStep;
            double y = h - (h * 0.9 * history.get(i) / max);
            if (i == 0) gc.moveTo(x, y); else gc.lineTo(x, y);
        }
        gc.stroke();

        // 최신 값 점
        int last = history.size() - 1;
        double lx = last * xStep;
        double ly = h - (h * 0.9 * history.get(last) / max);
        gc.setFill(Color.web("#88bbee"));
        gc.fillOval(lx - 3, ly - 3, 6, 6);
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
    // 메모리 상세 팝업
    // =========================================================

    /**
     * 서비스의 메모리 이력을 확대된 그래프로 표시하는 팝업을 연다.
     * 미실행 서비스는 팝업을 열지 않는다.
     *
     * @param inst 조회할 서비스 인스턴스
     */
    private void showMemoryPopup(ServiceInstance inst) {
        if (inst.getStatus() != ServiceStatus.RUNNING) return;

        javafx.stage.Stage popup = new javafx.stage.Stage();
        popup.setTitle(inst.getDefinition().getName() + " — 메모리 모니터링");
        popup.initModality(javafx.stage.Modality.NONE);

        // 상단 수치 요약
        long rss     = inst.getMemoryBytes();
        long virtual = inst.getVirtualMemoryBytes();

        Label titleLbl = new Label(inst.getDefinition().getName());
        titleLbl.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Label rssLbl = new Label("RSS(물리): " + formatMemory(rss));
        rssLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #88bbee;");

        Label totalLbl = new Label("가상 할당량: " + formatMemory(virtual));
        totalLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");

        ProgressBar bar = new ProgressBar(virtual > 0 ? (double) rss / virtual : 0);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(12);
        bar.setStyle("-fx-accent: #5588bb;");

        Label pctLbl = new Label(virtual > 0
                ? String.format("%.1f%% 상주 중", 100.0 * rss / virtual) : "");
        pctLbl.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");

        // 확대 스파크라인 캔버스
        Canvas bigChart = new Canvas(460, 160);
        drawSparkline(bigChart, inst.getMemoryHistory());

        Label hintLbl = new Label("최근 " + inst.getMemoryHistory().size() + "회 샘플 (10초 간격)");
        hintLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: #555566;");

        VBox root = new VBox(10, titleLbl, rssLbl, totalLbl, bar, pctLbl,
                new Separator(), bigChart, hintLbl);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #1e1e2e;");

        // 팝업 열려 있는 동안 10초마다 갱신
        inst.memoryBytesProperty().addListener((obs, o, n) -> {
            if (!popup.isShowing()) return;
            long r = n.longValue();
            long v = inst.getVirtualMemoryBytes();
            rssLbl.setText("RSS(물리): " + formatMemory(r));
            totalLbl.setText("가상 할당량: " + formatMemory(v));
            bar.setProgress(v > 0 ? (double) r / v : 0);
            pctLbl.setText(v > 0 ? String.format("%.1f%% 상주 중", 100.0 * r / v) : "");
            hintLbl.setText("최근 " + inst.getMemoryHistory().size() + "회 샘플 (10초 간격)");
            drawSparkline(bigChart, inst.getMemoryHistory());
        });

        popup.setScene(new javafx.scene.Scene(root, 500, 380));
        popup.show();
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
