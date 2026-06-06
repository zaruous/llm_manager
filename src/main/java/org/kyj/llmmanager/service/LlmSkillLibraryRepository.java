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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 로드한 LLM 스킬·룰 파일을 설정된 DB provider에 저장한다.
 */
public class LlmSkillLibraryRepository {
    private static final String DB_FILE_NAME = "skill-library.sqlite";
    private static final String TABLE_NAME = "skill_files";
    private static final String TOOL_ID = "cursor-library";
    private static final String PACK_ID = "cursor-library-loaded";
    private static final String IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";

    private final AppSettings settings;
    private final String provider;
    private final String jdbcUrl;
    private final String schema;
    private final HikariDataSource dataSource;

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

    public int saveFiles(Path sourceRoot, List<String> relativePaths) throws IOException, SQLException {
        prepareLocalSqliteDirectory();
        ensureSchema();

        int saved = 0;
        try (Connection con = connect();
             PreparedStatement update = con.prepareStatement("""
                     UPDATE skill_files
                     SET source_root = ?, target_path = ?, content = ?, updated_at = ?
                     WHERE relative_path = ?
                     """);
             PreparedStatement insert = con.prepareStatement("""
                     INSERT INTO skill_files
                         (source_root, relative_path, target_path, content, updated_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            con.setAutoCommit(false);
            for (String relativePath : relativePaths) {
                String normalized = normalize(relativePath);
                String content = Files.readString(sourceRoot.resolve(relativePath), StandardCharsets.UTF_8);
                String targetPath = toInstallTargetPath(normalized);
                String updatedAt = Instant.now().toString();

                update.setString(1, sourceRoot.toAbsolutePath().normalize().toString());
                update.setString(2, targetPath);
                update.setString(3, content);
                update.setString(4, updatedAt);
                update.setString(5, normalized);

                if (update.executeUpdate() == 0) {
                    insert.setString(1, sourceRoot.toAbsolutePath().normalize().toString());
                    insert.setString(2, normalized);
                    insert.setString(3, targetPath);
                    insert.setString(4, content);
                    insert.setString(5, updatedAt);
                    insert.executeUpdate();
                }
                saved++;
            }
            con.commit();
        }
        return saved;
    }

    public List<LlmTool> loadTools() {
        try {
            prepareLocalSqliteDirectory();
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
            return List.of();
        }
    }

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

    private void ensureSchema() throws SQLException, IOException {
        prepareLocalSqliteDirectory();
        try (Connection con = connect()) {
            if (tableExists(con)) {
                return;
            }
            try (Statement st = con.createStatement()) {
                st.executeUpdate(createTableSql());
                try {
                    st.executeUpdate("CREATE INDEX idx_skill_files_target_path ON skill_files(target_path)");
                } catch (SQLException ignored) {
                    // 일부 DB는 인덱스명 충돌이나 권한 문제로 실패할 수 있다. 테이블만 있으면 기능은 동작한다.
                }
            }
        }
    }

    private boolean tableExists(Connection con) throws SQLException {
        DatabaseMetaData meta = con.getMetaData();
        String[] names = { TABLE_NAME, TABLE_NAME.toUpperCase(), TABLE_NAME.toLowerCase() };
        String schemaPattern = schema.isBlank() ? null : schema;
        for (String name : names) {
            try (ResultSet rs = meta.getTables(null, schemaPattern, name, new String[] { "TABLE" })) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private String createTableSql() {
        return switch (provider) {
            case "postgresql" -> """
                    CREATE TABLE skill_files (
                        id BIGSERIAL PRIMARY KEY,
                        source_root TEXT NOT NULL,
                        relative_path TEXT NOT NULL UNIQUE,
                        target_path TEXT NOT NULL,
                        content TEXT NOT NULL,
                        updated_at VARCHAR(64) NOT NULL
                    )
                    """;
            case "oracle" -> """
                    CREATE TABLE skill_files (
                        id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        source_root VARCHAR2(2000) NOT NULL,
                        relative_path VARCHAR2(1000) NOT NULL UNIQUE,
                        target_path VARCHAR2(1000) NOT NULL,
                        content CLOB NOT NULL,
                        updated_at VARCHAR2(64) NOT NULL
                    )
                    """;
            case "mssql" -> """
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
                    CREATE TABLE skill_files (
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

    public void close() {
        dataSource.close();
    }

    private String resolveDriverClass() {
        String driverClass = settings.getSkillLibraryDbDriverClass();
        if (driverClass == null || driverClass.isBlank()) {
            driverClass = defaultDriverClass(provider);
        }
        return driverClass;
    }

    private void prepareLocalSqliteDirectory() throws IOException {
        if (!"sqlite".equals(provider) || !jdbcUrl.startsWith("jdbc:sqlite:")) {
            return;
        }
        Path path = Path.of(jdbcUrl.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize();
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
