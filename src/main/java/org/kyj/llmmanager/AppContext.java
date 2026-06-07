/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager;

import org.kyj.llmmanager.service.*;
import org.kyj.llmmanager.service.AppSettingsRepository;
import org.kyj.llmmanager.service.DevHotReloader;

/**
 * 앱 전역 싱글톤 컨텍스트. 모든 서비스 객체를 생성·보관하며 UI 레이어에 제공한다.
 */
public class AppContext {
    /** 싱글톤 인스턴스. 클래스 로딩 시점에 생성. */
    private static final AppContext INSTANCE = new AppContext();

    /** 앱 환경 설정 저장소 (settings.json) */
    private AppSettingsRepository appSettingsRepository;
    /** 사용자 서비스 정의 목록 관리 (services.json) */
    private ServiceRegistry serviceRegistry;
    /** lib/def/ 에서 기본 제공 서비스 정의를 로드하는 로더 */
    private BuiltinServiceLoader builtinServiceLoader;
    /** 개발 모드 FXML/CSS 핫 리로더. 프로덕션에서는 비활성. */
    private DevHotReloader devHotReloader;
    /** 서비스 프로세스 생명주기 관리 */
    private ProcessManager processManager;
    /** 프로세스 stdout/stderr 스트리밍 서비스 */
    private LogService logService;
    /** git clone + 의존성 설치 서비스 */
    private InstallationService installationService;
    /** HTTP 헬스체크 주기 실행 */
    private HealthMonitor healthMonitor;
    /** 시스템 트레이 아이콘 및 메뉴 관리 */
    private SystemTrayManager trayManager;
    /** LLM 스킬 파일 설치 서비스 */
    private LlmSkillInstaller llmSkillInstaller;
    /** LLM 스킬 설치 프로젝트 목록 관리 (projects.json) */
    private ProjectRegistry projectRegistry;
    /** 내장 REST API 서버 (Javalin). 설정에서 활성화 시 자동 시작. */
    private EmbeddedApiServer apiServer;
    /** 시스템 CPU·메모리 수집 서비스 (OSHI 기반). */
    private SystemMonitorService systemMonitor;

    private AppContext() {}

    public static AppContext getInstance() {
        return INSTANCE;
    }

    /**
     * 모든 서비스 객체를 초기화하고 헬스모니터를 시작한다. JavaFX start() 진입 직후 호출.
     */
    public void init() {
        appSettingsRepository = new AppSettingsRepository();
        appSettingsRepository.load();
        devHotReloader    = new DevHotReloader();
        builtinServiceLoader = new BuiltinServiceLoader();
        serviceRegistry = new ServiceRegistry();
        serviceRegistry.load();
        logService = new LogService();
        processManager = new ProcessManager(logService);
        installationService = new InstallationService();
        healthMonitor = new HealthMonitor(processManager,
                appSettingsRepository.get().getHealthCheckInterval());
        healthMonitor.start();

        llmSkillInstaller = new LlmSkillInstaller();
        projectRegistry = new ProjectRegistry();
        projectRegistry.load();

        trayManager = new SystemTrayManager();
        // 서비스 상태 변경 시 트레이 메뉴 갱신
        processManager.setOnStatusChange(() -> {
            if (trayManager.isSupported()) trayManager.refreshServicesMenu();
        });

        // 시스템 리소스 모니터 시작
        systemMonitor = new SystemMonitorService();
        systemMonitor.start();

        // 내장 API 서버 — 설정에서 활성화되어 있으면 시작
        apiServer = new EmbeddedApiServer(this);
        var settings = appSettingsRepository.get();
        if (settings.isApiServerEnabled()) {
            apiServer.start(settings.getApiServerHost(), settings.getApiServerPort());
        }

        // JVM 강제 종료(Ctrl+C, SIGTERM) 시 실행 중인 서비스 프로세스를 정리한다.
        // 작업 관리자에서 kill -9 수준의 강제 종료는 훅이 실행되지 않는다.
        Runtime.getRuntime().addShutdownHook(new Thread(
                processManager::stopAllSync, "shutdown-hook"));
    }

    /**
     * JavaFX 창이 준비된 후 시스템 트레이를 설치한다.
     *
     * @param stage 트레이와 연동할 JavaFX 기본 창
     */
    public void installTray(javafx.stage.Stage stage) {
        trayManager.install(stage, this);
    }

    /**
     * 헬스모니터 중지 → 모든 서비스 프로세스 종료 → 트레이 제거.
     */
    public void shutdown() {
        if (healthMonitor != null) healthMonitor.stop();
        if (apiServer != null)     apiServer.stop();
        if (processManager != null) processManager.stopAllSync();
        if (logService != null)    logService.shutdown();
        if (systemMonitor != null) systemMonitor.stop();
        if (trayManager != null)   trayManager.remove();
    }

    public AppSettingsRepository getAppSettingsRepository() { return appSettingsRepository; }
    public EmbeddedApiServer getApiServer() { return apiServer; }
    public SystemMonitorService getSystemMonitor() { return systemMonitor; }
    public ServiceRegistry getServiceRegistry() { return serviceRegistry; }
    public BuiltinServiceLoader getBuiltinServiceLoader() { return builtinServiceLoader; }
    public DevHotReloader getDevHotReloader() { return devHotReloader; }
    public ProcessManager getProcessManager() { return processManager; }
    public LogService getLogService() { return logService; }
    public InstallationService getInstallationService() { return installationService; }
    public HealthMonitor getHealthMonitor() { return healthMonitor; }
    public SystemTrayManager getTrayManager() { return trayManager; }
    public LlmSkillInstaller getLlmSkillInstaller() { return llmSkillInstaller; }
    public ProjectRegistry getProjectRegistry() { return projectRegistry; }
}
