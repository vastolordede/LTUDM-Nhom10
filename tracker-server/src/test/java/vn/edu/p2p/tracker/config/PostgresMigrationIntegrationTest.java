package vn.edu.p2p.tracker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class PostgresMigrationIntegrationTest {
    private static final String URL_ENV = "P2P_PG_TEST_URL";
    private static final String USER_ENV = "P2P_PG_TEST_USER";
    private static final String PASSWORD_ENV = "P2P_PG_TEST_PASSWORD";

    @Test
    @Timeout(120)
    void migrationsPreserveDataAndRejectIncompleteOrDriftedSchemas() throws Exception {
        String configuredUrl = System.getenv(URL_ENV);
        Assumptions.assumeTrue(configuredUrl != null && !configuredUrl.isBlank(),
                "SKIPPED: set P2P_PG_TEST_URL to run real PostgreSQL migration integration tests");
        String username = requiredEnvironment(USER_ENV);
        String password = requiredEnvironment(PASSWORD_ENV);
        String adminUrl = databaseUrl(configuredUrl, "postgres");
        Path repositoryRoot = findRepositoryRoot();

        try (Connection admin = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = admin.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT current_setting('server_version')")) {
                assertTrue(rs.next());
                System.out.println("PostgreSQL integration server: " + rs.getString(1));
            }
        }

        String emptyDatabase = createDatabase(adminUrl, username, password, "r13_empty");
        try {
            withDatabase(configuredUrl, emptyDatabase, username, password, connection -> {
                applyMigrations(connection, repositoryRoot);
                executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql"));
                assertSchemaVersion(connection);
                DatabaseConnectionFactory factory = new DatabaseConnectionFactory(new DatabaseConfig(
                        databaseUrl(configuredUrl, emptyDatabase), username, password));
                factory.verify();
                execute(connection, "UPDATE schema_version SET version = 8 WHERE singleton = TRUE");
                SQLException oldVersion = assertThrows(SQLException.class, factory::verify);
                assertTrue(oldVersion.getMessage().contains("phiên bản 9"), oldVersion.getMessage());
                execute(connection, "UPDATE schema_version SET version = 9 WHERE singleton = TRUE");

                execute(connection, "DROP INDEX uq_files_hash");
                SQLException missingIndex = assertThrows(SQLException.class,
                        () -> executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql")));
                assertTrue(missingIndex.getMessage().contains("missing indexes"),
                        missingIndex.getMessage());
                execute(connection, "CREATE UNIQUE INDEX uq_files_hash ON files(file_hash)");

                execute(connection, "ALTER TABLE peer_files DROP CONSTRAINT peer_files_pkey");
                SQLException missingConstraint = assertThrows(SQLException.class,
                        () -> executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql")));
                assertTrue(missingConstraint.getMessage().contains("missing constraints"),
                        missingConstraint.getMessage());
                execute(connection, """
                        ALTER TABLE peer_files
                        ADD CONSTRAINT peer_files_pkey PRIMARY KEY (peer_id, file_id)
                        """);
                executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql"));
                assertSchemaVersion(connection);

                SQLException rerunV2 = assertThrows(SQLException.class,
                        () -> executeSqlFile(connection, sqlFile(repositoryRoot, "migration_v2.sql")));
                assertTrue(rerunV2.getMessage().contains("publish_status"), rerunV2.getMessage());
            });
        } finally {
            dropDatabase(adminUrl, username, password, emptyDatabase);
        }

        String populatedDatabase = createDatabase(adminUrl, username, password, "r13_populated");
        try {
            withDatabase(configuredUrl, populatedDatabase, username, password, connection -> {
                executeSqlFile(connection, sqlFile(repositoryRoot, "schema.sql"));
                seedExistingRows(connection);
                for (int version = 2; version <= 9; version++) {
                    if (version == 5) {
                        execute(connection, "UPDATE files SET publish_status = 'PUBLISHING'");
                    }
                    executeSqlFile(connection, sqlFile(repositoryRoot, "migration_v" + version + ".sql"));
                }
                executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql"));
                assertSchemaVersion(connection);
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM users WHERE username = 'r13_user'"));
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM files WHERE file_hash = "
                        + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "
                        + "AND publish_status = 'READY'"));
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM file_aliases WHERE file_name = 'seed.bin'"));
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM peer_files"));
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM share_events WHERE event_type = 'SHARE_START'"));
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM transfer_sessions WHERE status = 'COMPLETED'"));
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM transfer_sources WHERE source_status = 'COMPLETED'"));
                executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql"));
                assertSchemaVersion(connection);
            });
        } finally {
            dropDatabase(adminUrl, username, password, populatedDatabase);
        }

        String incompleteDatabase = createDatabase(adminUrl, username, password, "r13_missing_migration");
        try {
            withDatabase(configuredUrl, incompleteDatabase, username, password, connection -> {
                executeSqlFile(connection, sqlFile(repositoryRoot, "schema.sql"));
                for (int version : new int[]{2, 3, 4}) {
                    executeSqlFile(connection, sqlFile(repositoryRoot, "migration_v" + version + ".sql"));
                }
                seedPublishingFile(connection);
                for (int version : new int[]{6, 7, 8, 9}) {
                    executeSqlFile(connection, sqlFile(repositoryRoot, "migration_v" + version + ".sql"));
                }
                SQLException missingRepair = assertThrows(SQLException.class,
                        () -> executeSqlFile(connection, sqlFile(repositoryRoot, "verify_schema.sql")));
                assertTrue(missingRepair.getMessage().contains("migration_v5"), missingRepair.getMessage());

                SQLException startupRejected = assertThrows(SQLException.class,
                        () -> new DatabaseConnectionFactory(new DatabaseConfig(
                                databaseUrl(configuredUrl, incompleteDatabase), username, password))
                                .verifyRequiredSchema());
                assertTrue(startupRejected.getMessage().contains("schema_version"),
                        startupRejected.getMessage());
            });
        } finally {
            dropDatabase(adminUrl, username, password, incompleteDatabase);
        }

        String wrongOrderDatabase = createDatabase(adminUrl, username, password, "r13_wrong_order");
        try {
            withDatabase(configuredUrl, wrongOrderDatabase, username, password, connection -> {
                executeSqlFile(connection, sqlFile(repositoryRoot, "schema.sql"));
                SQLException wrongOrder = assertThrows(SQLException.class,
                        () -> executeSqlFile(connection, sqlFile(repositoryRoot, "migration_v3.sql")));
                assertTrue(wrongOrder.getMessage().contains("publish_status"), wrongOrder.getMessage());
            });
        } finally {
            dropDatabase(adminUrl, username, password, wrongOrderDatabase);
        }
    }

    private static void applyMigrations(Connection connection, Path repositoryRoot) throws SQLException, IOException {
        executeSqlFile(connection, sqlFile(repositoryRoot, "schema.sql"));
        for (int version = 2; version <= 9; version++) {
            executeSqlFile(connection, sqlFile(repositoryRoot, "migration_v" + version + ".sql"));
        }
    }

    private static void seedExistingRows(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO users (username, password_hash) VALUES ('r13_user', 'not-a-real-password-hash')
                """);
        execute(connection, """
                INSERT INTO peers (peer_id, user_id, device_name)
                SELECT '00000000-0000-0000-0000-000000000101', user_id, 'r13-device'
                FROM users WHERE username = 'r13_user'
                """);
        execute(connection, """
                INSERT INTO files (file_name, file_size, file_hash, piece_size, piece_count)
                VALUES ('seed.bin', 4,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 4, 1)
                """);
        execute(connection, """
                INSERT INTO file_pieces (file_id, piece_index, piece_length, piece_hash)
                SELECT file_id, 0, 4,
                       'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
                FROM files WHERE file_name = 'seed.bin'
                """);
        execute(connection, """
                INSERT INTO peer_files (peer_id, file_id)
                SELECT '00000000-0000-0000-0000-000000000101', file_id
                FROM files WHERE file_name = 'seed.bin'
                """);
        execute(connection, """
                INSERT INTO share_events (peer_id, file_id, event_type)
                SELECT '00000000-0000-0000-0000-000000000101', file_id, 'SHARE_START'
                FROM files WHERE file_name = 'seed.bin'
                """);
        execute(connection, """
                INSERT INTO transfer_sessions
                    (transfer_id, file_id, destination_peer_id, status, bytes_received, completed_at)
                SELECT '00000000-0000-0000-0000-000000000201', file_id,
                       '00000000-0000-0000-0000-000000000101', 'COMPLETED', 4, CURRENT_TIMESTAMP
                FROM files WHERE file_name = 'seed.bin'
                """);
        execute(connection, """
                INSERT INTO transfer_sources
                    (transfer_id, source_peer_id, bytes_received, pieces_received, source_status)
                VALUES ('00000000-0000-0000-0000-000000000201',
                        '00000000-0000-0000-0000-000000000101', 4, 1, 'COMPLETED')
                """);
    }

    private static void seedPublishingFile(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO files (file_name, file_size, file_hash, piece_size, piece_count, publish_status)
                VALUES ('pending.bin', 4,
                        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 4, 1, 'PUBLISHING')
                """);
        execute(connection, """
                INSERT INTO file_pieces (file_id, piece_index, piece_length, piece_hash)
                SELECT file_id, 0, 4,
                       'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
                FROM files WHERE file_name = 'pending.bin'
                """);
    }

    private static void assertSchemaVersion(Connection connection) throws SQLException {
        assertEquals(9, count(connection,
                "SELECT version FROM schema_version WHERE singleton = TRUE"));
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "Expected a row for: " + sql);
            return rs.getInt(1);
        }
    }

    private static void executeSqlFile(Connection connection, Path path) throws SQLException, IOException {
        execute(connection, Files.readString(path));
    }

    private static Path sqlFile(Path repositoryRoot, String name) {
        return repositoryRoot.resolve("database").resolve(name);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String createDatabase(
            String adminUrl, String username, String password, String prefix) throws SQLException {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static void dropDatabase(
            String adminUrl, String username, String password, String name) throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    private static void withDatabase(
            String baseUrl, String database, String username, String password, SqlWork work) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                databaseUrl(baseUrl, database), username, password)) {
            work.run(connection);
        }
    }

    private static String databaseUrl(String url, String database) {
        int pathStart = url.lastIndexOf('/');
        if (pathStart < "jdbc:postgresql://".length()) {
            throw new IllegalArgumentException(URL_ENV + " must be a PostgreSQL JDBC URL with a database name");
        }
        int queryStart = url.indexOf('?', pathStart);
        return url.substring(0, pathStart + 1) + database
                + (queryStart < 0 ? "" : url.substring(queryStart));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when " + URL_ENV + " is set");
        }
        return value;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("database").resolve("schema.sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate database/schema.sql from the test working directory");
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }
}
