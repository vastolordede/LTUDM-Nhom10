package vn.edu.p2p.tracker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.config.EnvConfig;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.tracker.TrackerApplication;

class PostgresRecoveryIntegrationTest {
    private static final String URL_ENV = "P2P_PG_TEST_URL";
    private static final String USER_ENV = "P2P_PG_TEST_USER";
    private static final String PASSWORD_ENV = "P2P_PG_TEST_PASSWORD";
    private static final String CONTAINER_ENV = "P2P_PG_TEST_CONTAINER";

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void backupRestoreRollbackInvalidInputsAndTrackerRestart() throws Exception {
        String baseUrl = System.getenv(URL_ENV);
        Assumptions.assumeTrue(baseUrl != null && !baseUrl.isBlank(),
                "SKIPPED: set P2P_PG_TEST_URL to run real PostgreSQL recovery integration tests");
        String username = requiredEnvironment(USER_ENV);
        String password = requiredEnvironment(PASSWORD_ENV);
        String container = requiredEnvironment(CONTAINER_ENV);
        URI endpoint = URI.create(baseUrl.substring("jdbc:".length()));
        String host = endpoint.getHost();
        int port = endpoint.getPort();
        assertNotNull(host);
        assertTrue(port > 0 && port <= 65535);
        String adminUrl = databaseUrl(baseUrl, "postgres");
        Path root = findRepositoryRoot();

        String currentDatabase = createDatabase(adminUrl, username, password, "r55_current");
        String emptyRestoreDatabase = createDatabase(adminUrl, username, password, "r55_restore");
        String badPasswordDatabase = createDatabase(adminUrl, username, password, "r55_bad_password");
        String corruptDatabase = createDatabase(adminUrl, username, password, "r55_corrupt");
        String missingFileDatabase = createDatabase(adminUrl, username, password, "r55_missing_file");
        String emptyFileDatabase = createDatabase(adminUrl, username, password, "r55_empty_file");
        String nonEmptyDatabase = createDatabase(adminUrl, username, password, "r55_nonempty");
        String legacyDatabase = createDatabase(adminUrl, username, password, "r55_legacy_v8");
        String rollbackDatabase = createDatabase(adminUrl, username, password, "r55_rollback");

        Path currentBackup = tempDir.resolve("schema-v9.dump");
        Path legacyBackup = tempDir.resolve("pre-v9.dump");
        String currentMarker = "r55-current-" + UUID.randomUUID();
        String legacyMarker = "r55-legacy-" + UUID.randomUUID();
        boolean containerStopped = false;
        try {
            seedDatabase(adminUrl, currentDatabase, username, password, root, 9, currentMarker);
            assertSchemaVerified(currentDatabase, baseUrl, username, password);
            assertSuccess(runUtility(root, "Backup", currentDatabase, currentBackup,
                    password, false), "v9 backup");
            assertTrue(Files.size(currentBackup) > 0);

            assertSuccess(runUtility(root, "Restore", emptyRestoreDatabase, currentBackup,
                    password, false), "restore to empty database");
            assertCurrentSnapshot(emptyRestoreDatabase, baseUrl, username, password, currentMarker);
            assertSchemaVerified(emptyRestoreDatabase, baseUrl, username, password);

            try (Connection connection = connect(baseUrl, nonEmptyDatabase, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE recovery_sentinel (value TEXT NOT NULL)");
                statement.execute("INSERT INTO recovery_sentinel VALUES ('must-survive')");
            }
            ProcessResult refusedNonEmpty = runUtility(root, "Restore", nonEmptyDatabase,
                    currentBackup, password, false);
            assertFailure(refusedNonEmpty, "not empty");
            try (Connection connection = connect(baseUrl, nonEmptyDatabase, username, password)) {
                assertEquals(1, count(connection,
                        "SELECT COUNT(*) FROM recovery_sentinel WHERE value = 'must-survive'"));
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM pg_tables "
                        + "WHERE schemaname = 'public' AND tablename = 'users'"));
            }

            setContainerPassword(container, "deliberately-wrong-recovery-test");
            try {
                assertThrows(SQLException.class,
                        () -> DriverManager.getConnection(baseUrl, username, password),
                        "Changing the disposable role password should invalidate the old credential");
                ProcessResult wrongPassword = runUtility(root, "Restore", badPasswordDatabase,
                        currentBackup, password, false);
                assertFailure(wrongPassword, "password authentication failed");
            } finally {
                setContainerPassword(container, password);
            }
            assertDatabaseEmpty(baseUrl, badPasswordDatabase, username, password);

            ProcessResult missingFile = runUtility(root, "Restore", missingFileDatabase,
                    tempDir.resolve("not-created.dump"), password, false);
            assertFailure(missingFile, "does not exist");
            assertDatabaseEmpty(baseUrl, missingFileDatabase, username, password);

            Path emptyFile = tempDir.resolve("empty.dump");
            Files.createFile(emptyFile);
            ProcessResult emptyArchive = runUtility(root, "Restore", emptyFileDatabase,
                    emptyFile, password, false);
            assertFailure(emptyArchive, "empty");
            assertDatabaseEmpty(baseUrl, emptyFileDatabase, username, password);

            Path corruptFile = tempDir.resolve("corrupt.dump");
            Files.write(corruptFile, new byte[]{'n', 'o', 't', '-', 'a', '-', 'd', 'u', 'm', 'p'});
            ProcessResult corruptArchive = runUtility(root, "Restore", corruptDatabase,
                    corruptFile, password, false);
            assertFailure(corruptArchive, "valid archive");
            assertDatabaseEmpty(baseUrl, corruptDatabase, username, password);

            seedDatabase(adminUrl, legacyDatabase, username, password, root, 8, legacyMarker);
            assertSuccess(runUtility(root, "Backup", legacyDatabase, legacyBackup,
                    password, false), "pre-v9 backup");
            assertSuccess(runUtility(root, "Restore", rollbackDatabase, legacyBackup,
                    password, false), "rollback snapshot restore");
            assertLegacySnapshot(rollbackDatabase, baseUrl, username, password, legacyMarker);
            assertCurrentSnapshot(currentDatabase, baseUrl, username, password, currentMarker);
            assertSchemaVerified(currentDatabase, baseUrl, username, password);

            DatabaseConnectionFactory factory = new DatabaseConnectionFactory(new DatabaseConfig(
                    databaseUrl(baseUrl, currentDatabase), username, password));
            factory.verify();
            stopContainer(container);
            containerStopped = true;
            assertThrows(SQLException.class, factory::verify,
                    "A fresh JDBC connection should fail while PostgreSQL is stopped");
            startContainer(container);
            containerStopped = false;
            awaitDatabase(factory, Duration.ofSeconds(45));
            factory.verify();
            try (Connection connection = factory.open()) {
                assertEquals(9, readSchemaVersion(connection));
            }

            TrackerProcess initialTracker = startTracker(root, currentDatabase, host, port,
                    username, password, tempDir);
            initialTracker.stop();
            stopContainer(container);
            containerStopped = true;
            TrackerProcess failedStartup = startTrackerExpectingFailure(root, currentDatabase,
                    host, port, username, password, tempDir);
            assertTrue(failedStartup.output().toLowerCase().contains("connect"),
                    failedStartup.output());
            startContainer(container);
            containerStopped = false;
            awaitDatabase(factory, Duration.ofSeconds(45));
            TrackerProcess recoveredTracker = startTracker(root, currentDatabase, host, port,
                    username, password, tempDir);
            recoveredTracker.stop();
            factory.verify();
        } finally {
            if (containerStopped) {
                startContainer(container);
            }
            for (String database : List.of(
                    currentDatabase, emptyRestoreDatabase, badPasswordDatabase, corruptDatabase,
                    missingFileDatabase, emptyFileDatabase, nonEmptyDatabase, legacyDatabase,
                    rollbackDatabase)) {
                dropDatabase(adminUrl, username, password, database);
            }
        }
    }

    private void seedDatabase(String adminUrl, String database, String username, String password,
                              Path root, int schemaVersion, String marker) throws Exception {
        try (Connection connection = connect(databaseUrl(adminUrl, database), database,
                username, password)) {
            executeSqlFile(connection, root.resolve("database/schema.sql"));
            int lastMigration = schemaVersion == 9 ? 9 : schemaVersion;
            for (int version = 2; version <= lastMigration; version++) {
                executeSqlFile(connection, root.resolve("database/migration_v" + version + ".sql"));
            }
            if (schemaVersion == 8) {
                execute(connection, "ALTER TABLE peers DROP COLUMN IF EXISTS public_key");
            }
            seedRows(connection, marker);
            if (schemaVersion == 9) {
                executeSqlFile(connection, root.resolve("database/verify_schema.sql"));
            }
        }
    }

    private static void seedRows(Connection connection, String marker) throws SQLException {
        UUID peerId = UUID.nameUUIDFromBytes(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long fileId;
        try (var user = connection.prepareStatement(
                "INSERT INTO users (username, password_hash) VALUES (?, 'test-only-hash') RETURNING user_id")) {
            user.setString(1, marker);
            try (ResultSet rs = user.executeQuery()) {
                assertTrue(rs.next());
                long userId = rs.getLong(1);
                try (var peer = connection.prepareStatement(
                        "INSERT INTO peers (peer_id, user_id, device_name) VALUES (?, ?, ?)")) {
                    peer.setObject(1, peerId);
                    peer.setLong(2, userId);
                    peer.setString(3, marker + "-device");
                    peer.executeUpdate();
                }
            }
        }
        try (var file = connection.prepareStatement("""
                INSERT INTO files
                    (file_name, file_size, file_hash, piece_size, piece_count, publish_status)
                VALUES (?, 4, ?, 4, 1, 'READY') RETURNING file_id
                """)) {
            file.setString(1, marker + ".bin");
            file.setString(2, String.format("%064x", marker.hashCode() & 0xffffffffL));
            try (ResultSet rs = file.executeQuery()) {
                assertTrue(rs.next());
                fileId = rs.getLong(1);
            }
        }
        try (var piece = connection.prepareStatement("""
                    INSERT INTO file_pieces (file_id, piece_index, piece_length, piece_hash)
                    VALUES (?, 0, 4, ?)
                    """);
             var peerFile = connection.prepareStatement(
                     "INSERT INTO peer_files (peer_id, file_id) VALUES (?, ?)");
             var event = connection.prepareStatement("""
                     INSERT INTO share_events (peer_id, file_id, event_type)
                     VALUES (?, ?, 'SHARE_START')
                     """)) {
            piece.setLong(1, fileId);
            piece.setString(2, String.format("%064x", (marker + "-piece").hashCode() & 0xffffffffL));
            piece.executeUpdate();
            peerFile.setObject(1, peerId);
            peerFile.setLong(2, fileId);
            peerFile.executeUpdate();
            event.setObject(1, peerId);
            event.setLong(2, fileId);
            event.executeUpdate();
        }
    }

    private void assertCurrentSnapshot(String database, String baseUrl, String username,
                                       String password, String marker) throws Exception {
        try (Connection connection = connect(baseUrl, database, username, password)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM users WHERE username = '" + marker + "'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM peers WHERE device_name = '"
                    + marker + "-device'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM files WHERE file_name = '"
                    + marker + ".bin' AND publish_status = 'READY'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM peer_files"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM share_events WHERE event_type = 'SHARE_START'"));
        }
    }

    private void assertLegacySnapshot(String database, String baseUrl, String username,
                                      String password, String marker) throws Exception {
        try (Connection connection = connect(baseUrl, database, username, password)) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM users WHERE username = '" + marker + "'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM files WHERE file_name = '"
                    + marker + ".bin'"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name = 'schema_version'"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'peers' AND column_name = 'public_key'"));
        }
    }

    private void assertSchemaVerified(String database, String baseUrl, String username,
                                      String password) throws Exception {
        DatabaseConnectionFactory factory = new DatabaseConnectionFactory(new DatabaseConfig(
                databaseUrl(baseUrl, database), username, password));
        factory.verify();
        try (Connection connection = factory.open()) {
            assertEquals(9, readSchemaVersion(connection));
        }
    }

    private void assertDatabaseEmpty(String baseUrl, String database,
                                     String username, String password) throws Exception {
        try (Connection connection = connect(baseUrl, database, username, password)) {
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','S','f')"));
        }
    }

    private ProcessResult runUtility(Path root, String operation, String database, Path backup,
                                     String password, boolean allowNonEmpty) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", root.resolve("tools/postgres_backup_restore.ps1").toString(),
                "-Operation", operation));
        if (backup != null) {
            command.add("-BackupPath");
            command.add(backup.toString());
        }
        if (allowNonEmpty) command.add("-AllowNonEmpty");
        command.add("-DockerContainer");
        command.add(requiredEnvironment(CONTAINER_ENV));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        setPostgresEnvironment(builder.environment(), database, password);
        Path output = tempDir.resolve("utility-" + UUID.randomUUID() + ".log");
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Backup/restore utility timed out: " + operation);
        }
        String text = Files.readString(output);
        return new ProcessResult(process.exitValue(), text);
    }

    private void setPostgresEnvironment(java.util.Map<String, String> environment,
                                        String database, String password) throws Exception {
        environment.put("PGHOST", requiredEnvironment("P2P_PG_TEST_DOCKER_HOST"));
        environment.put("PGPORT", "5432");
        environment.put("PGDATABASE", database);
        environment.put("PGUSER", requiredEnvironment(USER_ENV));
        environment.put("PGPASSWORD", password);
        environment.put("PGCONNECT_TIMEOUT", "5");
    }

    private void assertSuccess(ProcessResult result, String operation) {
        assertEquals(0, result.exitCode(), operation + " failed: " + result.output());
    }

    private void assertFailure(ProcessResult result, String message) {
        assertTrue(result.exitCode() != 0, "Expected failure, output: " + result.output());
        String normalizedOutput = result.output().toLowerCase().replaceAll("\\s+", " ");
        assertTrue(normalizedOutput.contains(message.toLowerCase()),
                "Expected message '" + message + "', output: " + result.output());
    }

    private TrackerProcess startTracker(Path root, String database, String host, int dbPort,
                                        String username, String password, Path directory) throws Exception {
        TrackerProcess process = createTrackerProcess(root, database, host, dbPort,
                username, password, directory);
        process.start();
        String output = process.awaitReady(Duration.ofSeconds(20));
        assertTrue(output.contains("Tracker ready"), output);
        return process;
    }

    private TrackerProcess startTrackerExpectingFailure(Path root, String database,
            String host, int dbPort, String username, String password, Path directory) throws Exception {
        TrackerProcess process = createTrackerProcess(root, database, host, dbPort,
                username, password, directory);
        process.start();
        String output = process.awaitExit(Duration.ofSeconds(15));
        assertNotNull(output);
        assertTrue(process.exitCode() != 0, output);
        return process.withOutput(output);
    }

    private TrackerProcess createTrackerProcess(Path root, String database, String host, int dbPort,
            String username, String password, Path directory) throws Exception {
        Path keyStore = directory.resolve("tracker-recovery-test-" + UUID.randomUUID() + ".p12");
        String keyPassword = UUID.randomUUID().toString();
        createTlsKeyStore(keyStore, keyPassword);
        KeyPair signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        List<String> locations = List.of(
                codeLocation(TrackerApplication.class),
                codeLocation(EnvConfig.class),
                codeLocation(com.fasterxml.jackson.databind.ObjectMapper.class),
                codeLocation(com.fasterxml.jackson.core.JsonProcessingException.class),
                codeLocation(com.fasterxml.jackson.annotation.JsonProperty.class),
                codeLocation(org.postgresql.Driver.class),
                codeLocation(org.mindrot.jbcrypt.BCrypt.class),
                codeLocation(PeerAccessTickets.class));
        String classpath = String.join(File.pathSeparator, locations.stream().distinct().toList());
        Path log = directory.resolve("tracker-" + UUID.randomUUID() + ".log");
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classpath, TrackerApplication.class.getName());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).directory(root.toFile());
        java.util.Map<String, String> environment = builder.environment();
        environment.put("P2P_PROFILE", "development");
        environment.put("DB_HOST", host);
        environment.put("DB_PORT", Integer.toString(dbPort));
        environment.put("DB_NAME", database);
        environment.put("DB_USER", username);
        environment.put("DB_PASSWORD", password);
        environment.put("TRACKER_PORT", Integer.toString(unusedPort()));
        environment.put("RENDEZVOUS_PORT", Integer.toString(unusedPort()));
        environment.put("TRACKER_TLS_ENABLED", "true");
        environment.put("TRACKER_TLS_KEYSTORE", keyStore.toString());
        environment.put("TRACKER_TLS_KEYSTORE_PASSWORD", keyPassword);
        environment.put("TRACKER_TLS_KEYSTORE_TYPE", "PKCS12");
        environment.put("TRACKER_LOG_LEVEL", "INFO");
        environment.put("P2P_TICKET_SIGNING_KEY_B64",
                Base64.getEncoder().encodeToString(signingKey.getPrivate().getEncoded()));
        environment.put("P2P_TICKET_PUBLIC_KEY_B64",
                Base64.getEncoder().encodeToString(signingKey.getPublic().getEncoded()));
        environment.put("RELAY_ENABLED", "false");
        return new TrackerProcess(builder, log);
    }

    private static void createTlsKeyStore(Path keyStore, String password) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "tracker-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IOException("Could not create temporary TLS material for Tracker startup test.");
        }
    }

    private static String codeLocation(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static void stopContainer(String container) throws Exception {
        runDocker(Duration.ofSeconds(20), "stop", "--time", "1", container);
    }

    private static void startContainer(String container) throws Exception {
        runDocker(Duration.ofSeconds(20), "start", container);
    }

    private static void setContainerPassword(String container, String newPassword) throws Exception {
        Process process = new ProcessBuilder("docker", "exec", "-i", container, "psql",
                "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        String escapedPassword = newPassword.replace("'", "''");
        try (var input = process.getOutputStream()) {
            input.write(("ALTER ROLE postgres PASSWORD '" + escapedPassword + "';\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out changing the disposable PostgreSQL test credential.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Could not change the disposable PostgreSQL test credential.");
        }
    }

    private static void runDocker(Duration timeout, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Docker command timed out: " + arguments[0]);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Docker command failed: " + arguments[0]);
        }
    }

    private static void awaitDatabase(DatabaseConnectionFactory factory, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        SQLException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                factory.verify();
                return;
            } catch (SQLException failure) {
                lastFailure = failure;
                Thread.sleep(250);
            }
        }
        throw new AssertionError("PostgreSQL did not recover before timeout.", lastFailure);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when " + URL_ENV + " is set");
        }
        return value;
    }

    private static String createDatabase(String adminUrl, String username,
                                         String password, String prefix) throws SQLException {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static void dropDatabase(String adminUrl, String username,
                                     String password, String name) throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    private static Connection connect(String baseUrl, String database,
                                      String username, String password) throws SQLException {
        return DriverManager.getConnection(databaseUrl(baseUrl, database), username, password);
    }

    private static String databaseUrl(String url, String database) {
        int pathStart = url.lastIndexOf('/');
        int queryStart = url.indexOf('?', pathStart);
        return url.substring(0, pathStart + 1) + database
                + (queryStart < 0 ? "" : url.substring(queryStart));
    }

    private static void executeSqlFile(Connection connection, Path file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(file));
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static int readSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM schema_version WHERE singleton = TRUE")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("database").resolve("schema.sql"))
                    && Files.isRegularFile(current.resolve("tools/postgres_backup_restore.ps1"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root.");
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    private static final class TrackerProcess {
        private final ProcessBuilder builder;
        private final Path outputFile;
        private Process process;
        private String capturedOutput = "";

        private TrackerProcess(ProcessBuilder builder, Path outputFile) {
            this.builder = builder;
            this.outputFile = outputFile;
        }

        private void start() throws IOException {
            process = builder.start();
        }

        private String awaitReady(Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                capturedOutput = Files.exists(outputFile) ? Files.readString(outputFile) : "";
                if (capturedOutput.contains("Tracker ready")) return capturedOutput;
                if (!process.isAlive()) {
                    throw new AssertionError("Tracker exited before ready: " + capturedOutput);
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Tracker did not become ready: " + capturedOutput);
        }

        private String awaitExit(Duration timeout) throws Exception {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            capturedOutput = Files.exists(outputFile) ? Files.readString(outputFile) : "";
            return capturedOutput;
        }

        private int exitCode() {
            return process.exitValue();
        }

        private String output() {
            return capturedOutput;
        }

        private TrackerProcess withOutput(String output) {
            capturedOutput = output;
            return this;
        }

        private void stop() throws Exception {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(2, TimeUnit.SECONDS),
                            "Tracker subprocess did not terminate");
                }
            }
        }
    }
}
