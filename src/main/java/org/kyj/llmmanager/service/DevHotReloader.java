/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.concurrent.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 개발 모드(-Dllm.dev=true)에서 FXML·CSS·DEF JSON 파일 변경을 감시하고
 * 300ms 디바운스 후 UI를 자동 리로드한다.
 * 프로덕션 빌드에서는 완전히 비활성화된다.
 */
public class DevHotReloader {
    private static final Logger log = LoggerFactory.getLogger(DevHotReloader.class);

    /** 개발 모드 활성화 여부. -Dllm.dev=true JVM 옵션으로 설정. */
    public static final boolean ACTIVE = "true".equals(System.getProperty("llm.dev"));

    /** 감시할 FXML/CSS 소스 디렉토리 */
    private static final Path FXML_DIR = Path.of("src/main/resources/org/kyj/llmmanager");

    /** 감시할 기본 제공 서비스 JSON 디렉토리 */
    private static final Path DEF_DIR  = Path.of("lib/def");

    /** FXML 또는 DEF JSON 변경 시 호출할 콜백 (메인 씬 재로드) */
    private Runnable onFxmlChange;

    /** CSS 변경 시 호출할 콜백 (스타일시트만 교체) */
    private Runnable onCssChange;

    /**
     * 파일 저장 이벤트가 연속으로 발생할 때 300ms 기다렸다가
     * 한 번만 실행하도록 하는 스케줄러.
     */
    private final ScheduledExecutorService debouncer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dev-debouncer");
                t.setDaemon(true);
                return t;
            });

    /** 대기 중인 FXML 디바운스 태스크. 새 이벤트가 오면 이전 것을 취소. */
    private ScheduledFuture<?> fxmlPending;

    /** 대기 중인 CSS 디바운스 태스크. 새 이벤트가 오면 이전 것을 취소. */
    private ScheduledFuture<?> cssPending;

    public static boolean isActive() { return ACTIVE; }

    /**
     * 파일 감시 스레드를 시작한다. ACTIVE가 false이면 아무것도 하지 않는다.
     *
     * @param onFxmlChange FXML 또는 DEF JSON 변경 시 호출할 콜백
     * @param onCssChange  CSS 변경 시 호출할 콜백
     */
    public void start(Runnable onFxmlChange, Runnable onCssChange) {
        if (!ACTIVE) return;
        this.onFxmlChange = onFxmlChange;
        this.onCssChange  = onCssChange;

        Thread t = new Thread(this::watchLoop, "dev-hot-reloader");
        t.setDaemon(true);
        t.start();
        log.info("[DEV] Hot reload active — watching FXML/CSS in {}", FXML_DIR.toAbsolutePath());
    }

    /**
     * WatchService로 파일 변경을 감지한다.
     * 확장자별로 FXML/CSS/JSON을 구분해 적절한 디바운스를 실행.
     */
    private void watchLoop() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            if (Files.isDirectory(FXML_DIR)) FXML_DIR.register(ws, ENTRY_MODIFY, ENTRY_CREATE);
            if (Files.isDirectory(DEF_DIR))  DEF_DIR .register(ws, ENTRY_MODIFY, ENTRY_CREATE);

            while (true) {
                WatchKey key = ws.take();
                for (WatchEvent<?> ev : key.pollEvents()) {
                    String name = ev.context().toString();
                    if      (name.endsWith(".fxml")) { log.info("[DEV] FXML changed: {}", name); scheduleFxml(); }
                    else if (name.endsWith(".css"))  { log.info("[DEV] CSS  changed: {}", name); scheduleCss();  }
                    else if (name.endsWith(".json")) { log.info("[DEV] DEF  changed: {}", name); scheduleFxml(); }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[DEV] Watcher error", e);
        }
    }

    /**
     * 이전 대기 태스크를 취소하고 300ms 후 FXML 리로드 콜백을 예약한다.
     */
    private void scheduleFxml() {
        if (fxmlPending != null) fxmlPending.cancel(false);
        fxmlPending = debouncer.schedule(
                () -> Platform.runLater(onFxmlChange), 300, TimeUnit.MILLISECONDS);
    }

    /**
     * 이전 대기 태스크를 취소하고 300ms 후 CSS 리로드 콜백을 예약한다.
     */
    private void scheduleCss() {
        if (cssPending != null) cssPending.cancel(false);
        cssPending = debouncer.schedule(
                () -> Platform.runLater(onCssChange), 300, TimeUnit.MILLISECONDS);
    }
}
