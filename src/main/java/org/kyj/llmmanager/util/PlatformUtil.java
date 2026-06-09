/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.util;

import java.nio.file.Path;
import java.nio.file.Files;

/**
 * OS 종류 감지 및 플랫폼별 기본 경로·명령어를 제공하는 유틸리티.
 */
public class PlatformUtil {

    /** JVM이 감지한 소문자 OS 이름 (os.name 프로퍼티) */
    private static final String OS = System.getProperty("os.name", "").toLowerCase();

    public static boolean isWindows() { return OS.contains("win"); }
    public static boolean isMac() { return OS.contains("mac"); }
    public static boolean isLinux() { return OS.contains("nix") || OS.contains("nux"); }

    /**
     * 앱 홈 디렉토리. 설정 파일·로그·서비스 설치 기본 경로가 모두 이 아래에 위치한다.
     * OS 무관하게 ~/llm-services 로 통일한다.
     *
     * @return ~/llm-services 경로
     */
    public static Path getAppHome() {
        return Path.of(System.getProperty("user.home"), "llm-services");
    }

    /**
     * 서비스 설치 기본 루트 경로. {@link #getAppHome()}과 동일.
     *
     * @return ~/llm-services 경로
     */
    public static Path getDefaultInstallBase() {
        return getAppHome();
    }

    /**
     * 경로 문자열에 포함된 변수를 실제 값으로 치환한다.
     *
     * <p>지원 변수:
     * <ul>
     *   <li>{@code ${user.home}} — 사용자 홈 디렉토리</li>
     *   <li>{@code ${llm.home}}  — LLM Manager 앱 홈 (~/llm-services)</li>
     * </ul>
     *
     * @param path 변수가 포함된 경로 문자열 (null 허용)
     * @return 변수가 치환된 경로 문자열, null 입력 시 null
     */
    public static String resolvePath(String path) {
        if (path == null) return null;
        return path
                .replace("${user.home}", System.getProperty("user.home"))
                .replace("${llm.home}",  getAppHome().toString());
    }

    /**
     * 이 프로세스를 실행 중인 JVM의 java 실행 파일 절대 경로를 반환한다.
     * jpackage 배포 환경에서 번들 JRE를 사용하고,
     * 일반 개발 환경에서는 현재 JDK/JRE의 java를 사용한다.
     *
     * <p>서비스 startCommand의 bare {@code java}를 이 경로로 치환하면
     * 시스템 JAVA_HOME과 무관하게 항상 동일한 JRE로 서비스를 실행할 수 있다.
     *
     * @return java 실행 파일 절대 경로 (예: C:\app\runtime\bin\java.exe)
     */
    public static String getCurrentJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        String exeName  = isWindows() ? "java.exe" : "java";
        Path candidate  = Path.of(javaHome, "bin", exeName);
        if (Files.exists(candidate)) {
            return candidate.toAbsolutePath().toString();
        }
        // java.home이 jre/ 하위를 가리키는 구버전 JDK 대응 (JDK8 이하)
        Path parent = Path.of(javaHome).getParent();
        if (parent != null) {
            Path alt = parent.resolve(Path.of("bin", exeName));
            if (Files.exists(alt)) return alt.toAbsolutePath().toString();
        }
        return "java"; // 최후 폴백 — PATH 의존
    }

    /**
     * 플랫폼에 맞는 Python 명령어 이름을 반환한다.
     * Windows는 {@code python}, Unix는 {@code python3}.
     *
     * @return Python 실행 명령어 이름
     */
    public static String getPythonCommand() {
        return isWindows() ? "python" : "python3";
    }

    /**
     * 플랫폼에 맞는 Node.js 명령어 이름을 반환한다.
     *
     * @return Node.js 실행 명령어 이름
     */
    public static String getNodeCommand() {
        return "node";
    }

    /**
     * 플랫폼에 맞는 pip 명령어 이름을 반환한다.
     * Windows는 {@code pip}, Unix는 {@code pip3}.
     *
     * @return pip 실행 명령어 이름
     */
    public static String getPipCommand() {
        return isWindows() ? "pip" : "pip3";
    }
}
