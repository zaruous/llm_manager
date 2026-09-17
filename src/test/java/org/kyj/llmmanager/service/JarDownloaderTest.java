/*
 * 작성자 : kyj
 * 작성일 : 2026-09-17
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 다운로드 실패 안내문이 원인·직접 다운로드 URL·설치 경로를 모두 담는지 검증한다.
 */
class JarDownloaderTest {

    private static final String URL =
            "https://github.com/zaruous/sql-mcp-release/releases/latest/download/sql-gen-mcp.jar";

    @Test
    void failureMessageContainsCauseUrlAndTargetDir() {
        Path dir = Path.of("D:/llm-services/sql-gen-mcp");
        String msg = JarDownloader.failureMessage(
                new ConnectException("Permission denied: getsockopt"), URL, dir);

        assertTrue(msg.startsWith("실패: Permission denied: getsockopt"), msg);
        assertTrue(msg.contains(URL), "브라우저로 받을 URL이 있어야 한다");
        assertTrue(msg.contains(dir.toString()), "JAR을 넣을 설치 경로가 있어야 한다");
        assertTrue(msg.contains("브라우저"), "직접 다운로드 안내가 있어야 한다");
    }

    @Test
    void failureMessageFallsBackToExceptionTypeWhenMessageMissing() {
        String msg = JarDownloader.failureMessage(new ConnectException(), URL, Path.of("x"));
        assertTrue(msg.startsWith("실패: ConnectException"), msg);
    }
}
