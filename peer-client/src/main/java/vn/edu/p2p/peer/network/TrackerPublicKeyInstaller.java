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
    public static final class ReplacementConfirmationRequiredException extends IOException {
        private ReplacementConfirmationRequiredException() {
            super("Khóa Tracker hiện tại khác. Hãy nhập lại fingerprint SHA-256 mới để xác nhận thay khóa.");
        }
    }

    private TrackerPublicKeyInstaller() { }

    public static Path defaultDestination() {
        return Path.of(System.getProperty("user.home"), ".fileshare-p2p", "tracker-public.key.b64");
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
                throw new IOException("Tệp khóa Tracker hiện tại không hợp lệ; từ chối ghi đè.", e);
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
            }
        }
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
        }
        String fingerprint = settings.getProperty(PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY);
        if (fingerprint == null || fingerprint.isBlank()) return null;
        try {
            return PeerAccessTickets.normalizeFingerprint(fingerprint);
        } catch (IllegalArgumentException e) {
            throw new IOException("Fingerprint khóa Tracker đã lưu không hợp lệ.", e);
        }
    }
}
