package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PeerAccessTicketsTest {
    @Test
    void signedTicketChecksAudienceFileAndExpiryAndTamper() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var requester = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        String token = PeerAccessTickets.issue(pair.getPrivate(), 101L,
                "reader-123", "uploader-999", requester.getPublic(), now);
        assertNotNull(PeerAccessTickets.verifyClaims(pair.getPublic(), token, 101L,
                "uploader-999", now.plusSeconds(20)));
        assertNull(PeerAccessTickets.verifyClaims(pair.getPublic(), token, 102L,
                "uploader-999", now));
        assertNull(PeerAccessTickets.verifyClaims(pair.getPublic(), token, 101L,
                "different", now));
        assertNull(PeerAccessTickets.verifyClaims(pair.getPublic(), token, 101L,
                "uploader-999", now.plusSeconds(PeerAccessTickets.TTL_SECONDS + 1)));
        assertNull(PeerAccessTickets.verifyClaims(pair.getPublic(), token + "X", 101L,
                "uploader-999", now));
        assertNull(PeerAccessTickets.verifyClaims(pair.getPublic(), null, 101L,
                "uploader-999", now));
    }

    @Test
    void requesterPublicKeyIsSignedAndReturnedInVerifiedClaims() throws Exception {
        var trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var requesterKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-01T00:00:00Z");

        String ticket = PeerAccessTickets.issue(trackerKeys.getPrivate(), 101L,
                "reader-123", "uploader-999", requesterKeys.getPublic(), now);

        PeerAccessTickets.TicketClaims claims = PeerAccessTickets.verifyClaims(
                trackerKeys.getPublic(), ticket, 101L, "uploader-999", now.plusSeconds(20));
        assertNotNull(claims);
        assertEquals(101L, claims.fileId());
        assertEquals("reader-123", claims.requesterId());
        assertEquals("uploader-999", claims.uploaderId());
        assertEquals(now.getEpochSecond(), claims.issuedAtEpochSecond());
        assertEquals(now.plusSeconds(PeerAccessTickets.DEFAULT_TTL_SECONDS).getEpochSecond(),
                claims.expiresAtEpochSecond());
        assertEquals(requesterKeys.getPublic(), claims.requesterPublicKey());
        assertEquals(PeerAccessTickets.DEFAULT_KEY_ID, claims.keyId());
        assertEquals(claims.expiresAtEpochSecond(),
                PeerAccessTickets.readExpiryEpochSecondUntrusted(ticket).orElseThrow());

        assertNull(PeerAccessTickets.verifyClaims(trackerKeys.getPublic(), ticket, 102L,
                "uploader-999", now));
        assertNull(PeerAccessTickets.verifyClaims(trackerKeys.getPublic(), ticket, 101L,
                "different-uploader", now));
        assertNull(PeerAccessTickets.verifyClaims(trackerKeys.getPublic(), ticket, 101L,
                "uploader-999", now.plusSeconds(PeerAccessTickets.TTL_SECONDS + 1)));
    }

    @Test
    void changingRequesterPublicKeyClaimInvalidatesTicket() throws Exception {
        var trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var requesterKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        String ticket = PeerAccessTickets.issue(trackerKeys.getPrivate(), 101L,
                "reader-123", "uploader-999", requesterKeys.getPublic(), now);
        String[] parts = ticket.split("\\.", -1);
        String claims = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII);
        String[] fields = claims.split(":", -1);
        fields[7] = (fields[7].charAt(0) == 'A' ? "B" : "A") + fields[7].substring(1);
        String alteredClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join(":", fields).getBytes(StandardCharsets.US_ASCII));

        assertNull(PeerAccessTickets.verifyClaims(trackerKeys.getPublic(),
                alteredClaims + "." + parts[1], 101L, "uploader-999", now));
    }

    @Test
    void configuredTtlDefaultsAndBoundsInvalidValues() {
        assertEquals(900L, PeerAccessTickets.resolveTtlSeconds(null));
        assertEquals(900L, PeerAccessTickets.resolveTtlSeconds(" "));
        assertEquals(900L, PeerAccessTickets.resolveTtlSeconds("not-a-number"));
        assertEquals(900L, PeerAccessTickets.resolveTtlSeconds("59"));
        assertEquals(900L, PeerAccessTickets.resolveTtlSeconds("3601"));
        assertEquals(120L, PeerAccessTickets.resolveTtlSeconds("120"));
        assertEquals(3600L, PeerAccessTickets.resolveTtlSeconds("3600"));
    }

    @Test
    void keyIdSelectsVerifierAndSupportsOverlapDuringRotation() throws Exception {
        var oldKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var newKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var requester = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        String oldTicket = PeerAccessTickets.issue(oldKeys.getPrivate(), "key-a", 101L,
                "reader-123", "uploader-999", requester.getPublic(), now, 120);
        String newTicket = PeerAccessTickets.issue(newKeys.getPrivate(), "key-b", 101L,
                "reader-123", "uploader-999", requester.getPublic(), now, 120);
        Map<String, java.security.PublicKey> both = Map.of(
                "key-a", oldKeys.getPublic(), "key-b", newKeys.getPublic());

        assertEquals("key-a", PeerAccessTickets.verifyClaims(both, oldTicket, 101L,
                "uploader-999", now).keyId());
        assertEquals("key-b", PeerAccessTickets.verifyClaims(both, newTicket, 101L,
                "uploader-999", now).keyId());
        assertNull(PeerAccessTickets.verifyClaims(Map.of("key-b", newKeys.getPublic()),
                oldTicket, 101L, "uploader-999", now));

        String[] parts = oldTicket.split("\\.", -1);
        String[] claims = new String(Base64.getUrlDecoder().decode(parts[0]),
                StandardCharsets.US_ASCII).split(":", -1);
        claims[8] = "unknown";
        String unknownIdTicket = signClaims(oldKeys.getPrivate(), String.join(":", claims));
        assertNull(PeerAccessTickets.verifyClaims(both, unknownIdTicket, 101L,
                "uploader-999", now));

        String missingIdClaims = String.join(":",
                java.util.Arrays.copyOf(claims, 8));
        String missingIdTicket = signClaims(oldKeys.getPrivate(), missingIdClaims);
        assertNull(PeerAccessTickets.verifyClaims(both, missingIdTicket, 101L,
                "uploader-999", now));
    }

    private static String signClaims(java.security.PrivateKey key, String claims) throws Exception {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims.getBytes(StandardCharsets.US_ASCII));
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(encoded.getBytes(StandardCharsets.US_ASCII));
        return encoded + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }
}
