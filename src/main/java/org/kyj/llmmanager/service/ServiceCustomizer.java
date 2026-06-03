/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import org.kyj.llmmanager.model.ServiceDefinition;
import org.kyj.llmmanager.util.PlatformUtil;
import groovy.lang.Closure;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * Groovy 스크립트를 실행해 ServiceDefinition을 동적으로 수정한다.
 * OS·아키텍처·환경변수에 따라 설치 경로·명령어 등을 자동 결정할 때 사용.
 */
public class ServiceCustomizer {

    /**
     * Groovy 스크립트를 실행하고 def 객체를 직접 수정한다.
     * 바인딩 변수: service(ServiceDefinition), os(windows/mac/linux),
     * userHome, arch(x64/arm64), env(클로저).
     *
     * @param def          수정할 서비스 정의
     * @param groovyScript 실행할 Groovy 스크립트 문자열
     * @throws ScriptException Groovy 스크립트 실행 중 오류 발생 시
     */
    public void apply(ServiceDefinition def, String groovyScript) throws ScriptException {
        if (groovyScript == null || groovyScript.isBlank()) return;

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("groovy");
        if (engine == null) throw new ScriptException("Groovy 스크립트 엔진을 초기화할 수 없습니다.");

        Bindings b = engine.createBindings();
        b.put("service", def);
        b.put("os", resolveOs());
        b.put("userHome", System.getProperty("user.home"));
        b.put("arch", System.getProperty("os.arch").contains("aarch64") ? "arm64" : "x64");
        b.put("env", new Closure<String>(null) {
            @Override
            public String call(Object... args) {
                String name = String.valueOf(args[0]);
                String v = System.getenv(name);
                return v != null ? v : System.getProperty(name);
            }
        });

        engine.eval(groovyScript, b);
    }

    /**
     * 현재 OS를 windows / mac / linux 문자열로 반환한다.
     *
     * @return OS 식별 문자열
     */
    private String resolveOs() {
        if (PlatformUtil.isWindows()) return "windows";
        if (PlatformUtil.isMac()) return "mac";
        return "linux";
    }
}
