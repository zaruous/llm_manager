/*
 * 작성자 : kyj
 * 작성일 : 2026-06-06
 */
package org.kyj.llmmanager.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.kyj.llmmanager.model.AppSettings;
import org.kyj.llmmanager.model.LlmTool;
import org.kyj.llmmanager.model.SkillFile;
import org.kyj.llmmanager.model.SkillPack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 사용자가 로드한 LLM 스킬·룰 파일을 설정된 DB provider에 저장한다.
 * HikariCP 커넥션 풀을 소유하므로 사용 후 반드시 {@link #close()}를 호출해야 한다.
 */
public class LlmSkillLibraryRepository implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(LlmSkillLibraryRepository.class);

    private static final String DB_FILE_NAME = "skill-library.sqlite";
    private static final String TOOL_ID = "cursor-library";
    private static final String PACK_ID = "cursor-library-loaded";
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private final AppSettings settings;
    private final String provider;
    private final String jdbcUrl;
    private final String schema;
    private final HikariDataSource dataSource;

    /** ensureSchema()의 중복 실행을 막는 플래그. 최초 1회 성공 후 true로 설정된다. */
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public LlmSkillLibraryRepository(AppSettings settings) {
        this.settings = settings;
        this.provider = normalizeProvider(settings.getSkillLibraryDbProvider());
        this.jdbcUrl = resolveJdbcUrl(settings, provider);
        this.schema = normalizeSchema(settings.getSkillLibraryDbSchema(), provider);
        this.dataSource = createDataSource();
    }

    public String getProvider() {
        return provider;
    }

    public String getDatabaseLocation() {
        return jdbcUrl;
    }

    public String getSchema() {
        return schema;
    }

    /**
     * 파일 목록을 DB에 upsert한다. 실패 시 트랜잭션을 롤백하고 예외를 전파한다.
     *
     * @param sourceRoot    스캔한 소스 루트 디렉토리
     * @param relativePaths sourceRoot 기준 상대 경로 목록
     * @return 실제로 저장(insert/update)된 행 수
     */
    public int saveFiles(Path sourceRoot, List<String> relativePaths) throws IOException, SQLException {
        ensureSchema();

        int saved = 0;
        try (Connection con = connect()) {
            con.setAutoCommit(false);
            try {
                String upsertSql = buildUpsertSql();
                try (PreparedStatement ps = con.prepareStatement(upsertSql)) {
                    for (String relativePath : relativePaths) {
                        String normalized = normalize(relativePath);
                        String content = Files.readString(sourceRoot.resolve(relativePath), StandardCharsets.UTF_8);
                        String targetPath = toInstallTargetPath(normalized);
                        String updatedAt = Instant.now().toString();

                        ps.setString(1, sourceRoot.toAbsolutePath().normalize().toString());
                        ps.setString(2, normalized);
                        ps.setString(3, targetPath);
                        ps.setString(4, content);
                        ps.setString(5, updatedAt);
                        saved += ps.executeUpdate();
                    }
                }
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            }
        }
        return saved;
    }

    /**
     * DB에 저장된 스킬 파일을 LlmTool 목록으로 반환한다.
     * DB가 비어 있으면 빈 목록을, 오류 시에는 로그를 남기고 빈 목록을 반환한다.
     */
    public List<LlmTool> loadTools() {
        try {
            ensureSchema();
            List<SkillFile> files = new ArrayList<>();
            try (Connection con = connect();
                 PreparedStatement ps = con.prepareStatement("""
                         SELECT id, target_path
                         FROM skill_files
                         ORDER BY target_path
                         """);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SkillFile sf = new SkillFile();
                    sf.setLibraryFileId(rs.getLong("id"));
                    sf.setTargetPath(rs.getString("target_path"));
                    sf.setTemplate(false);
                    files.add(sf);
                }
            }

            if (files.isEmpty()) {
                return List.of();
            }

            SkillPack pack = new SkillPack();
            pack.setId(PACK_ID);
            pack.setName("로드된 파일");
            pack.setTags(List.of("loaded", provider));
            pack.setFiles(files);

            LlmTool tool = new LlmTool();
            tool.setId(TOOL_ID);
            tool.setName("로드된 Cursor 라이브러리");
            tool.setDescription("로드 탭에서 DB로 저장한 스킬·룰 파일");
            tool.setPacks(List.of(pack));
            return List.of(tool);
        } catch (Exception e) {
            log.warn("스킬 라이브러리 loadTools 실패 (provider={}): {}", provider, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * DB에서 파일 내용을 읽어 반환한다.
     *
     * @param id skill_files.id
     * @return 파일 텍스트 내용
     * @throws IOException DB 오류 또는 해당 id가 없을 때
     */
    public String readContent(long id) throws IOException {
        try {
            ensureSchema();
            try (Connection con = connect();
                 PreparedStatement ps = con.prepareStatement("""
                         SELECT content
                         FROM skill_files
                         WHERE id = ?
                         """)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("content");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("스킬 라이브러리 DB 읽기 실패: " + e.getMessage(), e);
        }
        throw new IOException("스킬 라이브러리 파일을 찾을 수 없습니다: " + id);
    }

    public void refresh() {
        // 현재 구현은 조회 시마다 DB를 직접 읽으므로 명시 캐시가 없다.
    }

    @Override
    public void close() {
        dataSource.close();
    }

    // =========================================================
    // 내부 구현
    // =========================================================

    /**
     * 테이블이 없으면 생성한다. schemaReady 플래그로 인스턴스 생존 기간 동안 한 번만 실행된다.
     * CREATE TABLE IF NOT EXISTS를 사용하므로 동시 호출 시에도 안전하다.
     */
    private synchronized void ensureSchema() throws SQLException, IOException {
        if (schemaReady.get()) {
            return;
        }
        prepareLocalSqliteDirectory();
        try (Connection con = connect();
             Statement st = con.createStatement()) {
            st.executeUpdate(createTableSql());
            try {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_skill_files_target_path ON skill_files(target_path)");
            } catch (SQLException ignored) {
                // 인덱스 생성 실패는 기능에 영향 없다.
            }
        }
        schemaReady.set(true);
    }

    /**
     * provider별 원자적 upsert SQL을 반환한다.
     * SQLite: INSERT OR REPLACE, PostgreSQL: ON CONFLICT DO UPDATE, 그 외: MERGE / INSERT OR REPLACE 사용.
     */
    private String buildUpsertSql() {
        return switch (provider) {
            case "postgresql" -> """
                    INSERT INTO skill_files
                        (source_root, relative_path, target_path, content, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (relative_path) DO UPDATE SET
                        source_root = EXCLUDED.source_root,
                        target_path = EXCLUDED.target_path,
                        content     = EXCLUDED.content,
                        updated_at  = EXCLUDED.updated_at
                    """;
            case "mssql" -> """
                    MERGE INTO skill_files AS t
                    USING (VALUES (?, ?, ?, ?, ?)) AS s(source_root, relative_path, target_path, content, updated_at)
                    ON t.relative_path = s.relative_path
                    WHEN MATCHED THEN
                        UPDATE SET source_root = s.source_root, target_path = s.target_path,
                                   content = s.content, updated_at = s.updated_at
                    WHEN NOT MATCHED THEN
                        INSERT (source_root, relative_path, target_path, content, updated_at)
                        VALUES (s.source_root, s.relative_path, s.target_path, s.content, s.updated_at);
                    """;
            // SQLite default: INSERT OR REPLACE (atomic; Oracle도 MERGE 없이 동일 패턴 지원)
            default -> """
                    INSERT OR REPLACE INTO skill_files
                        (source_root, relative_path, target_path, content, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """;
        };
    }

    private String createTableSql() {
        return switch (provider) {
            case "postgresql" -> """
                    CREATE TABLE IF NOT EXISTS skill_files (
                        id BIGSERIAL PRIMARY KEY,
                        source_root TEXT NOT NULL,
                        relative_path TEXT NOT NULL UNIQUE,
                        target_path TEXT NOT NULL,
                        content TEXT NOT NULL,
                        updated_at VARCHAR(64) NOT NULL
                    )
                    """;
            case "oracle" -> """
                    DECLARE
                        v_cnt NUMBER;
                    BEGIN
                        SELECT COUNT(*) INTO v_cnt FROM user_tables WHERE table_name = 'SKILL_FILES';
                        IF v_cnt = 0 THEN
                            EXECUTE IMMEDIATE '
                                CREATE TABLE skill_files (
                                    id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                                    source_root VARCHAR2(2000) NOT NULL,
                                    relative_path VARCHAR2(1000) NOT NULL UNIQUE,
                                    target_path VARCHAR2(1000) NOT NULL,
                                    content CLOB NOT NULL,
                                    updated_at VARCHAR2(64) NOT NULL
                                )';
                        END IF;
                    END;
                    """;
            case "mssql" -> """
                    IF NOT EXISTS (SELECT 1 FROM sys.tables WHERE name = 'skill_files')
                    CREATE TABLE skill_files (
                        id BIGINT IDENTITY(1,1) PRIMARY KEY,
                        source_root NVARCHAR(2000) NOT NULL,
                        relative_path NVARCHAR(1000) NOT NULL UNIQUE,
                        target_path NVARCHAR(1000) NOT NULL,
                        content NVARCHAR(MAX) NOT NULL,
                        updated_at NVARCHAR(64) NOT NULL
                    )
                    """;
            default -> """
                    CREATE TABLE IF NOT EXISTS skill_files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_root TEXT NOT NULL,
                        relative_path TEXT NOT NULL UNIQUE,
                        target_path TEXT NOT NULL,
                        content TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """;
        };
    }

    private Connection connect() throws SQLException {
        return dataSource.getConnection();
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("llm-skill-library-" + provider);
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName(resolveDriverClass());
        if (!schema.isBlank()) {
            config.setSchema(schema);
        }
        if (settings.getSkillLibraryDbUsername() != null &&
            !settings.getSkillLibraryDbUsername().isBlank()) {
            config.setUsername(settings.getSkillLibraryDbUsername());
            config.setPassword(settings.getSkillLibraryDbPassword() == null
                    ? "" : settings.getSkillLibraryDbPassword());
        }
        config.setMaximumPoolSize(Math.max(1, settings.getSkillLibraryDbMaximumPoolSize()));
        config.setMinimumIdle(Math.max(0,
                Math.min(settings.getSkillLibraryDbMinimumIdle(), settings.getSkillLibraryDbMaximumPoolSize())));
        config.setConnectionTimeout(Math.max(250, settings.getSkillLibraryDbConnectionTimeoutMs()));
        config.setIdleTimeout(Math.max(10000, settings.getSkillLibraryDbIdleTimeoutMs()));
        config.setMaxLifetime(Math.max(30000, settings.getSkillLibraryDbMaxLifetimeMs()));
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private String resolveDriverClass() {
        String driverClass = settings.getSkillLibraryDbDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = defaultDriverClass(provider);
        }
        return driverClass;
    }

    /** SQLite 파일 DB 사용 시 부모 디렉토리를 미리 생성한다. :memory: URL은 건너뛴다. */
    private void prepareLocalSqliteDirectory() throws IOException {
        if (!"sqlite".equals(provider) || !jdbcUrl.startsWith("jdbc:sqlite:")) {
            return;
        }
        String filePart = jdbcUrl.substring("jdbc:sqlite:".length());
        // 인메모리 URL은 파일시스템 접근이 불필요하다.
        if (filePart.startsWith(":") || filePart.startsWith("file:") && filePart.contains(":memory:")) {
            return;
        }
        Path path = Path.of(filePart).toAbsolutePath().normalize();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "sqlite";
        }
        String normalized = provider.trim().toLowerCase();
        if (normalized.equals("postgres")) {
            return "postgresql";
        }
        if (normalized.equals("sqlserver")) {
            return "mssql";
        }
        return switch (normalized) {
            case "sqlite", "postgresql", "oracle", "mssql" -> normalized;
            default -> throw new IllegalArgumentException("지원하지 않는 DB provider입니다: " + provider);
        };
    }

    private static String resolveJdbcUrl(AppSettings settings, String provider) {
        String configured = settings.getSkillLibraryDbUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if ("sqlite".equals(provider)) {
            return "jdbc:sqlite:" + resolveDefaultProjectRoot()
                    .resolve("lib").resolve("cursor").resolve(DB_FILE_NAME)
                    .toAbsolutePath().normalize();
        }
        return switch (provider) {
            case "postgresql" -> "jdbc:postgresql://localhost:5432/llm_manager";
            case "oracle" -> "jdbc:oracle:thin:@localhost:1521/FREEPDB1";
            case "mssql" -> "jdbc:sqlserver://localhost:1433;databaseName=llm_manager;encrypt=true;trustServerCertificate=true";
            default -> "jdbc:sqlite:" + resolveDefaultProjectRoot()
                    .resolve("lib").resolve("cursor").resolve(DB_FILE_NAME)
                    .toAbsolutePath().normalize();
        };
    }

    private static String defaultDriverClass(String provider) {
        return switch (provider) {
            case "postgresql" -> "org.postgresql.Driver";
            case "oracle" -> "oracle.jdbc.OracleDriver";
            case "mssql" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> "org.sqlite.JDBC";
        };
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String normalizeSchema(String schema, String provider) {
        if ("sqlite".equals(provider) || schema == null || schema.isBlank()) {
            return "";
        }
        String trimmed = schema.trim();
        if (!trimmed.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("유효하지 않은 DB schema입니다: " + schema);
        }
        return trimmed;
    }

    private static String toInstallTargetPath(String relativePath) {
        String path = normalize(relativePath);
        if (path.startsWith("cursor/")) {
            return "." + path;
        }
        return path;
    }

    private static Path resolveDefaultProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path cursor = current;
        while (cursor != null) {
            if (Files.exists(cursor.resolve("build.gradle")) ||
                Files.exists(cursor.resolve("settings.gradle")) ||
                Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return current;
    }
}
