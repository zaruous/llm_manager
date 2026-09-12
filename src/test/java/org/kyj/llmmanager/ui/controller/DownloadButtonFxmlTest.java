/*
 * 작성자 : kyj
 * 작성일 : 2026-09-12
 */
package org.kyj.llmmanager.ui.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.RuntimeType;
import org.kyj.llmmanager.model.ServiceDefinition;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서비스 설정 다이얼로그의 다운로드 · 최신 확인 버튼이 FXML과 제대로 엮였는지 검증한다.
 *
 * <p>FXML은 컴파일 시점에 검사되지 않는다. {@code onAction}이 가리키는 메서드가 없거나
 * {@code @FXML} 필드에 대응하는 {@code fx:id}가 없으면 다이얼로그를 여는 순간에야
 * 터지므로, 로드와 폼 구성까지 실제로 수행해 확인한다.
 */
class DownloadButtonFxmlTest {

    /** JavaFX 툴킷 기동 대기 시간 */
    private static final int TOOLKIT_TIMEOUT_SEC = 20;

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // 같은 JVM에서 다른 테스트가 이미 툴킷을 띄운 경우
            ready.countDown();
        }
        assertTrue(ready.await(TOOLKIT_TIMEOUT_SEC, TimeUnit.SECONDS),
                "JavaFX 툴킷이 기동하지 않았습니다");
    }

    /**
     * FX 스레드에서 작업을 실행하고 끝날 때까지 기다린다.
     *
     * @param task 실행할 작업
     * @throws Exception 작업이 던진 예외를 그대로 전달
     */
    private static void onFxThread(FxTask task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(TOOLKIT_TIMEOUT_SEC, TimeUnit.SECONDS), "FX 작업이 끝나지 않았습니다");
        if (failure.get() != null) {
            throw new AssertionError("FX 스레드에서 실패", failure.get());
        }
    }

    private interface FxTask {
        void run() throws Exception;
    }

    private static ServiceDefinition sqlGenDef() {
        ServiceDefinition def = new ServiceDefinition();
        def.setId("sql-gen-mcp-test");
        def.setName("SQL Gen MCP Server");
        def.setDescription("테스트용 정의");
        def.setRuntimeType(RuntimeType.JAVA);
        def.setStartCommand("java -jar sql-gen-mcp-1.0.0.jar");
        def.setInstallDir("D:/llm-services/sql-gen-mcp");
        def.setDownloadUrl(
                "https://github.com/zaruous/sql-mcp-release/releases/download/v1.0.0/sql-gen-mcp-1.0.0.jar");
        return def;
    }

    private static URL fxml(String name) {
        URL url = DownloadButtonFxmlTest.class.getResource("/org/kyj/llmmanager/" + name);
        assertNotNull(url, name + "을 찾을 수 없습니다");
        return url;
    }

    /**
     * 컨트롤러의 private @FXML 필드를 읽는다. 테스트에서만 쓰는 접근이다.
     */
    @SuppressWarnings("unchecked")
    private static <T> T field(Object controller, String name) throws Exception {
        Field f = controller.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(controller);
    }

    private static void assertDownloadControlsWired(Object controller) throws Exception {
        Button download = field(controller, "downloadBtn");
        Button checkLatest = field(controller, "checkLatestBtn");
        Button cancel = field(controller, "cancelDownloadBtn");
        HBox statusRow = field(controller, "downloadStatusRow");
        ProgressBar progress = field(controller, "downloadProgress");
        Label status = field(controller, "downloadStatusLabel");

        assertNotNull(download, "downloadBtn이 주입되지 않았습니다");
        assertNotNull(checkLatest, "checkLatestBtn이 주입되지 않았습니다");
        assertNotNull(cancel, "cancelDownloadBtn이 주입되지 않았습니다");
        assertNotNull(statusRow, "downloadStatusRow가 주입되지 않았습니다");
        assertNotNull(progress, "downloadProgress가 주입되지 않았습니다");
        assertNotNull(status, "downloadStatusLabel이 주입되지 않았습니다");

        // downloadUrl이 GitHub 릴리즈 URL이므로 두 버튼 모두 노출돼야 한다
        assertTrue(download.isVisible(), "다운로드 버튼이 보이지 않습니다");
        assertTrue(download.isManaged(), "다운로드 버튼이 레이아웃에서 빠져 있습니다");
        assertTrue(checkLatest.isVisible(), "최신 확인 버튼이 보이지 않습니다");

        // 진행 표시 행은 다운로드를 시작하기 전까지 숨겨져 있어야 한다
        assertFalse(statusRow.isVisible(), "진행 표시 행이 처음부터 보입니다");
        assertFalse(statusRow.isManaged(), "진행 표시 행이 처음부터 자리를 차지합니다");
    }

    @Test
    @DisplayName("서비스 상세 > 수정 다이얼로그에 다운로드·최신 확인 버튼이 붙는다")
    void addServiceDialogWiresDownloadControls() throws Exception {
        onFxThread(() -> {
            FXMLLoader loader = new FXMLLoader(fxml("add-service.fxml"));
            loader.load();
            AddServiceController controller = loader.getController();
            controller.prefill(sqlGenDef());
            assertDownloadControlsWired(controller);
        });
    }

    @Test
    @DisplayName("builtin 서비스 설정 다이얼로그에도 같은 버튼이 붙는다")
    void builtinSetupDialogWiresDownloadControls() throws Exception {
        onFxThread(() -> {
            FXMLLoader loader = new FXMLLoader(fxml("builtin-service-setup.fxml"));
            loader.load();
            BuiltinServiceSetupController controller = loader.getController();
            controller.setup(sqlGenDef());
            assertDownloadControlsWired(controller);
        });
    }

    @Test
    @DisplayName("downloadUrl이 없고 팩에도 없는 서비스는 버튼이 숨겨진다")
    void hidesButtonsWithoutDownloadUrl() throws Exception {
        onFxThread(() -> {
            ServiceDefinition plain = new ServiceDefinition();
            plain.setName("이름 없는 서비스 " + System.nanoTime());
            plain.setRuntimeType(RuntimeType.PYTHON);
            plain.setStartCommand("python server.py");

            FXMLLoader loader = new FXMLLoader(fxml("add-service.fxml"));
            loader.load();
            AddServiceController controller = loader.getController();
            controller.prefill(plain);

            Button download = field(controller, "downloadBtn");
            Button checkLatest = field(controller, "checkLatestBtn");
            assertFalse(download.isVisible(), "다운로드 대상이 없는데 버튼이 보입니다");
            assertFalse(checkLatest.isVisible(), "다운로드 대상이 없는데 최신 확인 버튼이 보입니다");
        });
    }
}
