package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PeerAccessTicketsTest {
    @Test
    void signedTicketChecksAudienceFileAndExpiryAndTamper() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.parse("2026-11-01T00:00:00Z");
        String token = PeerAccessTickets.issue(pair.getPrivate(), 101L,
                "reader-123", "uploader-999", now);
        assertTrue(PeerAccessTickets.verify(pair.getPublic(), token, 101L,
                "uploader-999", now.plusSeconds(20)));
        assertFalse(PeerAccessTickets.verify(pair.getPublic(), token, 102L,
                "uploader-999", now));
        assertFalse(PeerAccessTickets.verify(pair.getPublic(), token, 101L,
                "different", now));
        assertFalse(PeerAccessTickets.verify(pair.getPublic(), token, 101L,
                "uploader-999", now.plusSeconds(PeerAccessTickets.TTL_SECONDS + 1)));
        assertFalse(PeerAccessTickets.verify(pair.getPublic(), token + "X", 101L,
                "uploader-999", now));
        assertFalse(PeerAccessTickets.verify(pair.getPublic(), null, 101L,
                "uploader-999", now));
    }
}
