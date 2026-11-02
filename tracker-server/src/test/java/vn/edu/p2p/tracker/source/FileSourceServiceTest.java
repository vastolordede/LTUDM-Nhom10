package vn.edu.p2p.tracker.source;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.FileSourcesRequest;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.tracker.domain.FileSourceRecord;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.peer.PeerSessionService;
import vn.edu.p2p.tracker.repository.FileSourceRepository;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileSourceServiceTest {
    @Test
    void shouldReturnOnlineSourcesAndExcludeRequester() throws Exception {
        UUID requesterPeer = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID sourcePeer = UUID.randomUUID();

        PeerSessionRecord requesterSession = new PeerSessionRecord(
                sessionId,
                requesterPeer,
                "127.0.0.1",
                7001,
                PeerStatus.ONLINE,
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC),
                null
        );

        FakeSessionRepository sessions = new FakeSessionRepository(requesterSession);
        FakeSourceRepository sources = new FakeSourceRepository(List.of(
                new FileSourceRecord(
                        11L,
                        sourcePeer,
                        "COMPLETE",
                        "192.168.1.20",
                        7002,
                        OffsetDateTime.now(ZoneOffset.UTC)
                )
        ));

        FileSourceService service = new FileSourceService(
                sources,
                new PeerSessionService(sessions)
        );

        FileSourcesResponse response = service.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L)
        );

        assertEquals(11L, response.fileId());
        assertEquals("a".repeat(64), response.fileHash());
        assertEquals(1, response.sources().size());
        assertEquals(sourcePeer.toString(), response.sources().getFirst().peerId());
        assertEquals(requesterPeer, sources.excludedPeer);
    }

    private static final class FakeSourceRepository implements FileSourceRepository {
        private final List<FileSourceRecord> result;
        private UUID excludedPeer;

        private FakeSourceRepository(List<FileSourceRecord> result) {
            this.result = result;
        }

        @Override
        public Optional<String> findReadyCatalogHash(long fileId) { return Optional.of("a".repeat(64)); }

        @Override
        public List<FileSourceRecord> findOnlineSources(long fileId, UUID excludingPeerId) {
            this.excludedPeer = excludingPeerId;
            return result;
        }
    }

    private static final class FakeSessionRepository implements PeerSessionRepository {
        private final PeerSessionRecord session;

        private FakeSessionRepository(PeerSessionRecord session) {
            this.session = session;
        }

        @Override
        public void create(PeerSessionRecord session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PeerSessionRecord> findBySessionId(UUID sessionId) {
            return session.sessionId().equals(sessionId)
                    ? Optional.of(session)
                    : Optional.empty();
        }

        @Override
        public int closeActiveForPeer(UUID peerId, OffsetDateTime closedAt) { return 0; }

        @Override
        public boolean updateLastSeen(UUID sessionId, OffsetDateTime lastSeen) { return false; }

        @Override
        public boolean markLoggedOut(UUID sessionId, OffsetDateTime logoutAt) { return false; }

        @Override
        public int expireStaleSessions(OffsetDateTime staleBefore) { return 0; }
    }
}
