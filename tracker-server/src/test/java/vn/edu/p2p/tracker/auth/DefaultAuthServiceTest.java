package vn.edu.p2p.tracker.auth;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.tracker.domain.AccountStatus;
import vn.edu.p2p.tracker.domain.PeerRecord;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.UserRecord;
import vn.edu.p2p.tracker.repository.PeerRepository;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;
import vn.edu.p2p.tracker.repository.UserRepository;
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;

import java.sql.SQLException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultAuthServiceTest {

    @Test
    void shouldLoginAndLogout() throws Exception {
        FakeUserRepository users = new FakeUserRepository();
        FakePeerRepository peers = new FakePeerRepository();
        FakeSessionRepository sessions = new FakeSessionRepository();
        TicketRevocationRegistry revocations = new TicketRevocationRegistry(
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC));
        PasswordService passwords = new BCryptPasswordService(4);

        users.user = new UserRecord(
                1L,
                "dang",
                passwords.hash("secret"),
                AccountStatus.ACTIVE,
                OffsetDateTime.now()
        );

        DefaultAuthService service = new DefaultAuthService(
                users,
                peers,
                sessions,
                passwords,
                10,
                revocations,
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
        );

        String peerId = UUID.randomUUID().toString();
        KeyPair peerKeys = PeerRequestProof.generateKeyPair();
        LoginResponse response = service.login(
                new LoginRequest("dang", "secret", 7001, peerId, "TEST-PC",
                        PeerRequestProof.encodePublicKey(peerKeys.getPublic())),
                "127.0.0.1"
        );

        assertEquals(peerId, response.peerId());
        assertNotNull(response.sessionId());
        assertEquals(10, response.heartbeatIntervalSeconds());
        assertEquals(1, sessions.sessions.size());
        assertEquals(PeerRequestProof.encodePublicKey(peerKeys.getPublic()),
                peers.publicKeys.get(UUID.fromString(peerId)));

        service.logout(new LogoutRequest(response.sessionId()));
        assertEquals("LOGGED_OUT", sessions.lastStatus);
        assertEquals(1, revocations.size());
        assertEquals(peerId, revocations.entriesAfter(0).get(0).requesterPeerId());
    }

    @Test
    void shouldReplacePreviousOnlineSessionWhenSamePeerReconnects() throws Exception {
        FakeUserRepository users = new FakeUserRepository();
        FakePeerRepository peers = new FakePeerRepository();
        FakeSessionRepository sessions = new FakeSessionRepository();
        PasswordService passwords = new BCryptPasswordService(4);

        users.user = new UserRecord(
                1L,
                "dang",
                passwords.hash("secret"),
                AccountStatus.ACTIVE,
                OffsetDateTime.now()
        );

        DefaultAuthService service = new DefaultAuthService(
                users,
                peers,
                sessions,
                passwords,
                10
        );

        String peerId = UUID.randomUUID().toString();
        String publicKey = PeerRequestProof.encodePublicKey(PeerRequestProof.generateKeyPair().getPublic());
        LoginRequest request = new LoginRequest(
                "dang",
                "secret",
                7001,
                peerId,
                "TEST-PC",
                publicKey
        );

        LoginResponse first = service.login(request, "127.0.0.1");
        LoginResponse second = service.login(request, "127.0.0.1");

        assertNotEquals(first.sessionId(), second.sessionId());
        assertEquals(1, sessions.closedSessions);
        assertEquals(1, sessions.onlineSessionCount());
    }

    @Test
    void concurrentReconnectsShouldLeaveSingleOnlineSession() throws Exception {
        FakeUserRepository users = new FakeUserRepository();
        ConcurrentPeerRepository peers = new ConcurrentPeerRepository();
        ConcurrentSessionRepository sessions = new ConcurrentSessionRepository();
        PasswordService passwords = new BCryptPasswordService(4);

        users.user = new UserRecord(
                1L,
                "dang",
                passwords.hash("secret"),
                AccountStatus.ACTIVE,
                OffsetDateTime.now()
        );

        DefaultAuthService service = new DefaultAuthService(
                users, peers, sessions, passwords, 10
        );
        String peerId = UUID.randomUUID().toString();
        String publicKey = PeerRequestProof.encodePublicKey(PeerRequestProof.generateKeyPair().getPublic());
        LoginRequest request = new LoginRequest(
                "dang", "secret", 7001, peerId, "TEST-PC", publicKey
        );

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 12)
                    .mapToObj(i -> executor.submit(
                            () -> service.login(request, "127.0.0.1")
                    ))
                    .toList();
            for (Future<LoginResponse> future : futures) {
                assertNotNull(future.get().sessionId());
            }
            assertEquals(1, sessions.onlineSessionCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectWrongPassword() throws Exception {
        FakeUserRepository users = new FakeUserRepository();
        FakePeerRepository peers = new FakePeerRepository();
        FakeSessionRepository sessions = new FakeSessionRepository();
        PasswordService passwords = new BCryptPasswordService(4);

        users.user = new UserRecord(
                1L,
                "dang",
                passwords.hash("secret"),
                AccountStatus.ACTIVE,
                OffsetDateTime.now()
        );

        DefaultAuthService service = new DefaultAuthService(
                users,
                peers,
                sessions,
                passwords,
                10
        );

        AuthException error = assertThrows(
                AuthException.class,
                () -> service.login(
                        new LoginRequest(
                                "dang",
                                "wrong",
                                7001,
                                UUID.randomUUID().toString(),
                                "TEST-PC",
                                PeerRequestProof.encodePublicKey(
                                        PeerRequestProof.generateKeyPair().getPublic())
                        ),
                        "127.0.0.1"
                )
        );

        assertEquals("INVALID_CREDENTIALS", error.errorCode());
    }

    @Test
    void loginWithDifferentValidKeyOverwritesPreviouslyRegisteredKey() throws Exception {
        FakeUserRepository users = new FakeUserRepository();
        FakePeerRepository peers = new FakePeerRepository();
        FakeSessionRepository sessions = new FakeSessionRepository();
        PasswordService passwords = new BCryptPasswordService(4);
        users.user = new UserRecord(1L, "dang", passwords.hash("secret"),
                AccountStatus.ACTIVE, OffsetDateTime.now());
        DefaultAuthService service = new DefaultAuthService(users, peers, sessions, passwords, 10);
        String peerId = UUID.randomUUID().toString();
        KeyPair first = PeerRequestProof.generateKeyPair();
        KeyPair second = PeerRequestProof.generateKeyPair();

        service.login(new LoginRequest("dang", "secret", 7001, peerId, "TEST-PC",
                PeerRequestProof.encodePublicKey(first.getPublic())), "127.0.0.1");
        service.login(new LoginRequest("dang", "secret", 7001, peerId, "TEST-PC",
                PeerRequestProof.encodePublicKey(second.getPublic())), "127.0.0.1");

        assertEquals(PeerRequestProof.encodePublicKey(second.getPublic()),
                peers.publicKeys.get(UUID.fromString(peerId)));
    }

    @Test
    void rejectsMissingMalformedAndNonEd25519PublicKeys() throws Exception {
        FakeUserRepository users = new FakeUserRepository();
        FakePeerRepository peers = new FakePeerRepository();
        FakeSessionRepository sessions = new FakeSessionRepository();
        PasswordService passwords = new BCryptPasswordService(4);
        users.user = new UserRecord(1L, "dang", passwords.hash("secret"),
                AccountStatus.ACTIVE, OffsetDateTime.now());
        DefaultAuthService service = new DefaultAuthService(users, peers, sessions, passwords, 10);
        String peerId = UUID.randomUUID().toString();

        AuthException missing = assertThrows(AuthException.class,
                () -> service.login(new LoginRequest("dang", "secret", 7001, peerId, "TEST-PC", null),
                        "127.0.0.1"));
        AuthException malformed = assertThrows(AuthException.class,
                () -> service.login(new LoginRequest("dang", "secret", 7001, peerId, "TEST-PC", "not-a-key"),
                        "127.0.0.1"));
        KeyPair rsaKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String rsaPublicKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rsaKeys.getPublic().getEncoded());
        AuthException wrongAlgorithm = assertThrows(AuthException.class,
                () -> service.login(new LoginRequest("dang", "secret", 7001, peerId, "TEST-PC",
                                rsaPublicKey),
                        "127.0.0.1"));

        assertEquals("INVALID_PEER_PUBLIC_KEY", missing.errorCode());
        assertEquals("INVALID_PEER_PUBLIC_KEY", malformed.errorCode());
        assertEquals("INVALID_PEER_PUBLIC_KEY", wrongAlgorithm.errorCode());
        assertNull(peers.publicKeys.get(UUID.fromString(peerId)));
    }

    private static final class FakeUserRepository implements UserRepository {
        UserRecord user;

        @Override
        public Optional<UserRecord> findByUsername(String username) {
            if (user != null && user.username().equalsIgnoreCase(username)) {
                return Optional.of(user);
            }
            return Optional.empty();
        }

        @Override
        public UserRecord create(String username, String passwordHash) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakePeerRepository implements PeerRepository {
        final Map<UUID, PeerRecord> peers = new HashMap<>();
        final Map<UUID, String> publicKeys = new HashMap<>();

        @Override
        public Optional<PeerRecord> findById(UUID peerId) {
            return Optional.ofNullable(peers.get(peerId));
        }

        @Override
        public void create(PeerRecord peer) {
            peers.put(peer.peerId(), peer);
        }

        @Override
        public void updateLastLogin(UUID peerId, OffsetDateTime lastLoginAt) {
            PeerRecord old = peers.get(peerId);
            peers.put(peerId, new PeerRecord(
                    old.peerId(),
                    old.userId(),
                    old.deviceName(),
                    old.createdAt(),
                    lastLoginAt
            ));
        }

        @Override
        public Optional<String> findPublicKey(UUID peerId) {
            return Optional.ofNullable(publicKeys.get(peerId));
        }

        @Override
        public void updatePublicKey(UUID peerId, String publicKey) {
            publicKeys.put(peerId, publicKey);
        }
    }

    private static final class ConcurrentPeerRepository implements PeerRepository {
        private final ConcurrentHashMap<UUID, PeerRecord> peers =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, String> publicKeys = new ConcurrentHashMap<>();

        @Override
        public Optional<PeerRecord> findById(UUID peerId) {
            return Optional.ofNullable(peers.get(peerId));
        }

        @Override
        public void create(PeerRecord peer) throws SQLException {
            PeerRecord previous = peers.putIfAbsent(peer.peerId(), peer);
            if (previous != null) {
                SQLException duplicate = new SQLException("duplicate peer", "23505");
                throw duplicate;
            }
        }

        @Override
        public void updateLastLogin(UUID peerId, OffsetDateTime lastLoginAt) {
            peers.computeIfPresent(peerId, (id, old) -> new PeerRecord(
                    old.peerId(), old.userId(), old.deviceName(),
                    old.createdAt(), lastLoginAt
            ));
        }

        @Override
        public Optional<String> findPublicKey(UUID peerId) {
            return Optional.ofNullable(publicKeys.get(peerId));
        }

        @Override
        public void updatePublicKey(UUID peerId, String publicKey) {
            publicKeys.put(peerId, publicKey);
        }
    }

    private static final class ConcurrentSessionRepository
            implements PeerSessionRepository {
        private final Map<UUID, PeerSessionRecord> sessions = new HashMap<>();

        @Override
        public synchronized void create(PeerSessionRecord session) {
            sessions.put(session.sessionId(), session);
        }

        @Override
        public synchronized Optional<PeerSessionRecord> findBySessionId(UUID sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public synchronized int closeActiveForPeer(UUID peerId, OffsetDateTime closedAt) {
            return close(peerId, closedAt);
        }

        @Override
        public synchronized int replaceActiveForPeer(
                PeerSessionRecord newSession,
                OffsetDateTime closedAt
        ) {
            int replaced = close(newSession.peerId(), closedAt);
            sessions.put(newSession.sessionId(), newSession);
            return replaced;
        }

        private int close(UUID peerId, OffsetDateTime closedAt) {
            int changed = 0;
            for (Map.Entry<UUID, PeerSessionRecord> entry : sessions.entrySet()) {
                PeerSessionRecord current = entry.getValue();
                if (current.peerId().equals(peerId)
                        && current.status() == vn.edu.p2p.tracker.domain.PeerStatus.ONLINE) {
                    entry.setValue(new PeerSessionRecord(
                            current.sessionId(), current.peerId(), current.ipAddress(),
                            current.listeningPort(),
                            vn.edu.p2p.tracker.domain.PeerStatus.LOGGED_OUT,
                            current.loginAt(), current.lastSeen(), closedAt
                    ));
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public synchronized boolean updateLastSeen(UUID sessionId, OffsetDateTime lastSeen) {
            return sessions.containsKey(sessionId);
        }

        @Override
        public synchronized boolean markLoggedOut(UUID sessionId, OffsetDateTime logoutAt) {
            return sessions.containsKey(sessionId);
        }

        @Override
        public int expireStaleSessions(OffsetDateTime staleBefore) { return 0; }

        synchronized int onlineSessionCount() {
            return (int) sessions.values().stream()
                    .filter(session -> session.status()
                            == vn.edu.p2p.tracker.domain.PeerStatus.ONLINE)
                    .count();
        }
    }

    private static final class FakeSessionRepository implements PeerSessionRepository {
        final Map<UUID, PeerSessionRecord> sessions = new HashMap<>();
        String lastStatus;
        int closedSessions;

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
            int changed = 0;
            for (Map.Entry<UUID, PeerSessionRecord> entry : sessions.entrySet()) {
                PeerSessionRecord current = entry.getValue();
                if (current.peerId().equals(peerId)
                        && current.status() == vn.edu.p2p.tracker.domain.PeerStatus.ONLINE) {
                    entry.setValue(new PeerSessionRecord(
                            current.sessionId(),
                            current.peerId(),
                            current.ipAddress(),
                            current.listeningPort(),
                            vn.edu.p2p.tracker.domain.PeerStatus.LOGGED_OUT,
                            current.loginAt(),
                            current.lastSeen(),
                            closedAt
                    ));
                    changed++;
                }
            }
            closedSessions += changed;
            return changed;
        }

        @Override
        public boolean updateLastSeen(UUID sessionId, OffsetDateTime lastSeen) {
            return sessions.containsKey(sessionId);
        }

        @Override
        public boolean markLoggedOut(UUID sessionId, OffsetDateTime logoutAt) {
            PeerSessionRecord current = sessions.get(sessionId);
            if (current == null
                    || current.status() != vn.edu.p2p.tracker.domain.PeerStatus.ONLINE) {
                return false;
            }
            sessions.put(sessionId, new PeerSessionRecord(
                    current.sessionId(),
                    current.peerId(),
                    current.ipAddress(),
                    current.listeningPort(),
                    vn.edu.p2p.tracker.domain.PeerStatus.LOGGED_OUT,
                    current.loginAt(),
                    current.lastSeen(),
                    logoutAt
            ));
            lastStatus = "LOGGED_OUT";
            return true;
        }

        int onlineSessionCount() {
            return (int) sessions.values().stream()
                    .filter(session ->
                            session.status()
                                    == vn.edu.p2p.tracker.domain.PeerStatus.ONLINE)
                    .count();
        }

        @Override
        public int expireStaleSessions(OffsetDateTime staleBefore) {
            return 0;
        }
    }
}
