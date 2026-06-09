/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager;

import atlantafx.base.theme.NordDark;
import org.kyj.llmmanager.service.AppConfigLoader;
import org.kyj.llmmanager.service.DevHotReloader;
import org.kyj.llmmanager.setup.SetupCheckDialog;
import org.kyj.llmmanager.ui.controller.MainController;
import org.kyj.llmmanager.util.AppIconFactory;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JavaFX 애플리케이션 진입점. Stage 초기화, Scene 로드, 개발 모드 핫 리로드 설정을 담당.
 */
public class LlmManagerApp extends Application {
    private static final Logger log = LoggerFactory.getLogger(LlmManagerApp.class);

    public static void main(String[] args) {
        // CLI 인수 파싱 — AppContext.init() 보다 먼저 실행해야 한다
        AppConfigLoader.parseArgs(args);
        // AWT Toolkit을 JavaFX보다 먼저 초기화 (SystemTray 안정성)
        java.awt.Toolkit.getDefaultToolkit();
        launch(args);
    }

    /**
     * 앱 메인 진입. 환경 체크 → AppContext 초기화 → 메인 창 표시 순으로 실행한다.
     * 개발 모드이면 DevHotReloader를 시작한다.
     *
     * @param primaryStage JavaFX가 제공하는 기본 창
     * @throws Exception Scene 로드 또는 초기화 실패 시
     */
    @Override
    public void start(Stage primaryStage) throws Exception {
        Platform.setImplicitExit(false);
        Application.setUserAgentStylesheet(new NordDark().getUserAgentStylesheet());

        // CSS를 SetupCheckDialog 보다 먼저 등록해야 다이얼로그에도 동일 스타일이 적용된다
        SceneFactory.init(cssUrl("app.css"));

        // 환경 체크 다이얼로그 — '계속' 또는 '건너뜀' 클릭 시 반환
        new SetupCheckDialog().showAndWait();

        AppContext ctx = AppContext.getInstance();
        ctx.init();

        // Stage 기본 속성 (한 번만 설정)
        primaryStage.setTitle("LLM Manager");
        // 다중 해상도 아이콘 등록 — 타이틀바·태스크바·HiDPI 자동 선택
        primaryStage.getIcons().addAll(AppIconFactory.createFxIcons());
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(500);
        primaryStage.setOnCloseRequest(e -> {
            e.consume();
            SystemTrayManager tray = ctx.getTrayManager();
            if (tray.isSupported()) {
                tray.hideWindow();
                tray.notify("LLM Manager", "트레이에서 계속 실행 중입니다.\n우클릭 → 종료 로 앱을 닫을 수 있습니다.");
            } else {
                ctx.shutdown();
                Platform.exit();
            }
        });

        applyScene(primaryStage, ctx, 1100, 700);

        // 개발 모드: FXML/CSS/DEF 파일 감시 + 핫 리로드
        if (DevHotReloader.isActive()) {
            log.info("[DEV] Development mode — hot reload enabled");
            ctx.getDevHotReloader().start(
                () -> applyScene(primaryStage, ctx,
                        primaryStage.getScene().getWidth(),
                        primaryStage.getScene().getHeight()),
                () -> reloadCss(primaryStage)
            );
        }

        primaryStage.show();
        ctx.installTray(primaryStage);
    }

    // =========================================================
    // Scene / CSS 로드 헬퍼
    // =========================================================

    /**
     * main.fxml을 로드해 새 Scene을 생성하고 Stage에 적용한다.
     * 개발 모드에서는 파일시스템 URL, 프로덕션에서는 클래스패스 URL을 사용해 핫 리로드를 지원한다.
     *
     * @param stage  Scene을 적용할 JavaFX 창
     * @param ctx    MainController에 주입할 앱 컨텍스트
     * @param width  Scene 너비
     * @param height Scene 높이
     */
    private void applyScene(Stage stage, AppContext ctx, double width, double height) {
        try {
            FXMLLoader loader = new FXMLLoader(fxmlUrl("main.fxml"));
            Scene scene = SceneFactory.create(loader.load(), width, height);

            MainController controller = loader.getController();
            controller.initializeContext(ctx);

            stage.setScene(scene);

            if (DevHotReloader.isActive()) {
                log.info("[DEV] Scene reloaded: main.fxml");
            }
        } catch (Exception e) {
            log.error("Failed to load scene", e);
            // 개발 모드에서는 오류를 화면에 표시해 원인을 즉시 파악할 수 있도록 한다.
            // 컨트롤러 필드(@FXML)가 바뀐 경우 클래스 재컴파일 후 앱을 재시작해야 한다.
            if (DevHotReloader.isActive()) {
                Platform.runLater(() -> {
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.WARNING);
                    alert.setTitle("[DEV] Scene 리로드 실패");
                    alert.setHeaderText("FXML 리로드 중 오류가 발생했습니다.");
                    alert.setContentText(
                            e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "\n\n@FXML 필드가 변경됐다면 앱을 재시작하세요.\n(./gradlew runDev)");
                    alert.show();
                });
            }
        }
    }

    /**
     * 스타일시트만 교체해 Scene 전체를 재생성하지 않고 CSS를 즉시 반영한다.
     *
     * @param stage CSS를 갱신할 JavaFX 창
     */
    private void reloadCss(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) return;
        try {
            scene.getStylesheets().clear();
            SceneFactory.applyStyle(scene);
            log.info("[DEV] CSS reloaded");
        } catch (Exception e) {
            log.error("[DEV] CSS reload failed", e);
        }
    }

    // =========================================================
    // URL 해석 — 개발 모드: 소스 파일 직접, 프로덕션: 클래스패스
    // =========================================================

    /**
     * 개발 모드이면 src/main/resources 의 파일 URL, 아니면 클래스패스 URL을 반환한다.
     *
     * @param name FXML 파일명 (예: "main.fxml")
     * @return FXML 리소스 URL
     * @throws Exception URL 생성 실패 시
     */
    private URL fxmlUrl(String name) throws Exception {
        if (DevHotReloader.isActive()) {
            return Path.of("src/main/resources/org/kyj/llmmanager")
                    .resolve(name).toAbsolutePath().toUri().toURL();
        }
        return Objects.requireNonNull(
                getClass().getResource("/org/kyj/llmmanager/" + name));
    }

    /**
     * 개발 모드이면 src/main/resources 의 파일 URL 문자열, 아니면 클래스패스 URL 문자열을 반환한다.
     *
     * @param name CSS 파일명 (예: "app.css")
     * @return CSS 리소스 URL 문자열
     * @throws Exception URL 생성 실패 시
     */
    private String cssUrl(String name) throws Exception {
        if (DevHotReloader.isActive()) {
            return Path.of("src/main/resources/org/kyj/llmmanager")
                    .resolve(name).toAbsolutePath().toUri().toURL().toExternalForm();
        }
        return Objects.requireNonNull(
                getClass().getResource("/org/kyj/llmmanager/" + name)).toExternalForm();
    }
}
