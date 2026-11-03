package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.dto.TicketRevocation;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketRevocationListTest {
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    @Test
    void appliesRequesterAndFileRevocationsAndReloadsAfterTrackerSequenceReset() {
        TicketRevocationList list = new TicketRevocationList(Clock.fixed(NOW, ZoneOffset.UTC));
        var keys = PeerRequestProof.generateKeyPair();
        var requesterTicket = claims("requester-1", "uploader-1", 10L, keys.getPublic());
        var fileTicket = claims("requester-2", "uploader-1", 10L, keys.getPublic());

        assertFalse(list.isRevoked(requesterTicket, "uploader-1", 10L));
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(
                new TicketRevocation(1, TicketRevocation.Type.REQUESTER, "requester-1",
                        null, 0, NOW.toEpochMilli()),
                new TicketRevocation(2, TicketRevocation.Type.FILE, null, "uploader-1",
                        10, NOW.toEpochMilli())), 2L));
        assertTrue(list.isRevoked(requesterTicket, "uploader-1", 10L));
        assertTrue(list.isRevoked(fileTicket, "uploader-1", 10L));
        assertFalse(list.isRevoked(fileTicket, "uploader-2", 10L));

        var afterRestart = new TicketRevocation(1, TicketRevocation.Type.REQUESTER,
                "requester-2", null, 0, NOW.toEpochMilli());
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(),
                java.util.List.of(afterRestart), 1L));
        assertFalse(list.isRevoked(requesterTicket, "uploader-1", 10L));
        assertTrue(list.isRevoked(fileTicket, "uploader-1", 10L));
        assertTrue(list.latestSequence() == 1L);
    }

    @Test
    void missingRevocationFieldsFromLegacyHeartbeatDoNotClearKnownRevocations() throws Exception {
        TicketRevocationList list = new TicketRevocationList(Clock.fixed(NOW, ZoneOffset.UTC));
        var keys = PeerRequestProof.generateKeyPair();
        var claims = claims("requester-1", "uploader-1", 10L, keys.getPublic());
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(
                new TicketRevocation(1, TicketRevocation.Type.REQUESTER, "requester-1",
                        null, 0, NOW.toEpochMilli())), 1L));

        HeartbeatResponse legacyResponse = ProtocolCodec.fromPayload(
                ProtocolCodec.toPayload(java.util.Map.of(
                        "serverTimeEpochMillis", NOW.toEpochMilli())),
                HeartbeatResponse.class);
        list.apply(legacyResponse);

        assertTrue(list.isRevoked(claims, "uploader-1", 10L));
        assertEquals(1L, list.latestSequence());
    }

    @Test
    void ticketIssuedAfterRevocationRemainsValid() {
        TicketRevocationList list = new TicketRevocationList(Clock.fixed(NOW, ZoneOffset.UTC));
        var keys = PeerRequestProof.generateKeyPair();
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(
                new TicketRevocation(1, TicketRevocation.Type.REQUESTER, "requester-1",
                        null, 0, NOW.plusSeconds(1).toEpochMilli())), 1L));
        var laterTicket = claims("requester-1", "uploader-1", 10L, keys.getPublic(),
                NOW.plusSeconds(2));
        assertFalse(list.isRevoked(laterTicket, "uploader-1", 10L));
    }

    @Test
    void trackerInstanceChangeClearsOldRevocationsAndAcceptsNewInstanceEntries() {
        TicketRevocationList list = new TicketRevocationList(Clock.fixed(NOW, ZoneOffset.UTC));
        var keys = PeerRequestProof.generateKeyPair();
        var oldClaims = claims("old-requester", "uploader-1", 10L, keys.getPublic());
        var newClaims = claims("new-requester", "uploader-1", 10L, keys.getPublic());
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(
                new TicketRevocation(1, TicketRevocation.Type.REQUESTER, "old-requester",
                        null, 0, NOW.toEpochMilli())), 1L, "tracker-A"));

        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(
                new TicketRevocation(1, TicketRevocation.Type.REQUESTER, "new-requester",
                        null, 0, NOW.toEpochMilli())), 1L, "tracker-B"));

        assertFalse(list.isRevoked(oldClaims, "uploader-1", 10L));
        assertTrue(list.isRevoked(newClaims, "uploader-1", 10L));
        assertEquals(1L, list.latestSequence());
        assertEquals("tracker-B", list.trackerInstanceId());
    }

    @Test
    void sameTrackerInstanceAndMissingInstanceIdDoNotResetRevocations() {
        TicketRevocationList list = new TicketRevocationList(Clock.fixed(NOW, ZoneOffset.UTC));
        var keys = PeerRequestProof.generateKeyPair();
        var claims = claims("requester-1", "uploader-1", 10L, keys.getPublic());
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(
                new TicketRevocation(1, TicketRevocation.Type.REQUESTER, "requester-1",
                        null, 0, NOW.toEpochMilli())), 1L, "tracker-A"));

        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), java.util.List.of(), 1L, "tracker-A"));
        list.apply(new HeartbeatResponse(NOW.toEpochMilli(), null, null, null));

        assertTrue(list.isRevoked(claims, "uploader-1", 10L));
        assertEquals("tracker-A", list.trackerInstanceId());
    }

    private static PeerAccessTickets.TicketClaims claims(String requester, String uploader,
                                                          long fileId,
                                                          java.security.PublicKey key) {
        return claims(requester, uploader, fileId, key, NOW.minusSeconds(1));
    }

    private static PeerAccessTickets.TicketClaims claims(String requester, String uploader,
                                                          long fileId,
                                                          java.security.PublicKey key,
                                                          Instant issuedAt) {
        return new PeerAccessTickets.TicketClaims(fileId, requester, uploader,
                issuedAt.getEpochSecond(), issuedAt.plusSeconds(60).getEpochSecond(),
                "test-nonce", key);
    }
}
