package vn.edu.p2p.common.security;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class PeerRequestProof {
    public static final int BITFIELD_PIECE_INDEX = -1;
    public static final long DEFAULT_TIMESTAMP_WINDOW_SECONDS = 60;
    private static final byte[] DOMAIN = "p2p-req-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int REQUEST_NONCE_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum RequestType {
        BITFIELD,
        PIECE,
        METADATA_PAGE
    }

    public record Proof(long timestampEpochMillis, String requestNonce, String signature) {}

    private PeerRequestProof() {}

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate Ed25519 key pair", e);
        }
    }

    public static String fingerprint(PublicKey key) {
        Objects.requireNonNull(key, "key");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot calculate public-key fingerprint", e);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        Objects.requireNonNull(key, "key");
        if (!isEd25519(key)) {
            throw new IllegalArgumentException("Public key must use Ed25519");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > 256) {
            throw new IllegalArgumentException("Invalid Ed25519 public key encoding");
        }
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(encoded);
            if (keyBytes.length < 32 || keyBytes.length > 128) {
                throw new IllegalArgumentException("Invalid Ed25519 public key length");
            }
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            if (!isEd25519(key)) {
                throw new IllegalArgumentException("Public key must use Ed25519");
            }
            return key;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Ed25519 X.509 public key", e);
        }
    }

    public static Proof sign(PrivateKey privateKey, RequestType requestType, String uploaderId,
                             long fileId, int pieceIndex, String ticket, Clock clock) {
        return signInternal(privateKey, requestType, uploaderId, fileId, pieceIndex,
                null, ticket, clock);
    }

    /** Authorizes exactly one metadata page, including its start index AND size. */
    public static Proof signMetadataPage(PrivateKey privateKey, String uploaderId,
                                         long fileId, int startIndex, int pageSize,
                                         String ticket, Clock clock) {
        return signInternal(privateKey, RequestType.METADATA_PAGE, uploaderId,
                fileId, startIndex, pageSize, ticket, clock);
    }

    private static Proof signInternal(PrivateKey privateKey, RequestType requestType,
                                      String uploaderId, long fileId, int pieceIndex,
                                      Integer metadataPageSize, String ticket, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        byte[] nonce = new byte[REQUEST_NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        long timestamp = clock.millis();
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(Objects.requireNonNull(privateKey, "privateKey"));
            signer.update(canonicalMessage(requestType, uploaderId, fileId, pieceIndex,
                    metadataPageSize, ticket, timestamp, nonce));
            return new Proof(timestamp, Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        } catch (java.security.GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot sign peer request proof", e);
        }
    }

    public static boolean verify(PublicKey publicKey, RequestType requestType, String uploaderId,
                                 long fileId, int pieceIndex, String ticket, Proof proof,
                                 Clock clock) {
        return verify(publicKey, requestType, uploaderId, fileId, pieceIndex, ticket,
                proof, clock, Duration.ofSeconds(DEFAULT_TIMESTAMP_WINDOW_SECONDS));
    }

    public static boolean verify(PublicKey publicKey, RequestType requestType, String uploaderId,
                                 long fileId, int pieceIndex, String ticket, Proof proof,
                                 Clock clock, Duration allowedTimestampWindow) {
        return verifyInternal(publicKey, requestType, uploaderId, fileId, pieceIndex,
                null, ticket, proof, clock, allowedTimestampWindow);
    }

    public static boolean verifyMetadataPage(PublicKey publicKey, String uploaderId,
                                             long fileId, int startIndex, int pageSize,
                                             String ticket, Proof proof, Clock clock) {
        return verifyInternal(publicKey, RequestType.METADATA_PAGE, uploaderId,
                fileId, startIndex, pageSize, ticket, proof, clock,
                Duration.ofSeconds(DEFAULT_TIMESTAMP_WINDOW_SECONDS));
    }

    private static boolean verifyInternal(PublicKey publicKey, RequestType requestType,
                                          String uploaderId, long fileId, int pieceIndex,
                                          Integer metadataPageSize, String ticket, Proof proof,
                                          Clock clock, Duration allowedTimestampWindow) {
        if (publicKey == null || proof == null || clock == null || allowedTimestampWindow == null
                || allowedTimestampWindow.isNegative()) return false;
        try {
            validateRequest(requestType, uploaderId, fileId, pieceIndex, metadataPageSize, ticket);
            long windowMillis = allowedTimestampWindow.toMillis();
            long ageMillis = Math.subtractExact(clock.millis(), proof.timestampEpochMillis());
            if (ageMillis < -windowMillis || ageMillis > windowMillis) return false;
            byte[] nonce = Base64.getUrlDecoder().decode(proof.requestNonce());
            if (nonce.length != REQUEST_NONCE_BYTES) return false;
            byte[] signatureBytes = Base64.getUrlDecoder().decode(proof.signature());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonicalMessage(requestType, uploaderId, fileId, pieceIndex,
                    metadataPageSize, ticket, proof.timestampEpochMillis(), nonce));
            return verifier.verify(signatureBytes);
        } catch (RuntimeException | java.security.GeneralSecurityException | IOException e) {
            return false;
        }
    }

    private static byte[] canonicalMessage(RequestType requestType, String uploaderId,
                                           long fileId, int pieceIndex, Integer metadataPageSize,
                                           String ticket, long timestampEpochMillis, byte[] requestNonce)
            throws IOException {
        validateRequest(requestType, uploaderId, fileId, pieceIndex, metadataPageSize, ticket);
        Objects.requireNonNull(requestNonce, "requestNonce");
        byte[] ticketHash;
        try {
            ticketHash = MessageDigest.getInstance("SHA-256")
                    .digest(ticket.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot hash access ticket", e);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeBytes(out, DOMAIN);
            writeBytes(out, requestType.name().getBytes(StandardCharsets.US_ASCII));
            writeBytes(out, uploaderId.getBytes(StandardCharsets.UTF_8));
            out.writeLong(fileId);
            out.writeInt(pieceIndex);
            // The METADATA_PAGE domain also binds the number of hashes requested.
            if (requestType == RequestType.METADATA_PAGE) out.writeInt(metadataPageSize);
            writeBytes(out, ticketHash);
            out.writeLong(timestampEpochMillis);
            writeBytes(out, requestNonce);
        }
        return buffer.toByteArray();
    }

    private static void validateRequest(RequestType requestType, String uploaderId, long fileId,
                                        int pieceIndex, Integer metadataPageSize, String ticket) {
        Objects.requireNonNull(requestType, "requestType");
        if (uploaderId == null || uploaderId.isEmpty() || uploaderId.length() > 80
                || !uploaderId.matches("[A-Za-z0-9_-]+") || fileId <= 0
                || ticket == null || ticket.isEmpty()) {
            throw new IllegalArgumentException("Invalid peer request proof fields");
        }
        if (requestType == RequestType.BITFIELD && pieceIndex != BITFIELD_PIECE_INDEX
                || requestType == RequestType.PIECE && pieceIndex < 0
                || requestType == RequestType.METADATA_PAGE && (pieceIndex < 0
                       || metadataPageSize == null || metadataPageSize < 1 || metadataPageSize > 400)
                || requestType != RequestType.METADATA_PAGE && metadataPageSize != null) {
            throw new IllegalArgumentException("Invalid index/page size for request type");
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static boolean isEd25519(PublicKey key) {
        return "Ed25519".equalsIgnoreCase(key.getAlgorithm())
                || "EdDSA".equalsIgnoreCase(key.getAlgorithm());
    }
}
