package vn.edu.p2p.tracker.peer;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerSessionServiceTest {

    @Test
    void heartbeatShouldUpdateLastSeenForOnlineSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        FakeSessionRepository sessions = new FakeSessionRepository();

        sessions.sessions.put(sessionId, new PeerSessionRecord(
                sessionId,
                peerId,
                "127.0.0.1",
                7001,
                PeerStatus.ONLINE,
                now.minusSeconds(30).atOffset(ZoneOffset.UTC),
                now.minusSeconds(10).atOffset(ZoneOffset.UTC),
                null
        ));

        PeerSessionService service = new PeerSessionService(
                sessions,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        HeartbeatResponse response = service.heartbeat(
                new HeartbeatRequest(sessionId.toString())
        );

        assertEquals(now.toEpochMilli(), response.serverTimeEpochMillis());
        assertEquals(now.atOffset(ZoneOffset.UTC), sessions.lastSeen);
    }

    @Test
    void heartbeatShouldRejectLoggedOutSession() {
        UUID sessionId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        FakeSessionRepository sessions = new FakeSessionRepository();

        sessions.sessions.put(sessionId, new PeerSessionRecord(
                sessionId,
                peerId,
                "127.0.0.1",
                7001,
                PeerStatus.LOGGED_OUT,
                now.minusSeconds(30).atOffset(ZoneOffset.UTC),
                now.minusSeconds(10).atOffset(ZoneOffset.UTC),
                now.minusSeconds(5).atOffset(ZoneOffset.UTC)
        ));

        PeerSessionService service = new PeerSessionService(
                sessions,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        SessionException error = assertThrows(
                SessionException.class,
                () -> service.heartbeat(new HeartbeatRequest(sessionId.toString()))
        );

        assertEquals("SESSION_NOT_ONLINE", error.errorCode());
    }

    @Test
    void requireOnlineSessionShouldRejectMalformedUuid() {
        PeerSessionService service = new PeerSessionService(
                new FakeSessionRepository(),
                Clock.systemUTC()
        );

        SessionException error = assertThrows(
                SessionException.class,
                () -> service.requireOnlineSession("not-a-uuid")
        );

        assertEquals("INVALID_SESSION", error.errorCode());
    }

    private static final class FakeSessionRepository implements PeerSessionRepository {
        final Map<UUID, PeerSessionRecord> sessions = new HashMap<>();
        OffsetDateTime lastSeen;

        @Override
        public void create(PeerSessionRecord session) {
            sessions.put(session.sessionId(), session);
        }

        @Override
        public Optional<PeerSessionRecord> findBySessionId(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public int closeActiveForPeer(UUID peerId, OffsetDateTime closedAt) {
            return 0;
        }

        @Override
        public boolean updateLastSeen(UUID sessionId, OffsetDateTime lastSeen) {
            PeerSessionRecord current = sessions.get(sessionId);
            if (current == null || current.status() != PeerStatus.ONLINE) {
                return false;
            }
            this.lastSeen = lastSeen;
            sessions.put(sessionId, new PeerSessionRecord(
                    current.sessionId(),
                    current.peerId(),
                    current.ipAddress(),
                    current.listeningPort(),
                    current.status(),
                    current.loginAt(),
                    lastSeen,
                    current.logoutAt()
            ));
            return true;
        }

        @Override
        public boolean markLoggedOut(UUID sessionId, OffsetDateTime logoutAt) {
            return sessions.containsKey(sessionId);
        }

        @Override
        public int expireStaleSessions(OffsetDateTime staleBefore) {
            return 0;
        }
    }
}
