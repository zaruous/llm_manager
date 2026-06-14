/*
 * 작성자 : kyj
 * 작성일 : 2026-06-10
 */
package org.kyj.llmmanager.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 외부 런타임 도구(python, pip, node, npm)의 실행 명령을 찾는 공용 로케이터.
 *
 * PATH 조회를 우선하고, 설치 직후 PATH가 실행 중인 JVM에 반영되지 않는 Windows
 * 케이스를 위해 알려진 기본 설치 경로를 폴백으로 탐색한다. SetupChecker·
 * InstallationService·PluginDependencyInstaller·ProcessManager가 공유한다.
 * 스레드 안전: 탐색 성공 결과만 캐시하며, 실패는 캐시하지 않아 설치 후 재탐색된다.
 */
public final class ToolLocator {

    /** 도구별 확인 프로세스의 최대 대기 시간 (초) */
    private static final int TIMEOUT_SEC = 5;

    /** 도구 키 → 확인된 실행 명령 캐시. 성공만 저장해 설치 전 실패가 고착되지 않게 한다. */
    private static final Map<String, String> RESOLVED = new ConcurrentHashMap<>();

    private ToolLocator() { }

    /**
     * 도구 실행 명령 후보 목록을 반환한다. PATH 이름이 앞, 알려진 설치 경로의
     * 절대 경로(존재하는 것만)가 뒤에 온다.
     *
     * @param tool "python" | "pip" | "node" | "npm"
     * @return 시도 순서대로 정렬된 실행 명령 후보 (최소 1개)
     */
    public static List<String> candidateCommands(String tool) {
        List<String> candidates = new ArrayList<>(pathNames(tool));
        for (Path exe : knownExecutables(tool)) {
            candidates.add(exe.toString());
        }
        return candidates;
    }

    /**
     * PATH 우선, 알려진 설치 경로 폴백으로 실행 가능한 명령을 찾는다.
     * "<명령> --version"이 종료 코드 0이면 사용 가능으로 판정한다.
     *
     * @param tool "python" | "pip" | "node" | "npm"
     * @return 실행 가능한 명령(이름 또는 절대 경로), 없으면 null
     */
    public static String resolveCommand(String tool) {
        String cached = RESOLVED.get(tool);
        if (cached != null) return cached;
        for (String candidate : candidateCommands(tool)) {
            if (runsOk(candidate)) {
                RESOLVED.put(tool, candidate);
                return candidate;
            }
        }
        return null;
    }

    /**
     * 알려진 도구 설치 디렉토리(존재하는 것만)를 자식 프로세스 환경의 PATH 앞에 추가한다.
     * cmd.exe/bash 경유 실행은 자식 환경의 PATH로 명령을 찾으므로, 설치 직후
     * 앱 재시작 없이 npm·pip 등이 동작하게 한다. 직접 exec(셸 미경유)에는 효과가 없으니
     * 그 경우 {@link #resolveCommand(String)}의 절대 경로를 사용해야 한다.
     *
     * @param env ProcessBuilder.environment() 등 수정 가능한 환경변수 맵
     */
    public static void augmentPath(Map<String, String> env) {
        List<Path> dirs = knownToolDirs();
        if (dirs.isEmpty()) return;

        // Windows ProcessBuilder 환경맵은 대소문자 무시지만, 일반 맵 대비를 위해 키를 직접 찾는다
        String pathKey = env.keySet().stream()
                .filter(k -> k.equalsIgnoreCase("PATH"))
                .findFirst().orElse("PATH");
        String current = env.getOrDefault(pathKey, "");

        List<String> existing = List.of(current.split(java.io.File.pathSeparator));
        StringBuilder prefix = new StringBuilder();
        for (Path dir : dirs) {
            String d = dir.toString();
            if (existing.contains(d)) continue;
            if (prefix.length() > 0) prefix.append(java.io.File.pathSeparator);
            prefix.append(d);
        }
        if (prefix.length() == 0) return;
        env.put(pathKey, current.isBlank()
                ? prefix.toString()
                : prefix + java.io.File.pathSeparator + current);
    }

    /** 캐시를 비운다. 도구 제거 후 재탐색이 필요할 때 사용한다. */
    public static void invalidateCache() {
        RESOLVED.clear();
    }

    // ─────────────────────────────────────────────
    // 내부 탐색 로직
    // ─────────────────────────────────────────────

    /** 도구별 PATH 조회용 명령 이름. python3을 우선해 Python 2 환경과의 충돌을 줄인다. */
    private static List<String> pathNames(String tool) {
        return switch (tool) {
            case "python" -> List.of("python3", "python");
            case "pip"    -> List.of("pip3", "pip");
            default       -> List.of(tool);
        };
    }

    /**
     * 알려진 기본 설치 경로에서 도구 실행 파일을 찾는다 (Windows 전용 폴백).
     *
     * @return 존재하는 실행 파일 절대 경로 목록 (Windows 외 OS에서는 빈 목록)
     */
    private static List<Path> knownExecutables(String tool) {
        List<Path> found = new ArrayList<>();
        if (!PlatformUtil.isWindows()) return found;

        switch (tool) {
            case "python" -> {
                for (Path dir : pythonInstallDirs()) {
                    Path exe = dir.resolve("python.exe");
                    if (Files.isExecutable(exe)) found.add(exe);
                }
            }
            case "pip" -> {
                for (Path dir : pythonInstallDirs()) {
                    Path exe = dir.resolve("Scripts").resolve("pip.exe");
                    if (Files.isExecutable(exe)) found.add(exe);
                }
            }
            case "node" -> {
                Path exe = nodeInstallDir().resolve("node.exe");
                if (Files.isExecutable(exe)) found.add(exe);
            }
            case "npm" -> {
                Path cmd = nodeInstallDir().resolve("npm.cmd");
                if (Files.isRegularFile(cmd)) found.add(cmd);
            }
            default -> { }
        }
        return found;
    }

    /**
     * 존재하는 알려진 도구 설치 디렉토리를 반환한다 (PATH 추가용).
     * pip를 위해 Python의 Scripts 하위 디렉토리도 포함한다.
     */
    private static List<Path> knownToolDirs() {
        List<Path> dirs = new ArrayList<>();
        if (!PlatformUtil.isWindows()) return dirs;

        Path nodeDir = nodeInstallDir();
        if (Files.isDirectory(nodeDir)) dirs.add(nodeDir);

        for (Path dir : pythonInstallDirs()) {
            dirs.add(dir);
            Path scripts = dir.resolve("Scripts");
            if (Files.isDirectory(scripts)) dirs.add(scripts);
        }
        return dirs;
    }

    /** Node.js MSI 기본 설치 디렉토리 (install-nodejs.ps1과 동일 경로). */
    private static Path nodeInstallDir() {
        return Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "nodejs");
    }

    /**
     * install-python.ps1이 사용하는 기본 설치 디렉토리에서 Python3x 디렉토리를 찾는다.
     * 관리자 설치(C:\Python3xx)와 사용자 설치(%LOCALAPPDATA%\Programs\Python\Python3xx) 모두 탐색.
     */
    private static List<Path> pythonInstallDirs() {
        List<Path> found = new ArrayList<>();

        List<Path> roots = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            roots.add(Path.of(localAppData, "Programs", "Python"));
        }
        roots.add(Path.of("C:\\"));

        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, "Python*")) {
                for (Path dir : dirs) {
                    if (Files.isExecutable(dir.resolve("python.exe"))) found.add(dir);
                }
            } catch (IOException ignored) {
                // 루트 접근 불가 시 해당 루트만 건너뜀
            }
        }
        return found;
    }

    /**
     * "<명령> --version"을 실행해 종료 코드 0인지 확인한다.
     * npm.cmd 같은 스크립트도 실행되도록 Windows는 cmd.exe를 경유한다.
     */
    private static boolean runsOk(String command) {
        try {
            String[] cmd = PlatformUtil.isWindows()
                    ? new String[]{"cmd.exe", "/c", command, "--version"}
                    : new String[]{"bash", "-lc", command + " --version"};
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
