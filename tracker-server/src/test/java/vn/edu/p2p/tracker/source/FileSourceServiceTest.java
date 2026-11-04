package vn.edu.p2p.tracker.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
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
                trackerKeys.getPrivate(), "test-key", 120, Clock.systemUTC()
        );

        FileSourcesResponse response = service.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L)
        );

        assertEquals(11L, response.fileId());
        assertEquals("a".repeat(64), response.fileHash());
        assertEquals(1, response.sources().size());
        assertEquals(sourcePeer.toString(), response.sources().getFirst().peerId());
        assertEquals(requesterPeer, sources.excludedPeer);
        var claims = PeerAccessTickets.verifyClaims(
                Map.of("test-key", trackerKeys.getPublic()),
                response.sources().getFirst().accessTicket(), 11L,
                sourcePeer.toString(), Instant.now());
        assertEquals(requesterKeys.getPublic(), claims.requesterPublicKey());
        assertEquals("test-key", claims.keyId());
        assertEquals(120L, claims.expiresAtEpochSecond() - claims.issuedAtEpochSecond());
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

    @Test
    void shouldRejectOfflineRequesterAndIssueNoTicketForUnsharedFile() throws Exception {
        UUID requesterPeer = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PeerSessionRecord offline = new PeerSessionRecord(
                sessionId, requesterPeer, "127.0.0.1", 7001, PeerStatus.LOGGED_OUT,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
        KeyPair trackerKeys = PeerRequestProof.generateKeyPair();
        FileSourceService offlineService = new FileSourceService(
                new FakeSourceRepository(List.of()),
                new PeerSessionService(new FakeSessionRepository(offline)),
                new FakePeerRepository(), trackerKeys.getPrivate());

        FileSourceException offlineError = assertThrows(FileSourceException.class,
                () -> offlineService.findSources(new FileSourcesRequest(sessionId.toString(), 11L)));
        assertEquals("SESSION_NOT_ONLINE", offlineError.errorCode());

        PeerSessionRecord online = new PeerSessionRecord(
                sessionId, requesterPeer, "127.0.0.1", 7001, PeerStatus.ONLINE,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC), null);
        FakePeerRepository peers = new FakePeerRepository();
        peers.publicKeys.put(requesterPeer,
                PeerRequestProof.encodePublicKey(PeerRequestProof.generateKeyPair().getPublic()));
        FileSourceService unsharedService = new FileSourceService(
                new FakeSourceRepository(List.of()),
                new PeerSessionService(new FakeSessionRepository(online)),
                peers, trackerKeys.getPrivate());

        assertEquals(List.of(), unsharedService.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L)).sources());
    }

    @Test
    void returnsBothPeerSourcesAndKeepsSecondAfterFirstUnshares() throws Exception {
        UUID requesterPeer = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PeerSessionRecord requesterSession = onlineSession(sessionId, requesterPeer, 7001);
        KeyPair requesterKeys = PeerRequestProof.generateKeyPair();
        KeyPair trackerKeys = PeerRequestProof.generateKeyPair();
        FakePeerRepository peers = new FakePeerRepository();
        peers.publicKeys.put(requesterPeer, PeerRequestProof.encodePublicKey(requesterKeys.getPublic()));
        UUID peerA = UUID.randomUUID();
        UUID peerB = UUID.randomUUID();
        FakeSourceRepository sources = new FakeSourceRepository(List.of(
                sourceRecord(peerA, 7002),
                sourceRecord(peerB, 7003)));
        FileSourceService service = new FileSourceService(
                sources, new PeerSessionService(new FakeSessionRepository(requesterSession)),
                peers, trackerKeys.getPrivate(), "test-key", 120,
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));

        FileSourcesResponse beforeUnshare = service.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L));
        assertEquals(java.util.Set.of(peerA.toString(), peerB.toString()),
                beforeUnshare.sources().stream().map(source -> source.peerId())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals("a".repeat(64), beforeUnshare.fileHash());

        sources.removeSource(peerA);
        FileSourcesResponse afterUnshare = service.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L));
        assertEquals(List.of(peerB.toString()),
                afterUnshare.sources().stream().map(source -> source.peerId()).toList());
    }

    @Test
    void trackerServiceRestartReturnsSourceAfterPeerRegistersAgain() throws Exception {
        FakeSourceRepository persistedRepository = new FakeSourceRepository(List.of());
        UUID requesterPeer = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        KeyPair requesterKeys = PeerRequestProof.generateKeyPair();
        KeyPair trackerKeys = PeerRequestProof.generateKeyPair();
        FakePeerRepository peers = new FakePeerRepository();
        peers.publicKeys.put(requesterPeer, PeerRequestProof.encodePublicKey(requesterKeys.getPublic()));
        FileSourceService oldTrackerInstance = new FileSourceService(
                persistedRepository,
                new PeerSessionService(new FakeSessionRepository(
                        onlineSession(sessionId, requesterPeer, 7004))),
                peers, trackerKeys.getPrivate(), "test-key", 120,
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));
        assertTrue(oldTrackerInstance.findSources(
                new FileSourcesRequest(sessionId.toString(), 11L)).sources().isEmpty());

        UUID restoredPeer = UUID.randomUUID();
        persistedRepository.registerSource(sourceRecord(restoredPeer, 7002));
        UUID restartedSessionId = UUID.randomUUID();
        FileSourceService restartedTrackerInstance = new FileSourceService(
                persistedRepository,
                new PeerSessionService(new FakeSessionRepository(
                        onlineSession(restartedSessionId, requesterPeer, 7004))),
                peers, trackerKeys.getPrivate(), "test-key", 120,
                Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));

        FileSourcesResponse response = restartedTrackerInstance.findSources(
                new FileSourcesRequest(restartedSessionId.toString(), 11L));
        assertEquals(List.of(restoredPeer.toString()),
                response.sources().stream().map(source -> source.peerId()).toList());
        assertEquals("a".repeat(64), response.fileHash());
    }

    private static PeerSessionRecord onlineSession(UUID sessionId, UUID peerId, int port) {
        OffsetDateTime now = OffsetDateTime.ofInstant(
                Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
        return new PeerSessionRecord(sessionId, peerId, "127.0.0.1", port,
                PeerStatus.ONLINE, now, now, null);
    }

    private static FileSourceRecord sourceRecord(UUID peerId, int port) {
        return new FileSourceRecord(11L, peerId, "COMPLETE", "127.0.0.1", port,
                OffsetDateTime.ofInstant(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC));
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
            this.result = new ArrayList<>(result);
        }

        @Override
        public Optional<String> findReadyCatalogHash(long fileId) { return Optional.of("a".repeat(64)); }

        @Override
        public List<FileSourceRecord> findOnlineSources(long fileId, UUID excludingPeerId) {
            this.excludedPeer = excludingPeerId;
            return result.stream().filter(source -> source.fileId() == fileId)
                    .filter(source -> !source.peerId().equals(excludingPeerId)).toList();
        }

        private void removeSource(UUID peerId) {
            result.removeIf(source -> source.peerId().equals(peerId));
        }

        private void registerSource(FileSourceRecord source) {
            result.removeIf(existing -> existing.fileId() == source.fileId()
                    && existing.peerId().equals(source.peerId()));
            result.add(source);
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
