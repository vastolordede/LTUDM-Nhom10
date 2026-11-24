package vn.edu.p2p.common.security;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;

/** Proof that the login client holds the Ed25519 private key of the claimed Peer ID.
 * Prevents an authenticated account from silently replacing an existing Peer identity.
 * This is supplementary to credential authentication over authenticated Tracker TLS. */
public final class PeerLoginProof {
    public record Proof(long timestampMillis, String nonce, String signature) { }
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] DOMAIN = "fileshare-peer-login-v1".getBytes(StandardCharsets.US_ASCII);
    private static final long WINDOW_MS = 120_000;
    private PeerLoginProof() { }

    public static Proof sign(PrivateKey key, String username, String peerId,
                             int listeningPort, String encodedPublicKey, Clock clock) {
        byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long time = clock.millis();
        try {
            Signature sign = Signature.getInstance("Ed25519");
            sign.initSign(key);
            sign.update(canonical(username, peerId, listeningPort, encodedPublicKey, time, nonce));
            return new Proof(time, nonce, Base64.getUrlEncoder().withoutPadding().encodeToString(sign.sign()));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot sign login device proof", e);
        }
    }

    public static boolean verify(PublicKey key, String username, String peerId,
                                 int listeningPort, String encodedPublicKey,
                                 Proof proof, Clock clock) {
        if (proof == null || proof.nonce() == null || proof.signature() == null) return false;
        try {
            long skew = Math.subtractExact(clock.millis(), proof.timestampMillis());
            if (skew < -WINDOW_MS || skew > WINDOW_MS) return false;
            if (Base64.getUrlDecoder().decode(proof.nonce()).length != 16) return false;
            byte[] sig = Base64.getUrlDecoder().decode(proof.signature());
            if (sig.length != 64) return false;
            Signature verify = Signature.getInstance("Ed25519");
            verify.initVerify(key);
            verify.update(canonical(username, peerId, listeningPort, encodedPublicKey,
                    proof.timestampMillis(), proof.nonce()));
            return verify.verify(sig);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            return false;
        }
    }

    private static byte[] canonical(String username, String peerId, int port,
                                    String encodedKey, long timestamp, String nonce)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buf)) {
            out.writeInt(DOMAIN.length); out.write(DOMAIN);
            write(out, username); write(out, peerId); out.writeInt(port);
            write(out, encodedKey); out.writeLong(timestamp); write(out, nonce);
        }
        return buf.toByteArray();
    }
    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512) throw new IllegalArgumentException("Login proof field too large");
        out.writeInt(bytes.length); out.write(bytes);
    }
}
