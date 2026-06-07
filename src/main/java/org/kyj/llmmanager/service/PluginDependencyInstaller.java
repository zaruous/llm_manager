/*
 * 작성자 : kyj
 * 작성일 : 2026-06-07
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.plugin.LoadedPlugin;
import org.kyj.llmmanager.util.PlatformUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
     * 플러그인 npm 의존성이 이미 설치되어 있는지 간단히 확인한다.
     *
     * @param plugin 확인할 플러그인
     * @return node_modules가 있으면 true
     */
    public boolean isInstalled(LoadedPlugin plugin) {
        if (plugin == null || plugin.getManifest() == null) return false;
        if (isGlobalInstall(plugin)) {
            return plugin.getManifest().getInstall().getNpmPackages().stream()
                    .map(this::packageNameOnly)
                    .allMatch(this::isGlobalNpmPackageInstalled);
        }
        return plugin.getDirectory() != null
                && Files.isDirectory(plugin.getDirectory().resolve("node_modules"));
    }

    /**
     * package.json 기반 설치가 가능한 플러그인인지 확인한다.
     *
     * @param plugin 확인할 플러그인
     * @return package.json이 있으면 true
     */
    public boolean canInstall(LoadedPlugin plugin) {
        return plugin != null
                && plugin.getDirectory() != null
                && plugin.getManifest() != null
                && (!plugin.getManifest().getInstall().getNpmPackages().isEmpty()
                    || Files.isRegularFile(plugin.getDirectory().resolve("package.json")));
    }

    private void doInstall(LoadedPlugin plugin, ProgressCallback cb) {
        if (!canInstall(plugin)) {
            cb.onLog("package.json이 없어 설치할 의존성이 없습니다.");
            cb.onDone(false);
            return;
        }

        Path pluginDir = plugin.getDirectory().toAbsolutePath().normalize();
        String commandText = installCommand(plugin);
        String[] command = PlatformUtil.isWindows()
                ? new String[]{"cmd.exe", "/c", commandText}
                : new String[]{"bash", "-lc", commandText};

        try {
            cb.onLog("$ " + commandText);
            cb.onLog(isGlobalInstall(plugin) ? "scope: global" : "cwd: " + pluginDir);
            ProcessBuilder pb = new ProcessBuilder(command);
            if (!isGlobalInstall(plugin)) {
                pb.directory(pluginDir.toFile());
            }
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
            cb.onDone(exit == 0);
        } catch (Exception e) {
            cb.onLog("설치 오류: " + e.getMessage());
            cb.onDone(false);
        }
    }

    private boolean isGlobalInstall(LoadedPlugin plugin) {
        return plugin.getManifest() != null
                && plugin.getManifest().getInstall() != null
                && "global".equalsIgnoreCase(plugin.getManifest().getInstall().getScope());
    }

    private String installCommand(LoadedPlugin plugin) {
        List<String> packages = plugin.getManifest().getInstall().getNpmPackages();
        if (isGlobalInstall(plugin) && !packages.isEmpty()) {
            return "npm install -g " + String.join(" ", packages);
        }
        if (!packages.isEmpty()) {
            return "npm install " + String.join(" ", packages);
        }
        return "npm install";
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

    private boolean isGlobalNpmPackageInstalled(String packageName) {
        if (packageName == null || packageName.isBlank()) return false;
        try {
            String[] command = PlatformUtil.isWindows()
                    ? new String[]{"cmd.exe", "/c", "npm root -g"}
                    : new String[]{"bash", "-lc", "npm root -g"};
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(5, TimeUnit.SECONDS);
            if (!done || process.exitValue() != 0) return false;
            String root = new String(process.getInputStream().readAllBytes()).trim();
            if (root.isBlank()) return false;
            return Files.isDirectory(Path.of(root).resolve(packageName));
        } catch (Exception e) {
            return false;
        }
    }
}
