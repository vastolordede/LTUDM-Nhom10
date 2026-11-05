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
        if (!Files.exists(path)) return new Endpoint(defaults.trackerHost(),
                defaults.trackerPort(), defaults.trackerTlsEnabled(),
                defaults.trackerTrustStorePath().toString());
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IOException("Unsafe Tracker settings path: " + path);
        }

        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(path)) { values.load(input); }
        try {
            int port = Integer.parseInt(values.getProperty("tracker.port", ""));
            String tls = values.getProperty("tracker.tls", "");
            if (!"true".equals(tls) && !"false".equals(tls)) {
                throw new IllegalArgumentException("Invalid saved TLS flag");
            }
            return new Endpoint(values.getProperty("tracker.host", ""), port,
                    Boolean.parseBoolean(tls), values.getProperty("tracker.truststore.path", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid saved Tracker configuration: " + path, e);
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
        Properties values = new Properties();
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
}
