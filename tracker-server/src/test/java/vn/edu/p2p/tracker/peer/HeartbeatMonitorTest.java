package vn.edu.p2p.tracker.peer;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeartbeatMonitorTest {

    @Test
    void shouldExpireOnlyOnlineSessionsOlderThanTimeout() throws Exception {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        FakeSessionRepository sessions = new FakeSessionRepository();

        UUID staleId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();

        sessions.sessions.put(staleId, session(
                staleId,
                PeerStatus.ONLINE,
                now.minusSeconds(31)
        ));
        sessions.sessions.put(freshId, session(
                freshId,
                PeerStatus.ONLINE,
                now.minusSeconds(5)
        ));

        HeartbeatMonitor monitor = new HeartbeatMonitor(
                sessions,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Clock.fixed(now, ZoneOffset.UTC),
                new TicketRevocationRegistry(Clock.fixed(now, ZoneOffset.UTC))
        );

        int expired = monitor.scanOnce();

        assertEquals(1, expired);
        assertEquals(PeerStatus.EXPIRED, sessions.sessions.get(staleId).status());
        assertEquals(PeerStatus.ONLINE, sessions.sessions.get(freshId).status());
        monitor.close();
    }

    @Test
    void expiringHeartbeatSessionRevokesItsRequesterTickets() throws Exception {
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        FakeSessionRepository sessions = new FakeSessionRepository();
        UUID staleId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        sessions.sessions.put(staleId, new PeerSessionRecord(staleId, peerId, "127.0.0.1",
                7001, PeerStatus.ONLINE, now.minusSeconds(60).atOffset(ZoneOffset.UTC),
                now.minusSeconds(31).atOffset(ZoneOffset.UTC), null));
        TicketRevocationRegistry registry =
                new TicketRevocationRegistry(Clock.fixed(now, ZoneOffset.UTC));
        HeartbeatMonitor monitor = new HeartbeatMonitor(sessions, Duration.ofSeconds(30),
                Duration.ofSeconds(10), Clock.fixed(now, ZoneOffset.UTC), registry);

        assertEquals(1, monitor.scanOnce());

        var revocation = registry.entriesAfter(0).get(0);
        assertEquals(vn.edu.p2p.common.dto.TicketRevocation.Type.REQUESTER, revocation.type());
        assertEquals(peerId.toString(), revocation.requesterPeerId());
        assertEquals(now.toEpochMilli(), revocation.revokedBeforeEpochMillis());
        monitor.close();
    }

    private static PeerSessionRecord session(
            UUID sessionId,
            PeerStatus status,
            Instant lastSeen
    ) {
        return new PeerSessionRecord(
                sessionId,
                UUID.randomUUID(),
                "127.0.0.1",
                7001,
                status,
                lastSeen.minusSeconds(10).atOffset(ZoneOffset.UTC),
                lastSeen.atOffset(ZoneOffset.UTC),
                null
        );
    }

    private static final class FakeSessionRepository implements PeerSessionRepository {
        final Map<UUID, PeerSessionRecord> sessions = new HashMap<>();

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
            return false;
        }

        @Override
        public boolean markLoggedOut(UUID sessionId, OffsetDateTime logoutAt) {
            return false;
        }

        @Override
        public int expireStaleSessions(OffsetDateTime staleBefore) {
            int count = 0;
            for (Map.Entry<UUID, PeerSessionRecord> entry : sessions.entrySet()) {
                PeerSessionRecord current = entry.getValue();
                if (current.status() == PeerStatus.ONLINE
                        && current.lastSeen().isBefore(staleBefore)) {
                    entry.setValue(new PeerSessionRecord(
                            current.sessionId(),
                            current.peerId(),
                            current.ipAddress(),
                            current.listeningPort(),
                            PeerStatus.EXPIRED,
                            current.loginAt(),
                            current.lastSeen(),
                            current.logoutAt()
                    ));
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<PeerSessionRecord> expireStaleSessionsAndFind(OffsetDateTime staleBefore) {
            List<PeerSessionRecord> expired = sessions.values().stream()
                    .filter(current -> current.status() == PeerStatus.ONLINE
                            && current.lastSeen().isBefore(staleBefore))
                    .toList();
            expireStaleSessions(staleBefore);
            return expired;
        }
    }
}
