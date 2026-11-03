package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
        assertEquals(now.plusSeconds(PeerAccessTickets.TTL_SECONDS).getEpochSecond(),
                claims.expiresAtEpochSecond());
        assertEquals(requesterKeys.getPublic(), claims.requesterPublicKey());

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
}
