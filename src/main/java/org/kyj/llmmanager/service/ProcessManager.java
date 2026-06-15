/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.*;
import org.kyj.llmmanager.util.CommandBuilder;
import org.kyj.llmmanager.util.PlatformUtil;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 서비스 프로세스의 생명주기(시작·중지·재시작)를 관리한다.
 * 각 서비스는 별도 스레드에서 실행되며, 상태 변경은 JavaFX 스레드에서 처리된다.
 */
public class ProcessManager {
    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    /**
     * 서비스 ID → ServiceInstance 맵.
     * 같은 서비스가 중복 생성되지 않도록 ConcurrentHashMap으로 관리.
     */
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    /** 프로세스 stdout/stderr를 ServiceInstance 로그로 연결하는 서비스 */
    private final LogService logService;

    /** 런타임 설정 저장소. Java 홈 설정을 서비스 실행에 반영한다. */
    private final AppSettingsRepository settingsRepository;

    /** 서비스 상태가 바뀔 때 호출할 콜백. 트레이 메뉴 갱신 등에 사용. */
    private Runnable onStatusChange;

    public ProcessManager(LogService logService) {
        this(logService, null);
    }

    public ProcessManager(LogService logService, AppSettingsRepository settingsRepository) {
        this.logService = logService;
        this.settingsRepository = settingsRepository;
    }

    public void setOnStatusChange(Runnable callback) {
        this.onStatusChange = callback;
    }

    /**
     * 서비스 ID로 기존 인스턴스를 반환하거나 없으면 새로 생성한다.
     *
     * @param def 서비스 정의
     * @return 기존 또는 새로 생성된 ServiceInstance
     */
    public ServiceInstance getOrCreate(ServiceDefinition def) {
        return instances.computeIfAbsent(def.getId(), id -> new ServiceInstance(def));
    }

    public List<ServiceInstance> getAllInstances() {
        return new ArrayList<>(instances.values());
    }

    /**
     * 서비스 ID에 해당하는 인스턴스를 맵에서 제거한다.
     * 서비스 삭제 시 호출해 대시보드·상태 조회에서 제외되도록 한다.
     *
     * @param id 제거할 서비스 ID
     */
    public void remove(String id) {
        instances.remove(id);
    }

    /**
     * PID 파일에 남은 프로세스가 살아 있으면 현재 인스턴스에 연결한다.
     * 비정상 종료 후 앱이 재시작될 때 같은 서비스를 중복 기동하지 않기 위한 처리다.
     *
     * @param instance 복원할 서비스 인스턴스
     * @return 살아 있는 프로세스를 감지해 연결했으면 true
     */
    public boolean restoreFromPidFile(ServiceInstance instance) {
        OptionalLong savedPid = PidFileManager.read(instance.getDefinition());
        if (savedPid.isEmpty()) return false;

        long pid = savedPid.getAsLong();
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        if (!alive) {
            PidFileManager.delete(instance.getDefinition());
            return false;
        }

        instance.attachExternalPid(pid);
        setStatus(instance, ServiceStatus.RUNNING);
        logService.addSystemLog(instance, "기존 실행 프로세스를 감지했습니다 (PID=" + pid + ")");
        return true;
    }

    /**
     * 서비스를 별도 스레드에서 시작한다. 이미 RUNNING/STARTING 이면 무시.
     *
     * @param instance 시작할 서비스 인스턴스
     */
    public void start(ServiceInstance instance) {
        if (instance.getStatus() == ServiceStatus.RUNNING
                || instance.getStatus() == ServiceStatus.STARTING) return;
        if (restoreFromPidFile(instance)) return;
        new Thread(() -> doStart(instance), "start-" + instance.getDefinition().getName()).start();
    }

    /**
     * 실제 프로세스를 생성하고 로그 스트림을 연결한다.
     * 프로세스 종료 코드에 따라 STOPPED/ERROR로 전환.
     *
     * @param instance 시작할 서비스 인스턴스
     */
    private void doStart(ServiceInstance instance) {
        ServiceDefinition def = instance.getDefinition();
        setStatus(instance, ServiceStatus.STARTING);
        logService.addSystemLog(instance, "Starting " + def.getName() + "...");

        try {
            String command = CommandBuilder.buildStartCommand(def);
            logService.addSystemLog(instance, "Command: " + command);

            String workDir = def.getWorkingDir() != null ? def.getWorkingDir()
                    : def.getInstallDir();

            List<String> tokens = CommandBuilder.splitCommand(command);
            // bare "java" / "java.exe"를 이 프로세스를 실행 중인 JVM 절대 경로로 치환한다.
            // jpackage 번들 JRE와 시스템 JAVA_HOME이 다를 때 버전 불일치를 방지한다.
            if (!tokens.isEmpty()) {
                String exe = tokens.get(0);
                if (exe.equalsIgnoreCase("java") || exe.equalsIgnoreCase("java.exe")) {
                    String javaExecutable = resolveJavaExecutable(tokens, workDir);
                    tokens.set(0, javaExecutable);
                    logService.addSystemLog(instance,
                            "Resolved Java executable: " + javaExecutable);
                    logService.addSystemLog(instance,
                            "Resolved command: " + String.join(" ", tokens));
                }
            }

            ProcessBuilder pb = new ProcessBuilder(tokens);
            if (workDir != null && !workDir.isBlank()) {
                File dir = new File(workDir);
                if (!dir.exists() || !dir.isDirectory()) {
                    logService.addSystemLog(instance,
                            "오류: 설치 경로가 존재하지 않습니다 → " + workDir);
                    logService.addSystemLog(instance,
                            "서비스 수정(더블클릭 → 수정)에서 설치 경로를 JAR/실행파일이 있는 폴더로 변경해 주세요.");
                    setStatus(instance, ServiceStatus.ERROR);
                    return;
                }
                pb.directory(dir);
            }
            pb.environment().putAll(def.getEnvVars());
            pb.redirectErrorStream(false);

            Process process = pb.start();
            instance.setProcess(process);
            instance.setStartTime(Instant.now());

            // 이 앱이 실행시킨 프로세스임을 PID 파일로 마킹
            try {
                PidFileManager.write(def, process.pid());
            } catch (Exception e) {
                log.warn("Failed to write PID file for {}", def.getName(), e);
            }
            setStatus(instance, ServiceStatus.RUNNING);

            logService.attach(instance);

            int exit = process.waitFor();
            if (instance.getStatus() == ServiceStatus.RUNNING) {
                logService.addSystemLog(instance, "Process exited with code " + exit);
                setStatus(instance, exit == 0 ? ServiceStatus.STOPPED : ServiceStatus.ERROR);
            }
        } catch (Exception e) {
            log.error("Failed to start service: {}", def.getName(), e);
            logService.addSystemLog(instance, "Error: " + e.getMessage());
            setStatus(instance, ServiceStatus.ERROR);
        }
    }

    /**
     * Java 서비스 실행에 사용할 java 실행 파일을 결정한다.
     * <p>우선순위:
     * <ol>
     *   <li>설정의 Java 홈/JAVA_HOME</li>
     *   <li>JAR Main-Class의 class version이 현재 런타임보다 높으면 설치된 호환 JDK 자동 탐색</li>
     *   <li>현재 앱 JVM의 java 실행 파일</li>
     * </ol>
     *
     * @param tokens 실행 토큰
     * @param workDir 서비스 작업 디렉토리
     * @return 사용할 java 실행 파일 경로
     */
    private String resolveJavaExecutable(List<String> tokens, String workDir) {
        OptionalInt requiredMajor = requiredClassMajor(tokens, workDir);
        Optional<String> configured = configuredJavaExecutable();
        if (configured.isPresent()) {
            if (requiredMajor.isEmpty()
                    || javaExecutableClassMajor(Path.of(configured.get())) >= requiredMajor.getAsInt()) {
                return configured.get();
            }
        }

        String currentJava = PlatformUtil.getCurrentJavaExecutable();
        int currentMajor = currentRuntimeClassMajor();
        if (requiredMajor.isPresent() && requiredMajor.getAsInt() > currentMajor) {
            Optional<String> compatible = findCompatibleJavaExecutable(requiredMajor.getAsInt());
            if (compatible.isPresent()) {
                return compatible.get();
            }
        }

        return currentJava;
    }

    /**
     * 설정된 Java 홈 또는 Java 명령어가 있으면 실행 파일 경로로 해석한다.
     */
    private Optional<String> configuredJavaExecutable() {
        if (settingsRepository == null) return Optional.empty();

        AppSettings settings = settingsRepository.get();
        String exeName = PlatformUtil.isWindows() ? "java.exe" : "java";

        String javaHome = settings.getJavaHome();
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Path.of(javaHome).resolve("bin").resolve(exeName);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().toString());
            }
        }

        String javaCommand = settings.getJavaCommand();
        if (javaCommand != null && !javaCommand.isBlank()
                && !javaCommand.equalsIgnoreCase("java")
                && !javaCommand.equalsIgnoreCase("java.exe")) {
            Path commandPath = Path.of(javaCommand);
            if (commandPath.isAbsolute() && Files.isRegularFile(commandPath)) {
                return Optional.of(commandPath.toAbsolutePath().toString());
            }
            return Optional.of(javaCommand);
        }

        return Optional.empty();
    }

    /**
     * java -jar 대상 JAR의 Main-Class class file major version을 읽는다.
     */
    private OptionalInt requiredClassMajor(List<String> tokens, String workDir) {
        int jarArgIndex = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if ("-jar".equalsIgnoreCase(tokens.get(i))) {
                jarArgIndex = i + 1;
                break;
            }
        }
        if (jarArgIndex <= 0 || jarArgIndex >= tokens.size()) {
            return OptionalInt.empty();
        }

        Path jarPath = Path.of(tokens.get(jarArgIndex));
        if (!jarPath.isAbsolute() && workDir != null && !workDir.isBlank()) {
            jarPath = Path.of(workDir).resolve(jarPath);
        }
        if (!Files.isRegularFile(jarPath)) {
            return OptionalInt.empty();
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) return OptionalInt.empty();

            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            if (mainClass == null || mainClass.isBlank()) return OptionalInt.empty();

            String entryName = mainClass.replace('.', '/') + ".class";
            var entry = jar.getJarEntry(entryName);
            if (entry == null) return OptionalInt.empty();

            try (InputStream in = jar.getInputStream(entry)) {
                byte[] header = in.readNBytes(8);
                if (header.length < 8) return OptionalInt.empty();
                int magic = ((header[0] & 0xff) << 24)
                        | ((header[1] & 0xff) << 16)
                        | ((header[2] & 0xff) << 8)
                        | (header[3] & 0xff);
                if (magic != 0xCAFEBABE) return OptionalInt.empty();
                int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
                return OptionalInt.of(major);
            }
        } catch (Exception e) {
            log.debug("Failed to inspect Java class version for service jar", e);
            return OptionalInt.empty();
        }
    }

    /**
     * 현재 앱 JVM이 인식하는 class file major version을 반환한다.
     */
    private int currentRuntimeClassMajor() {
        try {
            String classVersion = System.getProperty("java.class.version", "61");
            return (int) Double.parseDouble(classVersion);
        } catch (NumberFormatException e) {
            return 61;
        }
    }

    /**
     * 설치된 JDK/JRE 후보 중 필요한 class file major version을 실행할 수 있는 java를 찾는다.
     */
    private Optional<String> findCompatibleJavaExecutable(int requiredMajor) {
        Set<Path> candidates = new LinkedHashSet<>();
        addJavaCandidate(candidates, System.getenv("JAVA_HOME"));
        addJavaCandidate(candidates, "C:\\Program Files\\java\\jdk-21.0.2");
        addJavaInstallRoot(candidates, Path.of("C:\\Program Files\\java"));
        addJavaInstallRoot(candidates, Path.of("C:\\Program Files\\Eclipse Adoptium"));
        addJavaInstallRoot(candidates, Path.of("C:\\Program Files\\Microsoft"));

        for (Path candidate : candidates) {
            int major = javaExecutableClassMajor(candidate);
            if (major >= requiredMajor) {
                return Optional.of(candidate.toAbsolutePath().toString());
            }
        }
        return Optional.empty();
    }

    private void addJavaCandidate(Set<Path> candidates, String javaHome) {
        if (javaHome == null || javaHome.isBlank()) return;
        String exeName = PlatformUtil.isWindows() ? "java.exe" : "java";
        Path javaExe = Path.of(javaHome).resolve("bin").resolve(exeName);
        if (Files.isRegularFile(javaExe)) {
            candidates.add(javaExe);
        }
    }

    private void addJavaInstallRoot(Set<Path> candidates, Path root) {
        if (!PlatformUtil.isWindows() || !Files.isDirectory(root)) return;
        String exeName = PlatformUtil.isWindows() ? "java.exe" : "java";
        try (var stream = Files.list(root)) {
            stream
                    .map(path -> path.resolve("bin").resolve(exeName))
                    .filter(Files::isRegularFile)
                    .forEach(candidates::add);
        } catch (Exception e) {
            log.debug("Failed to scan Java install root: {}", root, e);
        }
    }

    private int javaExecutableClassMajor(Path javaExecutable) {
        try {
            Process process = new ProcessBuilder(javaExecutable.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return 0;
            }
            String output = new String(process.getInputStream().readAllBytes());
            OptionalInt feature = parseJavaFeatureVersion(output);
            return feature.isPresent() ? feature.getAsInt() + 44 : 0;
        } catch (Exception e) {
            log.debug("Failed to check java version: {}", javaExecutable, e);
            return 0;
        }
    }

    private OptionalInt parseJavaFeatureVersion(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return OptionalInt.empty();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("version\\s+\"(\\d+)(?:\\.(\\d+))?")
                .matcher(versionOutput);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        int first = Integer.parseInt(matcher.group(1));
        if (first == 1 && matcher.group(2) != null) {
            return OptionalInt.of(Integer.parseInt(matcher.group(2)));
        }
        return OptionalInt.of(first);
    }

    /**
     * 실행 중인 프로세스를 종료한다.
     * Windows는 taskkill /F, Unix는 destroy() 사용. 5초 대기 후 강제 종료.
     *
     * @param instance 중지할 서비스 인스턴스
     */
    public void stop(ServiceInstance instance) {
        if (instance.getStatus() != ServiceStatus.RUNNING
                && instance.getStatus() != ServiceStatus.STARTING) return;

        setStatus(instance, ServiceStatus.STOPPING);
        logService.addSystemLog(instance, "Stopping " + instance.getDefinition().getName() + "...");

        Process process = instance.getProcess();
        ServiceDefinition def = instance.getDefinition();

        if (process == null) {
            // process 객체 없음 — 고아 프로세스인 경우 PID 파일로 종료 시도
            OptionalLong savedPid = PidFileManager.read(def);
            if (savedPid.isPresent()) {
                long pid = savedPid.getAsLong();
                new Thread(() -> {
                    try {
                        logService.addSystemLog(instance, "고아 프로세스 종료 시도 (PID=" + pid + ")");
                if (PlatformUtil.isWindows()) {
                    Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/T", "/PID", String.valueOf(pid)})
                            .waitFor(3, TimeUnit.SECONDS);
                } else {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
                        PidFileManager.delete(def);
                        setStatus(instance, ServiceStatus.STOPPED);
                        logService.addSystemLog(instance, "Stopped.");
                    } catch (Exception e) {
                        log.error("Error stopping orphan process", e);
                        setStatus(instance, ServiceStatus.ERROR);
                    }
                }, "stop-" + def.getName()).start();
            } else {
                setStatus(instance, ServiceStatus.STOPPED);
            }
            return;
        }

        // PID 파일과 현재 프로세스 PID 일치 검증
        OptionalLong savedPid = PidFileManager.read(def);
        savedPid.ifPresent(pid -> {
            if (pid != process.pid()) {
                log.warn("PID mismatch for {}: file={}, process={} — stopping anyway",
                        def.getName(), pid, process.pid());
                logService.addSystemLog(instance,
                        "경고: PID 불일치 (파일=" + pid + ", 프로세스=" + process.pid() + ")");
            }
        });

        new Thread(() -> {
            try {
                if (PlatformUtil.isWindows()) {
                    Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/T", "/PID",
                            String.valueOf(process.pid())});
                } else {
                    process.destroy();
                }
                boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                if (!terminated) process.destroyForcibly();
                PidFileManager.delete(def);
                setStatus(instance, ServiceStatus.STOPPED);
                logService.addSystemLog(instance, "Stopped.");
            } catch (Exception e) {
                log.error("Error stopping process", e);
                setStatus(instance, ServiceStatus.ERROR);
            }
        }, "stop-" + def.getName()).start();
    }

    /**
     * 서비스를 중지하고 완전히 종료된 후 다시 시작한다.
     * stop 후 최대 4초 대기.
     *
     * @param instance 재시작할 서비스 인스턴스
     */
    public void restart(ServiceInstance instance) {
        stop(instance);
        new Thread(() -> {
            try {
                // Wait for process to fully stop
                for (int i = 0; i < 20; i++) {
                    if (instance.getStatus() == ServiceStatus.STOPPED
                            || instance.getStatus() == ServiceStatus.ERROR) break;
                    Thread.sleep(200);
                }
                Thread.sleep(500);
                start(instance);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "restart-" + instance.getDefinition().getName()).start();
    }

    /**
     * JavaFX Application Thread에서 상태를 변경하고 onStatusChange 콜백을 호출한다.
     *
     * @param instance 상태를 변경할 서비스 인스턴스
     * @param status   새로운 서비스 상태
     */
    private void setStatus(ServiceInstance instance, ServiceStatus status) {
        Platform.runLater(() -> {
            instance.setStatus(status);
            if (onStatusChange != null) onStatusChange.run();
        });
    }

    /**
     * 실행 중인 모든 서비스를 중지한다. 앱 종료 시 호출.
     */
    public void stopAll() {
        instances.values().forEach(inst -> {
            if (inst.getStatus() == ServiceStatus.RUNNING) stop(inst);
        });
    }

    /**
     * 실행 중인 모든 서비스를 호출 스레드에서 동기적으로 종료한다.
     * JVM ShutdownHook에서 호출 — 새 스레드를 생성하면 종료 전에 완료가 보장되지 않으므로
     * 블로킹 방식으로 직접 처리한다. UI 업데이트(Platform.runLater)는 수행하지 않는다.
     */
    public void stopAllSync() {
        for (ServiceInstance inst : instances.values()) {
            if (inst.getStatus() != ServiceStatus.RUNNING
                    && inst.getStatus() != ServiceStatus.STARTING) continue;
            ServiceDefinition def = inst.getDefinition();
            Process process = inst.getProcess();
            try {
                long pid;
                if (process != null) {
                    // PID 파일과 현재 프로세스 PID 일치 검증
                    OptionalLong savedPid = PidFileManager.read(def);
                    if (savedPid.isPresent() && savedPid.getAsLong() != process.pid()) {
                        log.warn("ShutdownHook PID mismatch for {}: file={}, process={}",
                                def.getName(), savedPid.getAsLong(), process.pid());
                    }
                    pid = process.pid();
                } else {
                    // 고아 프로세스: PID 파일에서 PID 조회
                    OptionalLong savedPid = PidFileManager.read(def);
                    if (savedPid.isEmpty()) continue;
                    pid = savedPid.getAsLong();
                }

                if (PlatformUtil.isWindows()) {
                    new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                            .start().waitFor(3, TimeUnit.SECONDS);
                } else {
                    if (process != null) process.destroy();
                    else ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
                if (process != null && !process.waitFor(5, TimeUnit.SECONDS))
                    process.destroyForcibly();

                PidFileManager.delete(def);
                log.info("ShutdownHook: stopped {} (PID={})", def.getName(), pid);
            } catch (Exception e) {
                log.warn("ShutdownHook: failed to stop {}", def.getName(), e);
            }
        }
    }
}
