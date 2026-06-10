/*
 * 작성자 : kyj
 * 작성일 : 2026-06-09
 *
 * 개발용 단독 실행 런처 — 앱 전체를 띄우지 않고 SetupCheckDialog 만 테스트한다.
 * dev 소스셋에 있어 배포 JAR에는 포함되지 않는다.
 *
 * 실행:
 *   ./gradlew runSetupTest                        (모든 항목 실제 검사)
 *   ./gradlew runSetupTest --args="fail-all"      (모든 항목 강제 실패)
 *   ./gradlew runSetupTest --args="fail-python"
 *   ./gradlew runSetupTest --args="fail-nvidia"
 *   ./gradlew runSetupTest --args="fail-cuda"
 */
package org.kyj.llmmanager.setup;

import atlantafx.base.theme.NordDark;
import org.kyj.llmmanager.util.SceneFactory;
import javafx.application.Application;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;

/**
 * SetupCheckDialog 단독 테스트 런처.
 * 앱 전체 초기화 없이 다이얼로그만 띄워 UI·검사·설치 흐름을 검증한다.
 */
public class SetupCheckDialogTest extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        Application.setUserAgentStylesheet(new NordDark().getUserAgentStylesheet());

        // CSS 경로 — src/main/resources 기준 (개발 모드)
        String cssUrl = Path.of("src/main/resources/org/kyj/llmmanager/app.css")
                .toAbsolutePath().toUri().toURL().toExternalForm();
        SceneFactory.init(cssUrl);

        // 실행 인수로 강제 실패 시나리오 선택
        List<String> params = getParameters().getRaw();
        String mode = params.stream()
                .filter(p -> p.startsWith("--setup-test"))
                .findFirst().orElse("--setup-test");

        SetupChecker checker = buildChecker(mode);
        new SetupCheckDialog(checker).showAndWait();

        // 다이얼로그가 닫히면 앱 종료
        javafx.application.Platform.exit();
    }

    /**
     * 실행 모드에 따라 결과를 오버라이드한 SetupChecker를 반환한다.
     *
     * @param mode 실행 인수 문자열
     * @return 설정된 SetupChecker
     */
    private SetupChecker buildChecker(String mode) {
        SetupChecker checker = new SetupChecker();
        switch (mode) {
            case "--setup-test=fail-all" -> {
                for (SetupItem item : SetupItem.values()) checker.forceResult(item, false);
                System.out.println("[TEST] 모든 항목 강제 실패");
            }
            case "--setup-test=fail-python" -> {
                checker.forceResult(SetupItem.PYTHON, false);
                System.out.println("[TEST] Python 강제 실패");
            }
            case "--setup-test=fail-nvidia" -> {
                checker.forceResult(SetupItem.NVIDIA_DRIVER, false);
                System.out.println("[TEST] NVIDIA 드라이버 강제 실패");
            }
            case "--setup-test=fail-cuda" -> {
                checker.forceResult(SetupItem.CUDA, false);
                System.out.println("[TEST] CUDA 강제 실패");
            }
            default -> System.out.println("[TEST] 실제 환경 검사");
        }
        return checker;
    }
}
