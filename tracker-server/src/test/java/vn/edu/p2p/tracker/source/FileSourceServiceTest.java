package vn.edu.p2p.tracker.source;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.FileSourcesRequest;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.tracker.domain.FileSourceRecord;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.peer.PeerSessionService;
import vn.edu.p2p.tracker.repository.FileSourceRepository;
import vn.edu.p2p.tracker.repository.PeerRepository;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        KeyPair requesterKeys = PeerRequestProof.generateKeyPair();
        KeyPair trackerKeys = PeerRequestProof.generateKeyPair();
        FakePeerRepository peers = new FakePeerRepository();
        peers.publicKeys.put(requesterPeer, PeerRequestProof.encodePublicKey(requesterKeys.getPublic()));
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
                new PeerSessionService(sessions),
                peers,
                trackerKeys.getPrivate()
        );

        FileSourcesResponse response = service.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L)
        );

        assertEquals(11L, response.fileId());
        assertEquals("a".repeat(64), response.fileHash());
        assertEquals(1, response.sources().size());
        assertEquals(sourcePeer.toString(), response.sources().getFirst().peerId());
        assertEquals(requesterPeer, sources.excludedPeer);
        assertEquals(requesterKeys.getPublic(), PeerAccessTickets.verifyClaims(
                trackerKeys.getPublic(), response.sources().getFirst().accessTicket(), 11L,
                sourcePeer.toString(), Instant.now()).requesterPublicKey());
    }

    @Test
    void shouldRefuseToIssueTicketWhenRequesterHasNoRegisteredKey() {
        UUID requesterPeer = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PeerSessionRecord requesterSession = new PeerSessionRecord(
                sessionId, requesterPeer, "127.0.0.1", 7001, PeerStatus.ONLINE,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC), null);
        FileSourceService service = new FileSourceService(
                new FakeSourceRepository(List.of()),
                new PeerSessionService(new FakeSessionRepository(requesterSession)),
                new FakePeerRepository(),
                null);

        FileSourceException error = assertThrows(FileSourceException.class,
                () -> service.findSources(new FileSourcesRequest(sessionId.toString(), 11L)));

        assertEquals("REQUESTER_KEY_MISSING", error.errorCode());
        assertEquals("Peer public key is missing; please log in again.", error.getMessage());
    }

    private static final class FakePeerRepository implements PeerRepository {
        private final Map<UUID, String> publicKeys = new HashMap<>();

        @Override
        public Optional<vn.edu.p2p.tracker.domain.PeerRecord> findById(UUID peerId) {
            return Optional.empty();
        }

        @Override
        public void create(vn.edu.p2p.tracker.domain.PeerRecord peer) {}

        @Override
        public void updateLastLogin(UUID peerId, OffsetDateTime lastLoginAt) {}

        @Override
        public Optional<String> findPublicKey(UUID peerId) {
            return Optional.ofNullable(publicKeys.get(peerId));
        }

        @Override
        public void updatePublicKey(UUID peerId, String publicKey) throws SQLException {
            publicKeys.put(peerId, publicKey);
        }
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
