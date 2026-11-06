package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.security.PeerAccessTickets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Out-of-band fingerprint checked installation. Never blindly trust downloaded key material. */
public final class TrackerPublicKeyInstaller {
    private static final String SCHEMA_VERSION = "schema_version";
    private static final int CURRENT_SCHEMA_VERSION = 2;

    public static final class ReplacementConfirmationRequiredException extends IOException {
        private ReplacementConfirmationRequiredException() {
            super("Khóa Tracker hiện tại khác. Hãy nhập lại fingerprint SHA-256 mới để xác nhận thay khóa.");
        }
    }

    private TrackerPublicKeyInstaller() { }

    public static Path defaultDestination() {
        return Path.of(System.getProperty("user.home"), ".fileshare-p2p", "tracker-public.key.b64");
    }

    static void migratePinSettings(Path keyFile) throws IOException {
        Path trustSettings = keyFile.resolveSibling("tracker-public-key.properties");
        if (!Files.exists(trustSettings)) return;
        String pinned = readFingerprint(trustSettings);
        if (pinned == null) return;
        if (Files.exists(keyFile)) {
            if (Files.isSymbolicLink(keyFile) || !Files.isRegularFile(keyFile)) {
                throw new IOException("Tệp khóa công khai Tracker không an toàn.");
            }
            String encoded;
            java.security.PublicKey key;
            try {
                encoded = Files.readString(keyFile, StandardCharsets.US_ASCII).trim();
                key = PeerAccessTickets.decodePublicKey(encoded);
            } catch (Exception e) {
                throw invalidData(keyFile, "Tệp khóa công khai Tracker bị lỗi.", e);
            }
            if (!PeerAccessTickets.fingerprint(key).equals(pinned)) {
                throw new IOException("Khóa Tracker không khớp fingerprint đã tin cậy; "
                        + "không thay đổi cấu hình.");
            }
        }
        saveFingerprint(trustSettings, pinned);
    }

    public static String fingerprint(String base64) throws Exception {
        try {
            return PeerAccessTickets.formatFingerprint(
                    PeerAccessTickets.fingerprint(PeerAccessTickets.decodePublicKey(base64)));
        } catch (RuntimeException e) {
            throw new IOException("Tệp không chứa public key Ed25519 hợp lệ", e);
        }
    }

    /** Import ONLY if fingerprint was obtained from Tracker administrator through a trusted channel. */
    public static Path install(Path source, Path destination, String expectedFingerprint) throws Exception {
        return install(source, destination, expectedFingerprint, null);
    }

    public static Path install(Path source, Path destination, String expectedFingerprint,
                               String replacementConfirmation) throws Exception {
        if (source == null || !Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new IOException("Tệp khóa công khai phải là tệp thông thường.");
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new IOException("Cần fingerprint SHA-256 nhận từ quản trị viên qua kênh tin cậy.");
        }
        String base64;
        try {
            base64 = Files.readString(source, StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            throw new IOException("Không thể đọc tệp khóa công khai.", e);
        }
        if (base64.isBlank() || base64.length() > 1024 || base64.contains("PRIVATE KEY")) {
            throw new IOException("Tệp rỗng hoặc không phải public key Ed25519.");
        }
        java.security.PublicKey publicKey;
        try {
            publicKey = PeerAccessTickets.decodePublicKey(base64);
        } catch (IllegalArgumentException e) {
            throw new IOException("Tệp không chứa public key Ed25519 hợp lệ.", e);
        }
        String actualFingerprint = PeerAccessTickets.fingerprint(publicKey);
        String expected;
        try {
            expected = PeerAccessTickets.normalizeFingerprint(expectedFingerprint);
        } catch (IllegalArgumentException e) {
            throw new IOException("Fingerprint SHA-256 không đúng định dạng.", e);
        }
        if (!actualFingerprint.equals(expected)) {
            throw new IOException("Fingerprint không khớp. Không cài đặt khóa Tracker này.");
        }
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null || Files.isSymbolicLink(parent) || Files.isSymbolicLink(destination)) {
            throw new IOException("Đường dẫn lưu khóa công khai không an toàn.");
        }
        Files.createDirectories(parent);
        Path trustSettings = parent.resolve("tracker-public-key.properties");
        String previouslyTrusted = readFingerprint(trustSettings);
        if (previouslyTrusted != null && !previouslyTrusted.equals(actualFingerprint)
                && !actualFingerprint.equals(normalizeOrNull(replacementConfirmation))) {
            throw new ReplacementConfirmationRequiredException();
        }
        if (Files.exists(destination)) {
            if (!Files.isRegularFile(destination)) {
                throw new IOException("Tệp khóa Tracker hiện tại không hợp lệ; từ chối ghi đè.");
            }
            String current;
            java.security.PublicKey currentKey;
            try {
                current = Files.readString(destination, StandardCharsets.US_ASCII).trim();
                currentKey = PeerAccessTickets.decodePublicKey(current);
            } catch (Exception e) {
                throw invalidData(destination, "Tệp khóa công khai Tracker bị lỗi.", e);
            }
            if (PeerAccessTickets.fingerprint(currentKey).equals(actualFingerprint)) {
                saveFingerprint(trustSettings, actualFingerprint);
                return destination;
            }
            if (!actualFingerprint.equals(normalizeOrNull(replacementConfirmation))) {
                throw new ReplacementConfirmationRequiredException();
            }
        }
        Path temp = Files.createTempFile(parent, ".tracker-public-", ".tmp");
        try {
            Files.writeString(temp, base64 + System.lineSeparator(), StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        saveFingerprint(trustSettings, actualFingerprint);
        return destination;
    }

    private static String normalizeOrNull(String value) {
        try {
            return PeerAccessTickets.normalizeFingerprint(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void saveFingerprint(Path settingsFile, String fingerprint) throws IOException {
        if (Files.isSymbolicLink(settingsFile)
                || (Files.exists(settingsFile) && !Files.isRegularFile(settingsFile))) {
            throw new IOException("Cấu hình fingerprint khóa Tracker không an toàn.");
        }
        Properties settings = new Properties();
        if (Files.exists(settingsFile)) {
            try (InputStream input = Files.newInputStream(settingsFile)) {
                settings.load(input);
                validateVersion(settings);
                String existing = settings.getProperty(
                        PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY);
                if (existing != null && !existing.isBlank()) {
                    PeerAccessTickets.normalizeFingerprint(existing);
                }
            } catch (IOException | IllegalArgumentException e) {
                throw invalidData(settingsFile, "Cấu hình fingerprint Tracker bị lỗi.", e);
            }
        }
        settings.setProperty(SCHEMA_VERSION, Integer.toString(CURRENT_SCHEMA_VERSION));
        settings.setProperty(PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY, fingerprint);
        Path temp = Files.createTempFile(settingsFile.getParent(), ".tracker-trust-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) {
                settings.store(output, "Trusted Tracker public-key fingerprint");
            }
            try {
                Files.move(temp, settingsFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String readFingerprint(Path settingsFile) throws IOException {
        if (Files.isSymbolicLink(settingsFile)
                || (Files.exists(settingsFile) && !Files.isRegularFile(settingsFile))) {
            throw new IOException("Cấu hình fingerprint khóa Tracker không an toàn.");
        }
        if (!Files.exists(settingsFile)) return null;
        Properties settings = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            settings.load(input);
            validateVersion(settings);
        } catch (IOException | IllegalArgumentException e) {
            throw invalidData(settingsFile, "Cấu hình fingerprint Tracker bị lỗi.", e);
        }
        String fingerprint = settings.getProperty(PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY);
        if (fingerprint == null || fingerprint.isBlank()) return null;
        try {
            return PeerAccessTickets.normalizeFingerprint(fingerprint);
        } catch (IllegalArgumentException e) {
            throw invalidData(settingsFile, "Cấu hình fingerprint Tracker bị lỗi.", e);
        }
    }

    private static void validateVersion(Properties settings) throws IOException {
        String version = settings.getProperty(SCHEMA_VERSION);
        if (version == null) return;
        try {
            int parsed = Integer.parseInt(version);
            if (parsed >= 1 && parsed <= CURRENT_SCHEMA_VERSION) return;
        } catch (NumberFormatException ignored) {
            // Invalid version is treated as an unsupported/corrupt store below.
        }
        throw new IOException("Unsupported Tracker fingerprint schema");
    }

    private static IOException invalidData(Path file, String message, Exception cause)
            throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        try {
            if (!Files.exists(backup)) Files.copy(file, backup);
        } catch (IOException backupFailure) {
            throw new IOException(message + " Không thể tạo bản sao lưu an toàn.", backupFailure);
        }
        return new IOException(message + " Đã giữ nguyên tệp gốc và tạo bản sao lưu .bak.", cause);
    }
}
