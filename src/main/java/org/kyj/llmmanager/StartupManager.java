/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager;

import org.kyj.llmmanager.util.CommandBuilder;
import org.kyj.llmmanager.util.PlatformUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * OS 시작 시 자동 실행 등록/해제.
 * Windows: HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run 레지스트리
 * Linux  : ~/.config/autostart/llm-manager.desktop
 * macOS  : ~/Library/LaunchAgents/org.kyj.llmmanager.plist
 */
public class StartupManager {
    private static final Logger log = LoggerFactory.getLogger(StartupManager.class);
    private static final String APP_NAME = "LLMManager";

    /** 현재 실행 중인 JAR/클래스 경로 자동 감지 */
    public static String detectLaunchPath() {
        try {
            File jar = new File(StartupManager.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------------
    // 등록 여부 확인
    // ----------------------------------------------------------------

    public static boolean isRegistered() {
        if (PlatformUtil.isWindows()) return isRegisteredWindows();
        if (PlatformUtil.isLinux())   return isRegisteredLinux();
        if (PlatformUtil.isMac())     return isRegisteredMac();
        return false;
    }

    // ----------------------------------------------------------------
    // 등록
    // ----------------------------------------------------------------

    public static boolean register(String launchCommand) {
        try {
            if (PlatformUtil.isWindows()) return registerWindows(launchCommand);
            if (PlatformUtil.isLinux())   return registerLinux(launchCommand);
            if (PlatformUtil.isMac())     return registerMac(launchCommand);
        } catch (Exception e) {
            log.error("Failed to register startup", e);
        }
        return false;
    }

    // ----------------------------------------------------------------
    // 해제
    // ----------------------------------------------------------------

    public static boolean unregister() {
        try {
            if (PlatformUtil.isWindows()) return unregisterWindows();
            if (PlatformUtil.isLinux())   return unregisterLinux();
            if (PlatformUtil.isMac())     return unregisterMac();
        } catch (Exception e) {
            log.error("Failed to unregister startup", e);
        }
        return false;
    }

    // ================================================================
    // Windows
    // ================================================================

    private static final String WIN_REG_PATH =
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run";

    private static boolean isRegisteredWindows() {
        try {
            Process p = new ProcessBuilder("reg", "query", WIN_REG_PATH, "/v", APP_NAME)
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private static boolean registerWindows(String launchCommand) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(
                "reg", "add", WIN_REG_PATH,
                "/v", APP_NAME,
                "/t", "REG_SZ",
                "/d", launchCommand,
                "/f")
                .redirectErrorStream(true).start();
        int exit = p.waitFor();
        log.info("Windows startup register exit={}", exit);
        return exit == 0;
    }

    private static boolean unregisterWindows() throws IOException, InterruptedException {
        Process p = new ProcessBuilder(
                "reg", "delete", WIN_REG_PATH,
                "/v", APP_NAME, "/f")
                .redirectErrorStream(true).start();
        return p.waitFor() == 0;
    }

    // ================================================================
    // Linux (XDG autostart .desktop)
    // ================================================================

    private static Path linuxDesktopFile() {
        return Path.of(System.getProperty("user.home"),
                ".config", "autostart", "llm-manager.desktop");
    }

    private static boolean isRegisteredLinux() {
        return Files.exists(linuxDesktopFile());
    }

    private static boolean registerLinux(String launchCommand) throws IOException {
        Path file = linuxDesktopFile();
        Files.createDirectories(file.getParent());
        String content = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=LLM Manager\n"
                + "Exec=" + launchCommand + "\n"
                + "Hidden=false\n"
                + "NoDisplay=false\n"
                + "X-GNOME-Autostart-enabled=true\n";
        Files.writeString(file, content);
        log.info("Linux autostart registered: {}", file);
        return true;
    }

    private static boolean unregisterLinux() throws IOException {
        Path file = linuxDesktopFile();
        Files.deleteIfExists(file);
        return true;
    }

    // ================================================================
    // macOS (LaunchAgent plist)
    // ================================================================

    private static Path macPlistFile() {
        return Path.of(System.getProperty("user.home"),
                "Library", "LaunchAgents", "org.kyj.llmmanager.plist");
    }

    private static boolean isRegisteredMac() {
        return Files.exists(macPlistFile());
    }

    private static boolean registerMac(String launchCommand) throws IOException, InterruptedException {
        Path file = macPlistFile();
        Files.createDirectories(file.getParent());
        List<String> parts = CommandBuilder.splitCommand(launchCommand);
        StringBuilder args = new StringBuilder();
        for (String p : parts) {
            args.append("        <string>").append(xmlEscape(p)).append("</string>\n");
        }
        String plist = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
                + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\"><dict>\n"
                + "  <key>Label</key><string>org.kyj.llmmanager</string>\n"
                + "  <key>ProgramArguments</key><array>\n"
                + args
                + "  </array>\n"
                + "  <key>RunAtLoad</key><true/>\n"
                + "</dict></plist>\n";
        Files.writeString(file, plist);
        new ProcessBuilder("launchctl", "load", file.toString()).start().waitFor();
        log.info("macOS LaunchAgent registered: {}", file);
        return true;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static boolean unregisterMac() throws IOException, InterruptedException {
        Path file = macPlistFile();
        if (Files.exists(file)) {
            new ProcessBuilder("launchctl", "unload", file.toString()).start().waitFor();
            Files.delete(file);
        }
        return true;
    }
}
