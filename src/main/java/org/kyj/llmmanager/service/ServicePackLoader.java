/*
 * 작성자 : kyj
 * 작성일 : 2026-06-03
 */
package org.kyj.llmmanager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.kyj.llmmanager.model.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * YAML 서비스 팩 파일을 로드하고, 배포 환경에서 service-packs/ 디렉토리를 탐색한다.
 *
 * <p>탐색 우선순위:
 * <ol>
 *   <li>현재 작업 디렉토리의 {@code service-packs/} (개발 모드)</li>
 *   <li>JAR 위치에서 상위로 거슬러 찾은 {@code service-packs/} (배포 모드)</li>
 * </ol>
 */
public class ServicePackLoader {

    private static final Logger log = LoggerFactory.getLogger(ServicePackLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * 단일 YAML 서비스 팩 파일을 읽어 ServiceDefinition 으로 반환한다.
     *
     * @param file 읽을 YAML 파일
     * @return 파싱된 ServiceDefinition
     * @throws IOException 파일 읽기 또는 파싱 실패 시
     */
    public ServiceDefinition load(File file) throws IOException {
        return YAML.readValue(file, ServiceDefinition.class);
    }

    /**
     * service-packs/ 디렉토리의 모든 YAML 파일을 로드해 반환한다.
     * 디렉토리를 찾지 못하거나 읽기 실패 시 빈 목록을 반환한다.
     *
     * @return 로드된 ServiceDefinition 목록
     */
    public List<ServiceDefinition> loadAll() {
        Path dir = resolvePacksDir();
        if (dir == null) return Collections.emptyList();

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted()
                    .map(p -> {
                        try {
                            ServiceDefinition def = YAML.readValue(p.toFile(), ServiceDefinition.class);
                            // 서비스 팩에서 로드한 정의는 builtin으로 표시해
                            // BuiltinServiceSetupDialog 라우팅이 동작하도록 한다.
                            def.setBuiltin(true);
                            return def;
                        } catch (IOException e) {
                            log.warn("Failed to load service pack: {}", p, e);
                            return null;
                        }
                    })
                    .filter(d -> d != null)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list service-packs dir: {}", dir, e);
            return Collections.emptyList();
        }
    }

    /**
     * service-packs/ 디렉토리 경로를 반환한다.
     * 작업 디렉토리 기준으로 먼저 탐색하고, 없으면 JAR 위치에서 상위로 거슬러 탐색한다.
     *
     * @return 발견된 service-packs/ 경로, 없으면 null
     */
    public static Path resolvePacksDir() {
        // 1) 개발 모드: 작업 디렉토리 기준
        Path candidate = Path.of("service-packs");
        if (Files.isDirectory(candidate)) return candidate.toAbsolutePath();

        // 2) 배포 모드: JAR 위치에서 상위로 거슬러 탐색
        // JAR 위치: app/lib/  → 상위: app/  → 탐색: app/service-packs/
        try {
            Path jar = Path.of(
                    ServicePackLoader.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            // JAR이 디렉토리가 아닐 수도 있으므로 2단계까지만 탐색
            for (int i = 0; i < 2 && dir != null; i++) {
                Path found = dir.resolve("service-packs");
                if (Files.isDirectory(found)) return found;
                dir = dir.getParent();
            }
        } catch (URISyntaxException e) {
            log.warn("Cannot resolve JAR location for service-packs lookup", e);
        }

        return null;
    }
}
