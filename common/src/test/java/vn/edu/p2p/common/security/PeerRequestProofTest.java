package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerRequestProofTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void proofIsBoundToRequesterKeyAndEveryRequestField() throws Exception {
        var requester = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var otherRequester = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String ticket = "signed-ticket";
        PeerRequestProof.Proof proof = PeerRequestProof.sign(requester.getPrivate(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 3, ticket, CLOCK);

        assertTrue(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 3, ticket, proof, CLOCK));
        assertFalse(PeerRequestProof.verify(otherRequester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 3, ticket, proof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 43, 3, ticket, proof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 4, ticket, proof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-2", 42, 3, ticket, proof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.BITFIELD, "uploader-1", 42,
                PeerRequestProof.BITFIELD_PIECE_INDEX, ticket, proof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 3, "different-ticket",
                proof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 3, ticket,
                new PeerRequestProof.Proof(proof.timestampEpochMillis() + 1,
                        proof.requestNonce(), proof.signature()), CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 3, ticket,
                new PeerRequestProof.Proof(proof.timestampEpochMillis(),
                        "AAAAAAAAAAAAAAAAAAAAAA", proof.signature()), CLOCK));
    }

    @Test
    void proofRejectsTimestampsOutsideConfiguredWindow() throws Exception {
        var requester = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Clock oldClock = Clock.fixed(NOW.minusSeconds(61), ZoneOffset.UTC);
        Clock futureClock = Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC);
        PeerRequestProof.Proof oldProof = PeerRequestProof.sign(requester.getPrivate(),
                PeerRequestProof.RequestType.BITFIELD, "uploader-1", 42,
                PeerRequestProof.BITFIELD_PIECE_INDEX, "signed-ticket", oldClock);
        PeerRequestProof.Proof futureProof = PeerRequestProof.sign(requester.getPrivate(),
                PeerRequestProof.RequestType.BITFIELD, "uploader-1", 42,
                PeerRequestProof.BITFIELD_PIECE_INDEX, "signed-ticket", futureClock);

        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.BITFIELD, "uploader-1", 42,
                PeerRequestProof.BITFIELD_PIECE_INDEX, "signed-ticket", oldProof, CLOCK));
        assertFalse(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.BITFIELD, "uploader-1", 42,
                PeerRequestProof.BITFIELD_PIECE_INDEX, "signed-ticket", futureProof, CLOCK));
        assertTrue(PeerRequestProof.verify(requester.getPublic(),
                PeerRequestProof.RequestType.BITFIELD, "uploader-1", 42,
                PeerRequestProof.BITFIELD_PIECE_INDEX, "signed-ticket", oldProof, CLOCK,
                Duration.ofSeconds(61)));
    }

    @Test
    void fingerprintIsSha256OfTheEncodedPublicKey() throws Exception {
        var first = PeerRequestProof.generateKeyPair();
        var second = PeerRequestProof.generateKeyPair();
        String fingerprint = PeerRequestProof.fingerprint(first.getPublic());

        assertEquals(64, fingerprint.length());
        assertEquals(fingerprint, PeerRequestProof.fingerprint(first.getPublic()));
        assertNotEquals(fingerprint, PeerRequestProof.fingerprint(second.getPublic()));
    }

    @Test
    void stolenTicketCannotBeUsedWithoutRequestersPrivateKey() throws Exception {
        var tracker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var requester = PeerRequestProof.generateKeyPair();
        var thief = PeerRequestProof.generateKeyPair();
        String ticket = PeerAccessTickets.issue(tracker.getPrivate(), 42, "reader-1",
                "uploader-1", requester.getPublic(), NOW);
        PeerAccessTickets.TicketClaims claims = PeerAccessTickets.verifyClaims(
                tracker.getPublic(), ticket, 42, "uploader-1", NOW);
        assertTrue(claims != null);

        PeerRequestProof.Proof thiefProof = PeerRequestProof.sign(thief.getPrivate(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 0, ticket, CLOCK);

        assertFalse(PeerRequestProof.verify(claims.requesterPublicKey(),
                PeerRequestProof.RequestType.PIECE, "uploader-1", 42, 0, ticket, thiefProof, CLOCK));
    }
    @Test
    void metadataPageProofBindsBothOffsetAndPageSize() {
        var keys = PeerRequestProof.generateKeyPair();
        var proof = PeerRequestProof.signMetadataPage(keys.getPrivate(),
                "uploader-1", 42, 400, 200, "ticket", CLOCK);
        assertTrue(PeerRequestProof.verifyMetadataPage(keys.getPublic(),
                "uploader-1", 42, 400, 200, "ticket", proof, CLOCK));
        assertFalse(PeerRequestProof.verifyMetadataPage(keys.getPublic(),
                "uploader-1", 42, 401, 200, "ticket", proof, CLOCK));
        assertFalse(PeerRequestProof.verifyMetadataPage(keys.getPublic(),
                "uploader-1", 42, 400, 201, "ticket", proof, CLOCK));
        assertFalse(PeerRequestProof.verify(keys.getPublic(), PeerRequestProof.RequestType.PIECE,
                "uploader-1", 42, 400, "ticket", proof, CLOCK));
    }

}
