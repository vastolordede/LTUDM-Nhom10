package vn.edu.p2p.peer.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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
            throw new IOException("Corrupt peer identity for this local account", e);
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
            try (InputStream in = Files.newInputStream(file)) { props.load(in); }
        }
        return props;
    }

    private void saveProperties(Properties props) throws IOException {
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
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }

        String peerId = properties.getProperty(PEER_ID);
        String deviceName = properties.getProperty(DEVICE_NAME, detectDeviceName());

        try {
            UUID.fromString(peerId);
        } catch (Exception e) {
            throw new IOException("Invalid local peer identity: " + file, e);
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
}
