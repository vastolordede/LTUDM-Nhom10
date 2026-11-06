package vn.edu.p2p.peer.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

public final class PeerIdentityStore {
    private static final String PEER_ID = "peer.id";
    private static final String DEVICE_NAME = "device.name";
    private static final String ACCOUNT_PREFIX = "account.peer.";
    private static final String SCHEMA_VERSION = "schema_version";
    private static final int CURRENT_SCHEMA_VERSION = 2;

    private final Path file;

    public PeerIdentityStore(Path file) {
        this.file = file;
    }

    public static PeerIdentityStore defaultStore() {
        Path file = Path.of(
                System.getProperty("user.home"),
                ".fileshare-p2p",
                "peer.properties"
        );
        return new PeerIdentityStore(file);
    }

    Path directory() {
        return file.toAbsolutePath().getParent();
    }

    public PeerIdentity loadOrCreate() throws IOException {
        if (Files.exists(file)) {
            return load();
        }

        PeerIdentity identity = new PeerIdentity(
                UUID.randomUUID().toString(),
                detectDeviceName()
        );
        save(identity);
        return identity;
    }

    /**
     * A single machine may be used by multiple accounts. Keep the legacy peer.id
     * intact, but remember the chosen Peer ID per normalized username.
     * The key is SHA-256(username), not the raw account name.
     */
    public synchronized PeerIdentity loadOrCreateForAccount(String username) throws IOException {
        String key = accountKey(username);
        PeerIdentity legacy = loadOrCreate();
        Properties props = properties();
        String selected = props.getProperty(key);
        if (selected == null) {
            // On first login try the existing Peer ID. If server reports ownership
            // mismatch, the caller will rotate THIS account without changing legacy.
            props.setProperty(key, legacy.peerId());
            saveProperties(props);
            return legacy;
        }
        try {
            UUID.fromString(selected);
        } catch (IllegalArgumentException e) {
            throw invalidData(e);
        }
        return new PeerIdentity(selected, legacy.deviceName());
    }

    /** Called ONLY after Tracker explicitly says this ID belongs to another account. */
    public synchronized PeerIdentity rotateForAccount(String username) throws IOException {
        String key = accountKey(username);
        PeerIdentity legacy = loadOrCreate();
        PeerIdentity fresh = new PeerIdentity(UUID.randomUUID().toString(), legacy.deviceName());
        Properties props = properties();
        props.setProperty(key, fresh.peerId());
        saveProperties(props);
        return fresh;
    }

    private static String accountKey(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username cannot be blank");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    username.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return ACCOUNT_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Properties properties() throws IOException {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                validateVersion(props);
            } catch (IOException | IllegalArgumentException e) {
                throw invalidData(e);
            }
        }
        return props;
    }

    private void saveProperties(Properties props) throws IOException {
        props.setProperty(SCHEMA_VERSION, Integer.toString(CURRENT_SCHEMA_VERSION));
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        // Write to a sibling temporary file before replacing the existing identity.
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "peer-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "FileShare-P2P peer and account identities");
            }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException noAtomicMove) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private PeerIdentity load() throws IOException {
        Properties properties = properties();
        String peerId = properties.getProperty(PEER_ID);
        String deviceName = properties.getProperty(DEVICE_NAME, detectDeviceName());

        try {
            UUID.fromString(peerId);
            validateAccountIds(properties);
        } catch (Exception e) {
            throw invalidData(e);
        }
        if (properties.getProperty(SCHEMA_VERSION) == null) {
            saveProperties(properties);
        }

        return new PeerIdentity(peerId, deviceName);
    }

    private void save(PeerIdentity identity) throws IOException {
        Properties properties = properties();
        properties.setProperty(PEER_ID, identity.peerId());
        properties.setProperty(DEVICE_NAME, identity.deviceName());
        saveProperties(properties);
    }

    private static String detectDeviceName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host == null || host.isBlank() ? "Unknown device" : host;
        } catch (Exception e) {
            return "Unknown device";
        }
    }

    private static void validateVersion(Properties properties) throws IOException {
        String version = properties.getProperty(SCHEMA_VERSION);
        if (version == null) return;
        try {
            int parsed = Integer.parseInt(version);
            if (parsed >= 1 && parsed <= CURRENT_SCHEMA_VERSION) return;
        } catch (NumberFormatException ignored) {
            // Invalid version is treated as an unsupported/corrupt store below.
        }
        throw new IOException("Unsupported identity schema");
    }

    private static void validateAccountIds(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(ACCOUNT_PREFIX)) UUID.fromString(properties.getProperty(key));
        }
    }

    private IOException invalidData(Exception cause) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException backupFailure) {
            throw new IOException("Dữ liệu Peer ID bị lỗi và không thể tạo bản sao lưu an toàn.",
                    backupFailure);
        }
        return new IOException("Dữ liệu Peer ID bị lỗi hoặc không tương thích. "
                + "Đã giữ nguyên tệp gốc và tạo bản sao lưu .bak.", cause);
    }
}
