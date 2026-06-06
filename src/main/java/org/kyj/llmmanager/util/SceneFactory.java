/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.util;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * 앱 전체 Scene 생성을 담당하는 팩토리.
 * LlmManagerApp 시작 시 CSS URL을 등록해 두면, 이후 모든 팝업·다이얼로그가
 * create() 메서드 한 곳에서 동일한 스타일시트를 자동으로 적용받는다.
 *
 * <pre>
 * // 앱 초기화 시 1회 등록
 * SceneFactory.init(cssUrl);
 *
 * // 이후 모든 Scene 생성은 SceneFactory를 통해
 * stage.setScene(SceneFactory.create(root, 640, 480));
 * </pre>
 */
public final class SceneFactory {

    /** 모든 Scene에 공통 적용할 CSS 파일 URL (외부 파일 또는 클래스패스) */
    private static String appCssUrl;

    private SceneFactory() {}

    /**
     * 앱 CSS URL을 등록한다. LlmManagerApp.start() 초기화 시점에 1회 호출해야 한다.
     *
     * @param cssUrl app.css의 URL 문자열 (file:// 또는 classpath 형식)
     */
    public static void init(String cssUrl) {
        appCssUrl = cssUrl;
    }

    /**
     * 크기를 지정해 스타일이 적용된 Scene을 생성한다.
     *
     * @param root   Scene의 루트 노드
     * @param width  Scene 너비
     * @param height Scene 높이
     * @return app.css 가 적용된 Scene
     */
    public static Scene create(Parent root, double width, double height) {
        Scene scene = new Scene(root, width, height);
        applyStyle(scene);
        return scene;
    }

    /**
     * 너비만 고정하고 높이는 컨텐츠에 맞게 자동 조정되는 Scene을 생성한다.
     * Stage에 설정한 뒤 {@link #autoHeight(Stage)}를 호출해야 높이가 적용된다.
     *
     * @param root  Scene의 루트 노드
     * @param width 고정 너비
     * @return app.css 가 적용된 Scene
     */
    public static Scene create(Parent root, double width) {
        // 루트에 선호 너비를 설정해 레이아웃 패스가 올바른 높이를 계산하게 한다
        if (root instanceof Region r) r.setPrefWidth(width);
        Scene scene = new Scene(root);
        applyStyle(scene);
        return scene;
    }

    /**
     * 크기를 지정하지 않고 스타일이 적용된 Scene을 생성한다.
     * 컨텐츠 크기에 맞게 자동 조정된다.
     *
     * @param root Scene의 루트 노드
     * @return app.css 가 적용된 Scene
     */
    public static Scene create(Parent root) {
        Scene scene = new Scene(root);
        applyStyle(scene);
        return scene;
    }

    /**
     * Stage가 화면에 표시된 직후 컨텐츠 크기에 맞게 높이를 자동 조정한다.
     * {@code stage.show()} 또는 {@code stage.showAndWait()} 이전에 등록해야 한다.
     *
     * <pre>
     * stage.setScene(SceneFactory.create(root, 560));
     * SceneFactory.autoHeight(stage);
     * stage.showAndWait();
     * </pre>
     *
     * @param stage 높이를 자동 조정할 Stage
     */
    public static void autoHeight(Stage stage) {
        // onShown: 윈도우가 실제로 렌더링된 뒤 레이아웃이 완성되므로 이 시점에 sizeToScene() 호출
        stage.setOnShown(e -> {
            stage.sizeToScene();
            clampToVisualBounds(stage);
        });
    }

    /**
     * 자동 높이 계산 후 화면보다 커진 팝업을 현재 주 모니터 안으로 제한한다.
     * 긴 폼이나 많은 실행 인수를 가진 builtin 서비스 설정창이 화면 밖으로 벗어나는 것을 막는다.
     */
    private static void clampToVisualBounds(Stage stage) {
        var bounds = Screen.getPrimary().getVisualBounds();
        double maxWidth = bounds.getWidth() * 0.92;
        double maxHeight = bounds.getHeight() * 0.90;

        if (stage.getWidth() > maxWidth) {
            stage.setWidth(maxWidth);
        }
        if (stage.getHeight() > maxHeight) {
            stage.setHeight(maxHeight);
        }
        stage.centerOnScreen();
    }

    /**
     * 기존 Scene에 앱 스타일시트를 추가한다.
     * CSS를 교체(리로드)할 때 사용한다.
     *
     * @param scene 스타일을 적용할 Scene
     */
    public static void applyStyle(Scene scene) {
        if (appCssUrl != null && !scene.getStylesheets().contains(appCssUrl)) {
            scene.getStylesheets().add(appCssUrl);
        }
    }

    /** 등록된 CSS URL을 반환한다. 미등록이면 null. */
    public static String getAppCssUrl() { return appCssUrl; }
}
