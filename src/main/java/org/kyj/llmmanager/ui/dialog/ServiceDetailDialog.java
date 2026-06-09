/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.ui.dialog;

import org.kyj.llmmanager.AppContext;
import org.kyj.llmmanager.model.ArgSpec;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.model.ServiceInstance;
import org.kyj.llmmanager.util.CommandBuilder;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;

/**
 * 서비스 상세 정보 팝업 다이얼로그.
 * 서비스 목록에서 더블클릭 시 열리며, 기본 정보·실행 인수·환경 변수·명령어 미리보기를 표시한다.
 * '수정' 버튼으로 AddServiceDialog를 열어 설정을 변경할 수 있다.
 */
public class ServiceDetailDialog {

    /** 부모 Stage */
    private final Stage owner;
    /** 조회할 서비스 인스턴스 */
    private final ServiceInstance instance;
    /** 앱 컨텍스트 (수정 저장 시 사용) */
    private final AppContext ctx;
    /** 수정 저장 후 호출할 콜백 (UI 갱신) */
    private final Runnable onUpdated;

    public ServiceDetailDialog(Stage owner, ServiceInstance instance,
                               AppContext ctx, Runnable onUpdated) {
        this.owner     = owner;
        this.instance  = instance;
        this.ctx       = ctx;
        this.onUpdated = onUpdated;
    }

    /** 다이얼로그를 표시한다. */
    public void show() {
        Stage stage = new Stage();
        ServiceDefinition def = instance.getDefinition();

        VBox root = new VBox(14);
        root.setPadding(new Insets(20));

        // ── 헤더: 상태 점 + 이름 + 상태 ──────────────────────────────
        Circle dot = new Circle(7);
        try { dot.setFill(Color.web(instance.getStatus().getColor())); }
        catch (Exception e) { dot.setFill(Color.GRAY); }

        Label nameLabel = new Label(def.getName());
        nameLabel.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Label statusLabel = new Label(instance.getStatus().getLabel());
        statusLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");

        HBox header = new HBox(10, dot, nameLabel, statusLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(header, new Separator());

        // ── 기본 정보 섹션 ────────────────────────────────────────────
        root.getChildren().add(sectionLabel("기본 정보"));
        GridPane infoGrid = infoGrid();
        int row = 0;
        addInfoRow(infoGrid, row++, "설명",       nvl(def.getDescription()));
        addInfoRow(infoGrid, row++, "런타임",     def.getRuntimeType() != null ? def.getRuntimeType().name() : "-");
        addInfoRow(infoGrid, row++, "저장소",     nvl(def.getRepoUrl()));
        addInfoRow(infoGrid, row++, "설치 경로",  nvl(def.getInstallDir()));
        addInfoRow(infoGrid, row++, "작업 디렉토리", nvl(def.getWorkingDir()));
        addInfoRow(infoGrid, row++, "시작 명령어", nvl(def.getStartCommand()));
        addInfoRow(infoGrid, row++, "포트",       def.getPort() != null ? String.valueOf(def.getPort()) : "-");
        addInfoRow(infoGrid, row,   "헬스체크",   nvl(def.getHealthCheckPath()));
        root.getChildren().add(infoGrid);

        // ── 실행 인수 섹션 ─────────────────────────────────────────────
        // 서비스에 argSpecs가 없으면 builtin 정의에서 fallback으로 가져온다
        List<ArgSpec> argSpecs = def.getArgSpecs();
        if (argSpecs.isEmpty() && ctx != null) {
            argSpecs = ctx.getServicePackLoader().loadAll().stream()
                    .filter(b -> b.getName().equals(def.getName()))
                    .findFirst()
                    .map(ServiceDefinition::getArgSpecs)
                    .orElse(List.of());
        }
        if (!argSpecs.isEmpty()) {
            root.getChildren().addAll(new Separator(), sectionLabel("실행 인수"));

            // 4열: [●/○ 활성] [플래그명] [현재 값] [설명]
            GridPane argGrid = new GridPane();
            argGrid.setHgap(10);
            argGrid.setVgap(6);

            ColumnConstraints dotCol  = new ColumnConstraints(16);
            ColumnConstraints flagCol = new ColumnConstraints(185, 185, 185);
            ColumnConstraints valCol  = new ColumnConstraints(100, 130, Double.MAX_VALUE);
            valCol.setHgrow(Priority.ALWAYS);
            ColumnConstraints descCol = new ColumnConstraints(100, 130, Double.MAX_VALUE);
            descCol.setHgrow(Priority.SOMETIMES);
            descCol.setFillWidth(true);
            argGrid.getColumnConstraints().addAll(dotCol, flagCol, valCol, descCol);

            // 헤더 행
            argGrid.add(styledLabel("",       "arg-header"), 0, 0);
            argGrid.add(styledLabel("플래그",  "arg-header"), 1, 0);
            argGrid.add(styledLabel("현재 값", "arg-header"), 2, 0);
            argGrid.add(styledLabel("설명",    "arg-header"), 3, 0);

            int argRow = 1;
            for (ArgSpec spec : argSpecs) {
                boolean on  = spec.isEnabled();
                String  val = def.getArgValues().getOrDefault(spec.getName(),
                                    spec.getDefaultValue());

                // 활성 표시 점
                Label dotLbl = new Label(on ? "●" : "○");
                dotLbl.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                        + (on ? "#44BB44" : "#555566") + ";");

                // 플래그 — 활성 여부에 따라 CSS 클래스 분기
                Label flagLbl = new Label(spec.getFlag());
                flagLbl.getStyleClass().add(on ? "arg-flag" : "arg-flag-disabled");

                // 값
                Label valLbl = new Label(val != null ? val : "-");
                valLbl.setStyle("-fx-font-size: 11px;"
                        + (on ? "" : " -fx-text-fill: #555566;"));
                valLbl.setWrapText(true);

                // 설명
                Label descLbl = new Label(spec.getDescription() != null ? spec.getDescription() : "");
                descLbl.getStyleClass().add("arg-desc");
                descLbl.setWrapText(true);
                descLbl.setMaxWidth(Double.MAX_VALUE);

                argGrid.add(dotLbl,  0, argRow);
                argGrid.add(flagLbl, 1, argRow);
                argGrid.add(valLbl,  2, argRow);
                argGrid.add(descLbl, 3, argRow);
                argRow++;
            }
            ScrollPane argScroll = new ScrollPane(argGrid);
            argScroll.setFitToWidth(true);
            argScroll.setMaxHeight(220);
            argScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            argScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            argScroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
            root.getChildren().add(argScroll);
        }

        // ── 환경 변수 섹션 ────────────────────────────────────────────
        if (!def.getEnvVars().isEmpty()) {
            root.getChildren().addAll(new Separator(), sectionLabel("환경 변수"));
            GridPane envGrid = infoGrid();
            int envRow = 0;
            for (Map.Entry<String, String> e : def.getEnvVars().entrySet()) {
                addInfoRow(envGrid, envRow++, e.getKey(), e.getValue());
            }
            root.getChildren().add(envGrid);
        }

        // ── 실행 명령어 미리보기 ─────────────────────────────────────
        root.getChildren().addAll(new Separator(), sectionLabel("실행 명령어 미리보기"));
        TextArea cmdPreview = new TextArea(CommandBuilder.buildStartCommand(def));
        cmdPreview.setEditable(false);
        cmdPreview.setWrapText(true);
        cmdPreview.setPrefHeight(60);
        cmdPreview.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        root.getChildren().add(cmdPreview);

        // ── 하단 버튼 ────────────────────────────────────────────────
        root.getChildren().add(new Separator());

        Button editBtn  = new Button("수정");
        Button closeBtn = new Button("닫기");
        editBtn.setPrefWidth(80);
        closeBtn.setPrefWidth(80);
        closeBtn.setDefaultButton(true);

        editBtn.setOnAction(e -> {
            String originalId = def.getId();
            AddServiceDialog editDialog = new AddServiceDialog(stage, def);
            editDialog.showAndWait().ifPresent(updated -> {
                updated.setId(originalId);            // 기존 ID 유지 (덮어쓰기)
                ctx.getServiceRegistry().update(updated);
                instance.updateDefinition(updated);   // 인스턴스 정의 갱신
                if (onUpdated != null) onUpdated.run();
                stage.close();
            });
        });
        closeBtn.setOnAction(e -> stage.close());

        HBox buttons = new HBox(10, editBtn, closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(buttons);

        // ── Stage 설정 ───────────────────────────────────────────────
        stage.setTitle("서비스 상세 — " + def.getName());
        stage.setScene(SceneFactory.create(root, 600));
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        SceneFactory.autoHeight(stage);
        stage.setResizable(true);
        stage.show();
    }

    // ── 레이아웃 헬퍼 ───────────────────────────────────────────────

    /** 섹션 제목 레이블 */
    private Label sectionLabel(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("section-title");
        return lbl;
    }

    /**
     * 2열 정보 GridPane 생성 (레이블 열 110px 고정, 값 열 가변).
     *
     * @return 열 제약이 설정된 GridPane
     */
    private GridPane infoGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);

        ColumnConstraints lbl = new ColumnConstraints(110);
        lbl.setHalignment(HPos.RIGHT);

        ColumnConstraints val = new ColumnConstraints();
        val.setHgrow(Priority.ALWAYS);
        val.setFillWidth(true);

        grid.getColumnConstraints().addAll(lbl, val);
        return grid;
    }

    /**
     * 정보 행 하나를 GridPane에 추가한다.
     *
     * @param grid  대상 GridPane
     * @param row   행 인덱스
     * @param label 레이블 텍스트
     * @param value 값 텍스트
     */
    private void addInfoRow(GridPane grid, int row, String label, String value) {
        Label lbl = new Label(label + ":");
        lbl.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");

        Label val = new Label(value);
        val.setStyle("-fx-font-size: 12px;");
        val.setWrapText(true);

        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    /** null 또는 빈 문자열을 "-"로 변환 */
    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /** CSS 클래스가 적용된 레이블 */
    private Label styledLabel(String text, String styleClass) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add(styleClass);
        return lbl;
    }
}
