/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 앱 배포 시 포함된 lib/def/*.json 파일을 로드해 기본 제공 서비스 정의 목록을 반환한다.
 * 배포 환경(JAR)과 개발 환경(클래스 디렉토리) 모두 지원.
 */
public class BuiltinServiceLoader {
    private static final Logger log = LoggerFactory.getLogger(BuiltinServiceLoader.class);

    /** JSON 역직렬화용 ObjectMapper */
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 기본 제공 서비스 JSON 파일들이 있는 디렉토리 경로 */
    private final Path defDir;

    public BuiltinServiceLoader() {
        defDir = resolveDefDir();
    }

    /**
     * JAR 위치 또는 클래스 디렉토리 기준으로 lib/def 경로를 탐색한다.
     * 배포 환경: &lt;app-home&gt;/lib/def, 개발 환경: 프로젝트 루트/lib/def.
     *
     * @return 탐색된 lib/def 디렉토리 경로
     */
    private Path resolveDefDir() {
        try {
            Path codeLoc = Path.of(BuiltinServiceLoader.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());

            // 배포 환경: <app-home>/lib/xxx.jar → <app-home>/lib/def
            if (Files.isRegularFile(codeLoc)) {
                Path candidate = codeLoc.getParent().resolve("def");
                if (Files.isDirectory(candidate)) return candidate;
            }

            // 개발 환경: build/classes/java/main 등에서 위로 탐색
            Path dir = Files.isRegularFile(codeLoc) ? codeLoc.getParent() : codeLoc;
            for (int i = 0; i < 6; i++) {
                Path candidate = dir.resolve("lib").resolve("def");
                if (Files.isDirectory(candidate)) return candidate;
                Path parent = dir.getParent();
                if (parent == null) break;
                dir = parent;
            }
        } catch (Exception e) {
            log.warn("Failed to resolve built-in def dir: {}", e.getMessage());
        }
        return Path.of("lib", "def");
    }

    /**
     * defDir 아래의 모든 *.json을 로드해 ServiceDefinition 목록으로 반환한다.
     * 로드된 각 항목에는 builtin=true가 설정된다.
     *
     * @return 기본 제공 서비스 정의 목록
     */
    public List<ServiceDefinition> loadAll() {
        List<ServiceDefinition> result = new ArrayList<>();
        if (!Files.isDirectory(defDir)) {
            log.info("Built-in def dir not found: {}", defDir.toAbsolutePath());
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(defDir, "*.json")) {
            for (Path file : stream) {
                try {
                    ServiceDefinition def = mapper.readValue(file.toFile(), ServiceDefinition.class);
                    def.setBuiltin(true);
                    result.add(def);
                    log.info("Loaded built-in service: {}", def.getName());
                } catch (IOException e) {
                    log.error("Failed to load built-in {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan built-in def dir: {}", e.getMessage());
        }
        return result;
    }

    public Path getDefDir() {
        return defDir;
    }
}
