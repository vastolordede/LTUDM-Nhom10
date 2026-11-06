package vn.edu.p2p.peer.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** R53: local per-user endpoint settings; passwords and private keys are NEVER saved. */
public final class UserTrackerSettingsStore {
    private static final String SCHEMA_VERSION = "schema_version";
    private static final int CURRENT_SCHEMA_VERSION = 2;

    public record Endpoint(String host, int port, boolean tls, String trustStorePath) {
        public Endpoint {
            if (host == null || host.isBlank() || host.length() > 253
                    || !host.matches("[a-zA-Z0-9._:-]+") || host.contains("..")) {
                throw new IllegalArgumentException("Invalid Tracker hostname/IP");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Tracker port must be 1..65535");
            }
            if (trustStorePath != null && trustStorePath.length() > 2048) {
                throw new IllegalArgumentException("Truststore path is too long");
            }
            trustStorePath = trustStorePath == null ? "" : trustStorePath.trim();
        }

        public TrackerConnectionConfig toConnectionConfig(PeerSettings defaults) {
            Path trust = trustStorePath.isBlank() ? defaults.trackerTrustStorePath()
                    : Path.of(trustStorePath).toAbsolutePath().normalize();
            return new TrackerConnectionConfig(host, port, tls, trust,
                    defaults.trackerTrustStorePassword(), defaults.trackerTrustStoreType());
        }
    }

    private final Path path;

    public UserTrackerSettingsStore(Path path) {
        this.path = path;
    }

    public static UserTrackerSettingsStore defaultStore() {
        return new UserTrackerSettingsStore(Path.of(System.getProperty("user.home"),
                ".fileshare-p2p", "tracker-settings.properties"));
    }

    public Endpoint load(PeerSettings defaults) throws IOException {
        if (path.toAbsolutePath().normalize().equals(defaultSettingsPath())) {
            TrackerPublicKeyInstaller.migratePinSettings(
                    TrackerPublicKeyInstaller.defaultDestination());
        }
        if (!Files.exists(path)) return new Endpoint(defaults.trackerHost(),
                defaults.trackerPort(), defaults.trackerTlsEnabled(),
                defaults.trackerTrustStorePath().toString());
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("Unsafe Tracker settings path: " + path);
        }

        Properties values = readProperties();
        try {
            int port = Integer.parseInt(values.getProperty("tracker.port", ""));
            String tls = values.getProperty("tracker.tls", "");
            if (!"true".equals(tls) && !"false".equals(tls)) {
                throw new IllegalArgumentException("Invalid saved TLS flag");
            }
            Endpoint endpoint = new Endpoint(values.getProperty("tracker.host", ""), port,
                    Boolean.parseBoolean(tls), values.getProperty("tracker.truststore.path", ""));
            if (values.getProperty(SCHEMA_VERSION) == null) save(endpoint);
            return endpoint;
        } catch (IllegalArgumentException e) {
            throw invalidData(e);
        }
    }

    public boolean hasSavedSettings() {
        return Files.isRegularFile(path) && !Files.isSymbolicLink(path);
    }

    public void save(Endpoint endpoint) throws IOException {
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent())) {
            throw new IOException("Refusing to save through symbolic link: " + path);
        }
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            Properties existing = readProperties();
            try {
                new Endpoint(existing.getProperty("tracker.host", ""),
                        Integer.parseInt(existing.getProperty("tracker.port", "")),
                        Boolean.parseBoolean(existing.getProperty("tracker.tls", "")),
                        existing.getProperty("tracker.truststore.path", ""));
                String tls = existing.getProperty("tracker.tls", "");
                if (!"true".equals(tls) && !"false".equals(tls)) {
                    throw new IllegalArgumentException("Invalid TLS flag");
                }
            } catch (IllegalArgumentException e) {
                throw invalidData(e);
            }
        }
        Properties values = new Properties();
        values.setProperty(SCHEMA_VERSION, Integer.toString(CURRENT_SCHEMA_VERSION));
        values.setProperty("tracker.host", endpoint.host());
        values.setProperty("tracker.port", Integer.toString(endpoint.port()));
        values.setProperty("tracker.tls", Boolean.toString(endpoint.tls()));
        values.setProperty("tracker.truststore.path", endpoint.trustStorePath());
        Path temp = Files.createTempFile(path.getParent(), ".tracker-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                values.store(output, "FileShare-P2P local endpoint settings - no credentials");
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Properties readProperties() throws IOException {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
        } catch (IOException | IllegalArgumentException e) {
            throw invalidData(e);
        }
        String version = values.getProperty(SCHEMA_VERSION);
        if (version != null) {
            int parsed;
            try {
                parsed = Integer.parseInt(version);
            } catch (NumberFormatException e) {
                throw invalidData(e);
            }
            if (parsed < 1 || parsed > CURRENT_SCHEMA_VERSION) {
                throw invalidData(new IOException("Unsupported settings schema"));
            }
        }
        return values;
    }

    private static Path defaultSettingsPath() {
        return Path.of(System.getProperty("user.home"), ".fileshare-p2p",
                "tracker-settings.properties").toAbsolutePath().normalize();
    }

    private IOException invalidData(Exception cause) throws IOException {
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        try {
            if (!Files.exists(backup)) Files.copy(path, backup);
        } catch (IOException backupFailure) {
            throw new IOException("Cấu hình Tracker bị lỗi và không thể tạo bản sao lưu an toàn.",
                    backupFailure);
        }
        return new IOException("Cấu hình Tracker bị lỗi hoặc không tương thích. "
                + "Đã giữ nguyên tệp gốc và tạo bản sao lưu .bak.", cause);
    }
}
