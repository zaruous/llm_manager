/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager;

import org.kyj.llmmanager.model.ServiceInstance;
import org.kyj.llmmanager.model.ServiceStatus;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.*;
import java.util.List;

/**
 * 운영체제 시스템 트레이에 LLM Manager 아이콘을 표시하고, 서비스 시작/중지 메뉴를 제공한다.
 * AWT EventQueue에서 동작하며 JavaFX Stage와 연동된다.
 */
public class SystemTrayManager {

    /** 트레이에 표시되는 아이콘 객체 */
    private TrayIcon trayIcon;
    /** 트레이에서 보이기/숨기기를 제어할 JavaFX 창 */
    private Stage primaryStage;
    /** 서비스 목록과 프로세스 제어에 접근하기 위한 앱 컨텍스트 */
    private AppContext ctx;
    /** 트레이 팝업 메뉴의 '서비스' 서브메뉴 */
    private Menu servicesMenu;

    public boolean isSupported() {
        return SystemTray.isSupported();
    }

    /**
     * 트레이 아이콘을 생성하고 시스템 트레이에 등록한다. AWT EventQueue에서 실행.
     *
     * @param stage  트레이와 연동할 JavaFX 기본 창
     * @param appCtx 서비스 목록과 프로세스 제어를 제공하는 앱 컨텍스트
     */
    public void install(Stage stage, AppContext appCtx) {
        if (!isSupported()) return;
        this.primaryStage = stage;
        this.ctx = appCtx;

        EventQueue.invokeLater(() -> {
            try {
                SystemTray tray = SystemTray.getSystemTray();
                servicesMenu = new Menu("서비스");
                PopupMenu popup = buildPopupMenu();

                trayIcon = new TrayIcon(createIcon(), "LLM Manager", popup);
                trayIcon.setImageAutoSize(true);
                trayIcon.addActionListener(e -> showWindow()); // 더블클릭
                tray.add(trayIcon);

                refreshServicesMenu();
            } catch (AWTException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 트레이 우클릭 팝업 메뉴(열기, 서비스 서브메뉴, 종료)를 생성한다.
     *
     * @return 구성된 AWT PopupMenu
     */
    private PopupMenu buildPopupMenu() {
        PopupMenu menu = new PopupMenu();

        MenuItem showItem = new MenuItem("열기");
        showItem.addActionListener(e -> showWindow());

        MenuItem exitItem = new MenuItem("종료");
        exitItem.addActionListener(e -> {
            ctx.shutdown();
            EventQueue.invokeLater(() -> {
                if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
            });
            Platform.exit();
        });

        menu.add(showItem);
        menu.add(servicesMenu);
        menu.addSeparator();
        menu.add(exitItem);
        return menu;
    }

    /**
     * 현재 서비스 목록을 기반으로 서비스 서브메뉴를 다시 구성한다. 클릭 시 실행/중지 토글.
     * 서비스 추가/제거/상태 변경 시 호출한다.
     */
    public void refreshServicesMenu() {
        if (servicesMenu == null) return;
        List<ServiceInstance> instances = ctx.getProcessManager().getAllInstances();

        EventQueue.invokeLater(() -> {
            servicesMenu.removeAll();
            if (instances.isEmpty()) {
                MenuItem empty = new MenuItem("(서비스 없음)");
                empty.setEnabled(false);
                servicesMenu.add(empty);
                return;
            }
            for (ServiceInstance inst : instances) {
                boolean running = inst.getStatus() == ServiceStatus.RUNNING;
                String label = (running ? "■ " : "▶ ") + inst.getDefinition().getName()
                        + "  [" + inst.getStatus().getLabel() + "]";
                MenuItem item = new MenuItem(label);
                item.addActionListener(e -> {
                    if (inst.getStatus() == ServiceStatus.RUNNING) {
                        ctx.getProcessManager().stop(inst);
                    } else if (inst.getStatus() == ServiceStatus.INSTALLED
                            || inst.getStatus() == ServiceStatus.STOPPED
                            || inst.getStatus() == ServiceStatus.ERROR) {
                        ctx.getProcessManager().start(inst);
                    }
                });
                servicesMenu.add(item);
            }
        });
        updateTooltip();
    }

    /**
     * JavaFX 창을 보이거나 숨긴다. (showWindow)
     * JavaFX Application Thread에서 실행된다.
     */
    public void showWindow() {
        Platform.runLater(() -> {
            primaryStage.show();
            primaryStage.setIconified(false);
            primaryStage.toFront();
            primaryStage.requestFocus();
        });
    }

    /**
     * JavaFX 창을 숨긴다. (hideWindow)
     * JavaFX Application Thread에서 실행된다.
     */
    public void hideWindow() {
        Platform.runLater(() -> primaryStage.hide());
    }

    /**
     * 트레이 풍선 알림을 표시한다.
     *
     * @param title   알림 제목
     * @param message 알림 내용
     */
    public void notify(String title, String message) {
        if (trayIcon != null) {
            EventQueue.invokeLater(() ->
                    trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO));
        }
    }

    /**
     * 트레이 아이콘 툴팁을 '실행중: N/T' 형식으로 업데이트한다.
     */
    private void updateTooltip() {
        if (trayIcon == null) return;
        long running = ctx.getProcessManager().getAllInstances().stream()
                .filter(i -> i.getStatus() == ServiceStatus.RUNNING).count();
        long total = ctx.getProcessManager().getAllInstances().size();
        EventQueue.invokeLater(() ->
                trayIcon.setToolTip("LLM Manager  |  실행중: " + running + "/" + total));
    }

    public void remove() {
        if (trayIcon != null && SystemTray.isSupported()) {
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(trayIcon));
        }
    }

    /**
     * AppIconFactory를 통해 트레이 아이콘을 생성한다.
     * 앱 아이콘과 디자인을 통일한다.
     *
     * @return AWT Image 트레이 아이콘 (32×32)
     */
    private Image createIcon() {
        return org.kyj.llmmanager.util.AppIconFactory.createAwtIcon(32);
    }
}
