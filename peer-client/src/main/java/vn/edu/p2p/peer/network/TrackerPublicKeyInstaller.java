package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.security.PeerAccessTickets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;

/** Out-of-band fingerprint checked installation. Never blindly trust downloaded key material. */
public final class TrackerPublicKeyInstaller {
    private TrackerPublicKeyInstaller() { }

    public static Path defaultDestination() {
        return Path.of(System.getProperty("user.home"), ".fileshare-p2p", "tracker-public.key.b64");
    }

    public static String fingerprint(String base64) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(base64.strip());
        PeerAccessTickets.decodePublicKey(base64.strip());
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(encoded);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
    }

    /** Import ONLY if fingerprint was obtained from Tracker administrator through a trusted channel. */
    public static Path install(Path source, Path destination, String expectedFingerprint) throws Exception {
        if (source == null || !Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new IOException("Public key source must be a normal file");
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            throw new IOException("Trusted public-key fingerprint is required");
        }
        String base64 = Files.readString(source, StandardCharsets.US_ASCII).trim();
        if (!fingerprint(base64).equals(expectedFingerprint.trim())) {
            throw new IOException("Public key fingerprint MISMATCH. Do not install this key");
        }
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null || Files.isSymbolicLink(parent) || Files.isSymbolicLink(destination)) {
            throw new IOException("Unsafe public-key destination");
        }
        Files.createDirectories(parent);
        if (Files.exists(destination)) {
            String current = Files.readString(destination, StandardCharsets.US_ASCII).trim();
            if (current.equals(base64)) return destination;
            throw new IOException("A different Tracker public key is already installed. "
                    + "Rotation requires administrator approval; refusing to overwrite");
        }
        Path temp = Files.createTempFile(parent, ".tracker-public-", ".tmp");
        try {
            Files.writeString(temp, base64 + System.lineSeparator(), StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, destination);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return destination;
    }
}
