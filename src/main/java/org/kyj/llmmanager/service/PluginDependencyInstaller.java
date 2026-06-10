/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.util.PlatformUtil;
import org.kyj.llmmanager.util.ToolLocator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 플러그인 디렉토리의 런타임 의존성을 설치한다.
 */
public class PluginDependencyInstaller {

    /**
     * 설치 진행 상황을 UI에 전달하는 콜백.
     */
    public interface ProgressCallback {
        void onLog(String message);
        void onDone(boolean success);
    }

    /**
     * 플러그인 디렉토리에 package.json이 있으면 npm install을 실행한다.
     *
     * @param plugin 설치 대상 플러그인
     * @param cb 진행 상황 콜백
     */
    public void install(LoadedPlugin plugin, ProgressCallback cb) {
        new Thread(() -> doInstall(plugin, cb), "plugin-install-" + plugin.getDirectory().getFileName()).start();
    }

    /**
     * 플러그인의 npm 및 pip 의존성이 모두 설치되어 있는지 확인한다.
     *
     * @param plugin 확인할 플러그인
     * @return npm(node_modules 또는 global 패키지)과 pip 패키지가 모두 충족되면 true
     */
    public boolean isInstalled(LoadedPlugin plugin) {
        if (plugin == null || plugin.getManifest() == null) return false;

        boolean npmOk = isNpmInstalled(plugin);
        boolean pipOk = isPipInstalled(plugin);
        return npmOk && pipOk;
    }

    /**
     * npm 또는 pip 의존성 중 하나라도 설치가 필요한 플러그인인지 확인한다.
     *
     * @param plugin 확인할 플러그인
     * @return 설치가 필요하면 true
     */
    public boolean canInstall(LoadedPlugin plugin) {
        if (plugin == null || plugin.getDirectory() == null || plugin.getManifest() == null) return false;
        boolean hasNpm = !plugin.getManifest().getInstall().getNpmPackages().isEmpty()
                || Files.isRegularFile(plugin.getDirectory().resolve("package.json"));
        boolean hasPip = !plugin.getManifest().getInstall().getPipPackages().isEmpty()
                || Files.isRegularFile(plugin.getDirectory().resolve("requirements.txt"));
        return hasNpm || hasPip;
    }

    private boolean isNpmInstalled(LoadedPlugin plugin) {
        List<String> npmPkgs = plugin.getManifest().getInstall().getNpmPackages();
        if (npmPkgs.isEmpty() && !Files.isRegularFile(plugin.getDirectory().resolve("package.json"))) {
            return true; // npm 의존성 없음
        }
        if (isGlobalInstall(plugin)) {
            return npmPkgs.stream()
                    .map(this::packageNameOnly)
                    .allMatch(this::isGlobalNpmPackageInstalled);
        }
        return plugin.getDirectory() != null
                && Files.isDirectory(plugin.getDirectory().resolve("node_modules"));
    }

    private boolean isPipInstalled(LoadedPlugin plugin) {
        List<String> pipPkgs = plugin.getManifest().getInstall().getPipPackages();
        boolean hasReqFile = Files.isRegularFile(plugin.getDirectory().resolve("requirements.txt"));
        if (pipPkgs.isEmpty() && !hasReqFile) return true; // pip 의존성 없음
        return pipPkgs.stream()
                .map(this::pipPackageNameOnly)
                .allMatch(this::isPipPackageInstalled);
    }

    private void doInstall(LoadedPlugin plugin, ProgressCallback cb) {
        if (!canInstall(plugin)) {
            cb.onLog("설치할 의존성이 없습니다.");
            cb.onDone(false);
            return;
        }

        Path pluginDir = plugin.getDirectory().toAbsolutePath().normalize();
        boolean success = true;

        // npm 의존성 설치
        boolean hasNpm = !plugin.getManifest().getInstall().getNpmPackages().isEmpty()
                || Files.isRegularFile(pluginDir.resolve("package.json"));
        if (hasNpm) {
            // node/npm 가용성 먼저 확인 — 미설치 시 'npm is not recognized' 대신 안내 메시지.
            // ToolLocator가 설치 직후 PATH 미반영 케이스도 알려진 경로 폴백으로 처리
            if (ToolLocator.resolveCommand("node") == null
                    || ToolLocator.resolveCommand("npm") == null) {
                cb.onLog("[오류] Node.js(npm)를 찾을 수 없습니다.");
                cb.onLog("메뉴 [설정 > 필요 라이브러리 설치]에서 Node.js를 설치한 뒤 다시 시도하세요.");
                cb.onDone(false);
                return;
            }
            success = runInstallCommand(npmInstallCommand(plugin), isGlobalInstall(plugin) ? null : pluginDir, cb);
        }

        // pip 의존성 설치
        if (success) {
            boolean hasPip = !plugin.getManifest().getInstall().getPipPackages().isEmpty()
                    || Files.isRegularFile(pluginDir.resolve("requirements.txt"));
            if (hasPip) {
                // python/pip 가용성 먼저 확인
                String python = ToolLocator.resolveCommand("python");
                String pip = ToolLocator.resolveCommand("pip");
                if (python == null) {
                    cb.onLog("[오류] Python을 찾을 수 없습니다. 메뉴 [설정 > 필요 라이브러리 설치]에서 설치하세요.");
                    cb.onDone(false);
                    return;
                }
                if (pip == null) {
                    cb.onLog("[오류] pip를 찾을 수 없습니다. 메뉴 [설정 > 필요 라이브러리 설치]에서 Python 3을 설치하세요.");
                    cb.onDone(false);
                    return;
                }
                String pipCmd = pipInstallCommand(plugin, pip, pluginDir);
                success = runInstallCommand(pipCmd, pluginDir, cb);
            }
        }

        cb.onDone(success);
    }

    private boolean runInstallCommand(String commandText, Path workDir, ProgressCallback cb) {
        String[] command = PlatformUtil.isWindows()
                ? new String[]{"cmd.exe", "/c", commandText}
                : new String[]{"bash", "-lc", commandText};
        try {
            cb.onLog("$ " + commandText);
            if (workDir != null) cb.onLog("cwd: " + workDir);
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null) pb.directory(workDir.toFile());
            // 설치 직후 PATH 미반영 케이스 — 셸이 npm/pip 등을 찾도록 알려진 도구 경로 추가
            ToolLocator.augmentPath(pb.environment());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    cb.onLog(line);
                }
            }

            int exit = process.waitFor();
            cb.onLog("exit code: " + exit);
            return exit == 0;
        } catch (Exception e) {
            cb.onLog("설치 오류: " + e.getMessage());
            return false;
        }
    }

    private boolean isGlobalInstall(LoadedPlugin plugin) {
        return plugin.getManifest() != null
                && plugin.getManifest().getInstall() != null
                && "global".equalsIgnoreCase(plugin.getManifest().getInstall().getScope());
    }

    private String npmInstallCommand(LoadedPlugin plugin) {
        List<String> packages = plugin.getManifest().getInstall().getNpmPackages();
        if (isGlobalInstall(plugin) && !packages.isEmpty()) {
            return "npm install -g " + String.join(" ", packages);
        }
        if (!packages.isEmpty()) {
            return "npm install " + String.join(" ", packages);
        }
        return "npm install";
    }

    private String pipInstallCommand(LoadedPlugin plugin, String pip, Path pluginDir) {
        List<String> packages = plugin.getManifest().getInstall().getPipPackages();
        // requirements.txt가 있으면 우선 사용
        if (packages.isEmpty() && Files.isRegularFile(pluginDir.resolve("requirements.txt"))) {
            return quoteIfNeeded(pip) + " install -r requirements.txt";
        }
        return quoteIfNeeded(pip) + " install " + String.join(" ", packages);
    }

    /** 절대 경로로 해석된 명령에 공백이 있으면 셸 실행용으로 따옴표를 감싼다. */
    private String quoteIfNeeded(String command) {
        return command.contains(" ") ? "\"" + command + "\"" : command;
    }

    private String packageNameOnly(String packageSpec) {
        if (packageSpec == null || packageSpec.isBlank()) return "";
        if (packageSpec.startsWith("@")) {
            int versionIndex = packageSpec.indexOf('@', 1);
            return versionIndex > 0 ? packageSpec.substring(0, versionIndex) : packageSpec;
        }
        int versionIndex = packageSpec.indexOf('@');
        return versionIndex > 0 ? packageSpec.substring(0, versionIndex) : packageSpec;
    }

    /** pip 패키지 스펙에서 패키지명만 추출한다. 예: "requests>=2.28" → "requests" */
    private String pipPackageNameOnly(String packageSpec) {
        if (packageSpec == null || packageSpec.isBlank()) return "";
        int idx = packageSpec.indexOf('[');
        if (idx > 0) packageSpec = packageSpec.substring(0, idx);
        for (String sep : new String[]{">=", "<=", "!=", "==", ">", "<", "~="}) {
            int i = packageSpec.indexOf(sep);
            if (i > 0) return packageSpec.substring(0, i).trim();
        }
        return packageSpec.trim();
    }

    private boolean isGlobalNpmPackageInstalled(String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        try {
            String[] command = PlatformUtil.isWindows()
                    ? new String[]{"cmd.exe", "/c", "npm root -g"}
                    : new String[]{"bash", "-lc", "npm root -g"};
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            // 설치 직후 PATH 미반영 케이스 — 셸이 npm을 찾도록 알려진 도구 경로 추가
            ToolLocator.augmentPath(pb.environment());
            Process process = pb.start();
            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            if (!done || process.exitValue() != 0) return false;
            String root = new String(process.getInputStream().readAllBytes()).trim();
            if (root.isBlank()) return false;
            return Files.isDirectory(Path.of(root).resolve(packageName));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * pip show 명령으로 패키지 설치 여부를 확인한다.
     *
     * @param packageName 패키지명 (버전 수식자 제외)
     * @return 설치되어 있으면 true
     */
    private boolean isPipPackageInstalled(String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        String pip = ToolLocator.resolveCommand("pip");
        if (pip == null) return false;
        String commandText = quoteIfNeeded(pip) + " show " + packageName;
        try {
            String[] command = PlatformUtil.isWindows()
                    ? new String[]{"cmd.exe", "/c", commandText}
                    : new String[]{"bash", "-lc", commandText};
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            return done && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
