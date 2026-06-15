/*
 * 작성자 : kyj
 * 작성일 : 2026-06-15
 */
package org.kyj.llmmanager.service;

import org.junit.jupiter.api.Test;
import org.kyj.llmmanager.model.ServiceDefinition;

import javax.script.ScriptException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ServiceCustomizer의 Groovy 스크립트 실행·바인딩 주입·예외 처리를 검증한다.
 */
class ServiceCustomizerTest {

    private static ServiceDefinition newDef() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName("Test Service");
        def.setStartCommand("python server.py");
        return def;
    }

    // ─────────────────────────────────────────────────────────────
    // null / blank 스크립트 — no-op
    // ─────────────────────────────────────────────────────────────

    @Test
    void nullScript_noChange() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, null);
        assertEquals("python server.py", def.getStartCommand());
    }

    @Test
    void blankScript_noChange() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, "   ");
        assertEquals("python server.py", def.getStartCommand());
    }

    @Test
    void emptyScript_noChange() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, "");
        assertEquals("python server.py", def.getStartCommand());
    }

    // ─────────────────────────────────────────────────────────────
    // 스크립트 실행 — service 바인딩
    // ─────────────────────────────────────────────────────────────

    @Test
    void scriptCanModifyStartCommand() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, "service.setStartCommand('python main.py --port 8080')");
        assertEquals("python main.py --port 8080", def.getStartCommand());
    }

    @Test
    void scriptCanModifyPort() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, "service.setPort(9999)");
        assertEquals(9999, def.getPort());
    }

    @Test
    void scriptCanModifyInstallDir() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, "service.setInstallDir('/opt/myservice')");
        assertEquals("/opt/myservice", def.getInstallDir());
    }

    @Test
    void scriptCanModifyWorkingDir() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, "service.setWorkingDir('/opt/myservice/run')");
        assertEquals("/opt/myservice/run", def.getWorkingDir());
    }

    @Test
    void scriptCanSetEnvVar() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def,
                "service.getEnvVars().put('MY_KEY', 'MY_VALUE')");
        assertEquals("MY_VALUE", def.getEnvVars().get("MY_KEY"));
    }

    // ─────────────────────────────────────────────────────────────
    // os 바인딩
    // ─────────────────────────────────────────────────────────────

    @Test
    void osBinding_isInjected() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def,
                "service.setDescription(os != null ? 'os-ok' : 'no-os')");
        assertEquals("os-ok", def.getDescription());
    }

    @Test
    void osBinding_isKnownValue() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def,
                "service.setDescription(os)");
        String os = def.getDescription();
        assertTrue(List.of("windows", "mac", "linux").contains(os),
                "os 바인딩은 windows/mac/linux 중 하나여야 한다: " + os);
    }

    // ─────────────────────────────────────────────────────────────
    // userHome 바인딩
    // ─────────────────────────────────────────────────────────────

    @Test
    void userHomeBinding_isInjected() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def,
                "service.setInstallDir(userHome + '/.test-svc')");
        assertFalse(def.getInstallDir().isBlank());
        assertTrue(def.getInstallDir().endsWith(".test-svc"),
                "userHome 기반 경로가 설정되어야 한다: " + def.getInstallDir());
    }

    // ─────────────────────────────────────────────────────────────
    // env 클로저 바인딩
    // ─────────────────────────────────────────────────────────────

    @Test
    void envClosure_isInjected() throws ScriptException {
        ServiceDefinition def = newDef();
        // PATH는 항상 존재하는 환경변수
        new ServiceCustomizer().apply(def,
                "def p = env('PATH'); service.setDescription(p != null ? 'found' : 'missing')");
        assertEquals("found", def.getDescription());
    }

    @Test
    void envClosure_returnsNullForUnknownVar() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def,
                "def v = env('__NO_SUCH_VAR_XYZ__'); service.setDescription(v == null ? 'null' : 'found')");
        assertEquals("null", def.getDescription());
    }

    // ─────────────────────────────────────────────────────────────
    // arch 바인딩
    // ─────────────────────────────────────────────────────────────

    @Test
    void archBinding_isInjected() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def,
                "service.setDescription(arch != null ? 'arch-ok' : 'no-arch')");
        assertEquals("arch-ok", def.getDescription());
    }

    // ─────────────────────────────────────────────────────────────
    // 조건 분기 스크립트
    // ─────────────────────────────────────────────────────────────

    @Test
    void conditionalOs_setsWorkingDir() throws ScriptException {
        ServiceDefinition def = newDef();
        new ServiceCustomizer().apply(def, """
                if (os == 'windows') {
                    service.setWorkingDir('C:/opt/svc')
                } else {
                    service.setWorkingDir('/opt/svc')
                }
                """);
        assertNotNull(def.getWorkingDir());
        assertFalse(def.getWorkingDir().isBlank());
    }

    // ─────────────────────────────────────────────────────────────
    // 오류 처리
    // ─────────────────────────────────────────────────────────────

    @Test
    void invalidScript_throwsScriptException() {
        ServiceDefinition def = newDef();
        assertThrows(ScriptException.class, () ->
                new ServiceCustomizer().apply(def, "throw new RuntimeException('fail')"));
    }

    @Test
    void syntaxError_throwsScriptException() {
        ServiceDefinition def = newDef();
        assertThrows(ScriptException.class, () ->
                new ServiceCustomizer().apply(def, "def x = {{{{"));
    }
}
