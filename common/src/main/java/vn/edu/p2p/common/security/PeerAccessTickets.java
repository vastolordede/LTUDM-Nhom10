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
 * Tracker-issued Ed25519 authorization tickets for P2P pieces and metadata.
 * Only Tracker holds a private signing key; every uploader holds a PUBLIC key.
 * The file ID and intended uploading peer are cryptographically bound.
 */
public final class PeerAccessTickets {
    public static final long TTL_SECONDS = 3600;
    private static final long CLOCK_SKEW_SECONDS = 90;
    private static final int MAX_TICKET_LENGTH = 2048;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CLAIMS_VERSION = "2";

    private PeerAccessTickets() {}

    public record TicketClaims(
            long fileId,
            String requesterId,
            String uploaderId,
            long issuedAtEpochSecond,
            long expiresAtEpochSecond,
            String nonce,
            PublicKey requesterPublicKey
    ) {}

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
        String explicitFile = setting("P2P_TICKET_SIGNING_KEY_FILE");
        String base64 = explicitFile != null ? readKeyFile(explicitFile) : setting("P2P_TICKET_SIGNING_KEY_B64");
        if (base64 == null) {
            java.nio.file.Path fallback = defaultKeyDirectory().resolve("tracker-private.key.b64");
            if (java.nio.file.Files.exists(fallback)) base64 = readKeyFile(fallback);
        }
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("Tracker Ed25519 signing key missing. Run "
                    + ":tracker-server:generateTicketKeys once and keep the private key on Tracker only.");
        }
        PrivateKey key = decodePrivateKey(base64);
        // When a companion public key is present, detect accidental key-pair mismatch at startup
        // rather than allowing peers to fail with ACCESS_DENIED after successful login.
        PublicKey publicKey = optionalPeerKey();
        if (publicKey != null) {
            java.security.KeyPair requester = PeerRequestProof.generateKeyPair();
            String startupTicket = issue(key, 1L, "startup-check", "startup-check",
                    requester.getPublic(), Instant.now());
            if (verifyClaims(publicKey, startupTicket, 1L, "startup-check", Instant.now()) == null) {
                throw new IllegalStateException(
                        "Tracker signing key does not match configured public key");
            }
        }
        return key;
    }

    /** A missing key makes the upload server deny requests, not silently allow them. */
    public static PublicKey optionalPeerKey() {
        String file = setting("P2P_TICKET_PUBLIC_KEY_FILE");
        String base64 = file != null ? readKeyFile(file) : setting("P2P_TICKET_PUBLIC_KEY_B64");
        if (base64 == null) {
            java.nio.file.Path fallback = defaultKeyDirectory().resolve("tracker-public.key.b64");
            if (java.nio.file.Files.exists(fallback)) base64 = readKeyFile(fallback);
        }
        return base64 == null ? null : decodePublicKey(base64);
    }

    private static java.nio.file.Path defaultKeyDirectory() {
        return java.nio.file.Path.of(System.getProperty("user.home"), ".fileshare-p2p");
    }

    private static String readKeyFile(String rawPath) {
        return readKeyFile(LocalEnv.load().resolvePath(rawPath));
    }

    private static String readKeyFile(java.nio.file.Path path) {
        try {
            if (java.nio.file.Files.isSymbolicLink(path)) {
                throw new IllegalStateException("Refusing symlinked key file: " + path);
            }
            return java.nio.file.Files.readString(path, StandardCharsets.US_ASCII).trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read Ed25519 key file: " + path, e);
        }
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
                               String uploaderId, PublicKey requesterPublicKey, Instant now) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requesterPublicKey, "requesterPublicKey");
        Objects.requireNonNull(now, "now");
        if (fileId <= 0 || !safeId(requesterId) || !safeId(uploaderId)) {
            throw new IllegalArgumentException("Invalid ticket claims");
        }
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String encodedPublicKey = PeerRequestProof.encodePublicKey(requesterPublicKey);
        String claimString = claims(CLAIMS_VERSION, fileId, requesterId, uploaderId,
                now, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce), encodedPublicKey);
        return signClaims(key, claimString);
    }

    private static String claims(String version, long fileId, String requesterId,
                                 String uploaderId, Instant now, String nonce,
                                 String requesterPublicKey) {
        String base = version + ":" + fileId + ":" + requesterId + ":" + uploaderId + ":"
                + now.getEpochSecond() + ":" + (now.getEpochSecond() + TTL_SECONDS) + ":" + nonce;
        return base + ":" + Objects.requireNonNull(requesterPublicKey, "requesterPublicKey");
    }

    private static String signClaims(PrivateKey key, String claims) {
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

    public static TicketClaims verifyClaims(PublicKey key, String token, long fileId,
                                            String uploaderId, Instant now) {
        return verifyInternal(key, token, fileId, uploaderId, now);
    }

    private static TicketClaims verifyInternal(PublicKey key, String token, long fileId,
                                               String uploaderId, Instant now) {
        if (key == null || token == null || token.length() > MAX_TICKET_LENGTH
                || now == null || fileId <= 0 || !safeId(uploaderId)) return null;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) return null;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(parts[0].getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[1]))) return null;
            String claims = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII);
            String[] c = claims.split(":", -1);
            if (c.length != 8 || !CLAIMS_VERSION.equals(c[0])
                    || Long.parseLong(c[1]) != fileId || !safeId(c[2])
                    || !uploaderId.equals(c[3]) || !isValidNonce(c[6])) return null;
            long issued = Long.parseLong(c[4]);
            long expires = Long.parseLong(c[5]);
            long current = now.getEpochSecond();
            if (!(issued <= current + CLOCK_SKEW_SECONDS && expires >= current
                    && expires > issued && expires - issued <= TTL_SECONDS
                    && expires <= current + TTL_SECONDS + CLOCK_SKEW_SECONDS)) return null;
            PublicKey requesterPublicKey = PeerRequestProof.decodePublicKey(c[7]);
            return new TicketClaims(Long.parseLong(c[1]), c[2], c[3], issued, expires,
                    c[6], requesterPublicKey);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return null;
        }
    }

    private static boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.length() != 22) return false;
        try {
            return Base64.getUrlDecoder().decode(nonce).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean safeId(String id) {
        return id != null && id.length() > 0 && id.length() <= 80 && id.matches("[A-Za-z0-9_-]+");
    }
}
