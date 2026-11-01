package vn.edu.p2p.common.security;

import vn.edu.p2p.common.config.LocalEnv;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * R43: Tracker-issued Ed25519 bearer tickets for P2P pieces and metadata.
 * Only Tracker holds a private signing key; every uploader holds a PUBLIC key.
 * The file ID and intended uploading peer are cryptographically bound.
 */
public final class PeerAccessTickets {
    public static final long TTL_SECONDS = 3600;
    private static final long CLOCK_SKEW_SECONDS = 90;
    private static final int MAX_TICKET_LENGTH = 2048;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PeerAccessTickets() {}

    public static PrivateKey decodePrivateKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(Objects.requireNonNull(encoded));
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PKCS8 Ed25519 private key", e);
        }
    }

    public static PublicKey decodePublicKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(Objects.requireNonNull(encoded));
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid X509 Ed25519 public key", e);
        }
    }

    /** Only use for Tracker boot; fail fast before opening any sockets. */
    public static PrivateKey requiredTrackerKey() {
        // Staging: prefer an externally mounted read-only secret rather than env vars.
        String file = setting("P2P_TICKET_SIGNING_KEY_FILE");
        String base64;
        if (file != null) {
            try {
                base64 = java.nio.file.Files.readString(java.nio.file.Path.of(file),
                        StandardCharsets.US_ASCII).trim();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read Tracker signing key file", e);
            }
        } else {
            base64 = setting("P2P_TICKET_SIGNING_KEY_B64");
        }
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("Tracker Ed25519 signing key file or env is required");
        }
        return decodePrivateKey(base64);
    }

    /** A missing key makes the upload server deny requests, not silently allow them. */
    public static PublicKey optionalPeerKey() {
        String base64 = setting("P2P_TICKET_PUBLIC_KEY_B64");
        return base64 == null ? null : decodePublicKey(base64);
    }

    public static boolean localInsecureDemoEnabled() {
        return "true".equalsIgnoreCase(setting("P2P_INSECURE_LOOPBACK_DEMO"));
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) value = LocalEnv.load().get(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String issue(PrivateKey key, long fileId, String requesterId,
                               String uploaderId, Instant now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(now, "now");
        if (fileId <= 0 || !safeId(requesterId) || !safeId(uploaderId)) {
            throw new IllegalArgumentException("Invalid ticket claims");
        }
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String claims = "1:" + fileId + ":" + requesterId + ":" + uploaderId + ":"
                + now.getEpochSecond() + ":" + (now.getEpochSecond() + TTL_SECONDS) + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String encodedClaims = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.getBytes(StandardCharsets.US_ASCII));
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(encodedClaims.getBytes(StandardCharsets.US_ASCII));
            return encodedClaims + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign peer ticket", e);
        }
    }

    public static boolean verify(PublicKey key, String token, long fileId,
                                 String uploaderId, Instant now) {
        if (key == null || token == null || token.length() > MAX_TICKET_LENGTH
                || fileId <= 0 || !safeId(uploaderId)) return false;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return false;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(parts[0].getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[1]))) return false;
            String claims = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII);
            String[] c = claims.split(":", -1);
            if (c.length != 7 || !"1".equals(c[0]) || Long.parseLong(c[1]) != fileId
                    || !safeId(c[2]) || !uploaderId.equals(c[3]) || c[6].length() != 22) return false;
            long issued = Long.parseLong(c[4]);
            long expires = Long.parseLong(c[5]);
            long current = now.getEpochSecond();
            return issued <= current + CLOCK_SKEW_SECONDS && expires >= current
                    && expires > issued && expires - issued <= TTL_SECONDS
                    && expires <= current + TTL_SECONDS + CLOCK_SKEW_SECONDS;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return false;
        }
    }

    private static boolean safeId(String id) {
        return id != null && id.length() > 0 && id.length() <= 80 && id.matches("[A-Za-z0-9_-]+");
    }
}
