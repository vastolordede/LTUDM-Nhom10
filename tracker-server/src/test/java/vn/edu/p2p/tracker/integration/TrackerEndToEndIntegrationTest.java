package vn.edu.p2p.tracker.integration;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.FileSourcesRequest;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.tracker.auth.BCryptPasswordService;
import vn.edu.p2p.tracker.auth.DefaultAuthService;
import vn.edu.p2p.tracker.auth.PasswordService;
import vn.edu.p2p.tracker.domain.AccountStatus;
import vn.edu.p2p.tracker.domain.FileSourceRecord;
import vn.edu.p2p.tracker.domain.PeerRecord;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.domain.UserRecord;
import vn.edu.p2p.tracker.network.TrackerRequestDispatcher;
import vn.edu.p2p.tracker.network.TrackerServer;
import vn.edu.p2p.tracker.network.TrackerServerSocketProvider;
import vn.edu.p2p.tracker.peer.PeerSessionService;
import vn.edu.p2p.tracker.repository.FileSourceRepository;
import vn.edu.p2p.tracker.repository.PeerRepository;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;
import vn.edu.p2p.tracker.repository.UserRepository;
import vn.edu.p2p.tracker.source.FileSourceService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D48 - Full Tracker integration test.
 *
 * This test intentionally uses a real TCP TrackerServer + ProtocolCodec framing,
 * while replacing PostgreSQL with thread-safe in-memory repositories so the test
 * remains deterministic and does not require local DB credentials/certificates.
 *
 * Covered flow:
 * Login -> Heartbeat -> File Source API -> Reconnect -> old-session rejection
 * -> new-session Heartbeat -> Logout -> logged-out-session rejection.
 *
 * TLS + real PostgreSQL remain covered by the manual smoke test because local
 * keystores, truststores and DB credentials are intentionally not committed.
 */
class TrackerEndToEndIntegrationTest {

    @Test
    void shouldCompleteFullTrackerControlFlowOverTcp() throws Exception {
        PasswordService passwords = new BCryptPasswordService(4);

        InMemoryUserRepository users = new InMemoryUserRepository(
                new UserRecord(
                        1L,
                        "dang",
                        passwords.hash("123456"),
                        AccountStatus.ACTIVE,
                        OffsetDateTime.now(ZoneOffset.UTC)
                )
        );
        InMemoryPeerRepository peers = new InMemoryPeerRepository();
        InMemoryPeerSessionRepository sessions = new InMemoryPeerSessionRepository();

        UUID sourcePeerId = UUID.randomUUID();
        FileSourceRepository fileSources = new FixedFileSourceRepository(
                new FileSourceRecord(
                        101L,
                        sourcePeerId,
                        "COMPLETE",
                        "192.168.1.50",
                        7101,
                        OffsetDateTime.now(ZoneOffset.UTC)
                )
        );

        DefaultAuthService authService = new DefaultAuthService(
                users,
                peers,
                sessions,
                passwords,
                10
        );
        PeerSessionService peerSessionService = new PeerSessionService(sessions);
        FileSourceService fileSourceService = new FileSourceService(
                fileSources,
                peerSessionService
        );
        TrackerRequestDispatcher dispatcher = new TrackerRequestDispatcher(
                authService,
                peerSessionService,
                fileSourceService
        );

        int port = findFreePort();
        TrackerServer server = new TrackerServer(
                port,
                dispatcher,
                4,
                16,
                5_000,
                TrackerServerSocketProvider.plain(),
                "TCP-TEST"
        );

        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Thread serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Throwable error) {
                serverFailure.set(error);
            }
        }, "tracker-integration-server");
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            awaitServer(port, serverFailure);

            try (Socket incompatibleClient = connect(port)) {
                MessageEnvelope incompatibleResponse = roundTrip(
                        incompatibleClient,
                        new MessageEnvelope(
                                MessageEnvelope.CURRENT_VERSION + 1,
                                MessageType.HEARTBEAT,
                                "unsupported-version",
                                null,
                                null,
                                null,
                                null
                        )
                );
                assertEquals(ResponseStatus.ERROR, incompatibleResponse.status());
                assertEquals("UNSUPPORTED_PROTOCOL_VERSION", incompatibleResponse.errorCode());
            }

            UUID peerId = UUID.randomUUID();

            try (Socket firstClient = connect(port)) {
                LoginResponse firstLogin = login(
                        firstClient,
                        "login-1",
                        peerId,
                        7001
                );

                assertEquals(peerId.toString(), firstLogin.peerId());
                assertEquals(10, firstLogin.heartbeatIntervalSeconds());
                assertNotNull(firstLogin.sessionId());
                assertEquals(1, sessions.onlineSessionCount());

                MessageEnvelope heartbeat = roundTrip(
                        firstClient,
                        MessageEnvelope.request(
                                MessageType.HEARTBEAT,
                                "heartbeat-1",
                                ProtocolCodec.toPayload(
                                        new HeartbeatRequest(firstLogin.sessionId())
                                )
                        )
                );
                assertSuccess(heartbeat, MessageType.HEARTBEAT_ACK);
                HeartbeatResponse heartbeatPayload = ProtocolCodec.fromPayload(
                        heartbeat.payload(),
                        HeartbeatResponse.class
                );
                assertTrue(heartbeatPayload.serverTimeEpochMillis() > 0L);

                MessageEnvelope sourceResponse = roundTrip(
                        firstClient,
                        MessageEnvelope.request(
                                MessageType.FILE_SOURCES_REQUEST,
                                "sources-1",
                                ProtocolCodec.toPayload(
                                        new FileSourcesRequest(
                                                firstLogin.sessionId(),
                                                101L
                                        )
                                )
                        )
                );
                assertSuccess(
                        sourceResponse,
                        MessageType.FILE_SOURCES_RESPONSE
                );

                FileSourcesResponse sourcesPayload = ProtocolCodec.fromPayload(
                        sourceResponse.payload(),
                        FileSourcesResponse.class
                );
                assertEquals(101L, sourcesPayload.fileId());
                assertEquals("a".repeat(64), sourcesPayload.fileHash());
                assertEquals(1, sourcesPayload.sources().size());

                FileSourceInfo source = sourcesPayload.sources().getFirst();
                assertEquals(sourcePeerId.toString(), source.peerId());
                assertEquals("COMPLETE", source.availabilityStatus());
                assertEquals("192.168.1.50", source.ipAddress());
                assertEquals(7101, source.listeningPort());

                try (Socket secondClient = connect(port)) {
                    LoginResponse secondLogin = login(
                            secondClient,
                            "login-2",
                            peerId,
                            7002
                    );

                    assertNotEquals(
                            firstLogin.sessionId(),
                            secondLogin.sessionId()
                    );
                    assertEquals(1, sessions.onlineSessionCount());
                    assertFalse(sessions.isOnline(firstLogin.sessionId()));
                    assertTrue(sessions.isOnline(secondLogin.sessionId()));

                    MessageEnvelope oldHeartbeat = roundTrip(
                            firstClient,
                            MessageEnvelope.request(
                                    MessageType.HEARTBEAT,
                                    "old-heartbeat",
                                    ProtocolCodec.toPayload(
                                            new HeartbeatRequest(
                                                    firstLogin.sessionId()
                                            )
                                    )
                            )
                    );
                    assertEquals(ResponseStatus.ERROR, oldHeartbeat.status());
                    assertEquals(
                            "SESSION_NOT_ONLINE",
                            oldHeartbeat.errorCode()
                    );

                    MessageEnvelope newHeartbeat = roundTrip(
                            secondClient,
                            MessageEnvelope.request(
                                    MessageType.HEARTBEAT,
                                    "heartbeat-2",
                                    ProtocolCodec.toPayload(
                                            new HeartbeatRequest(
                                                    secondLogin.sessionId()
                                            )
                                    )
                            )
                    );
                    assertSuccess(newHeartbeat, MessageType.HEARTBEAT_ACK);

                    MessageEnvelope logout = roundTrip(
                            secondClient,
                            MessageEnvelope.request(
                                    MessageType.LOGOUT_REQUEST,
                                    "logout-1",
                                    ProtocolCodec.toPayload(
                                            new LogoutRequest(
                                                    secondLogin.sessionId()
                                            )
                                    )
                            )
                    );
                    assertSuccess(logout, MessageType.LOGOUT_RESPONSE);
                    assertEquals(0, sessions.onlineSessionCount());

                    MessageEnvelope postLogoutHeartbeat = roundTrip(
                            secondClient,
                            MessageEnvelope.request(
                                    MessageType.HEARTBEAT,
                                    "heartbeat-after-logout",
                                    ProtocolCodec.toPayload(
                                            new HeartbeatRequest(
                                                    secondLogin.sessionId()
                                            )
                                    )
                            )
                    );
                    assertEquals(
                            ResponseStatus.ERROR,
                            postLogoutHeartbeat.status()
                    );
                    assertEquals(
                            "SESSION_NOT_ONLINE",
                            postLogoutHeartbeat.errorCode()
                    );
                }
            }
        } finally {
            server.close();
            serverThread.join(2_000);
        }

        assertFalse(serverThread.isAlive());
        assertNull(serverFailure.get());
    }

    private static LoginResponse login(
            Socket socket,
            String requestId,
            UUID peerId,
            int listeningPort
    ) throws Exception {
        MessageEnvelope response = roundTrip(
                socket,
                MessageEnvelope.request(
                        MessageType.LOGIN_REQUEST,
                        requestId,
                        ProtocolCodec.toPayload(
                                new LoginRequest(
                                        "dang",
                                        "123456",
                                        listeningPort,
                                        peerId.toString(),
                                        "INTEGRATION-TEST"
                                )
                        )
                )
        );

        assertSuccess(response, MessageType.LOGIN_RESPONSE);
        return ProtocolCodec.fromPayload(
                response.payload(),
                LoginResponse.class
        );
    }

    private static MessageEnvelope roundTrip(
            Socket socket,
            MessageEnvelope request
    ) throws IOException {
        ProtocolCodec.write(socket.getOutputStream(), request);
        MessageEnvelope response = ProtocolCodec.read(socket.getInputStream());

        assertEquals(request.requestId(), response.requestId());
        return response;
    }

    private static void assertSuccess(
            MessageEnvelope response,
            MessageType expectedType
    ) {
        assertEquals(expectedType, response.type());
        assertEquals(ResponseStatus.SUCCESS, response.status());
        assertNull(response.errorCode());
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(
                new InetSocketAddress("127.0.0.1", port),
                2_000
        );
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitServer(
            int port,
            AtomicReference<Throwable> serverFailure
    ) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;

        while (System.nanoTime() < deadline) {
            Throwable failure = serverFailure.get();
            if (failure != null) {
                throw new AssertionError(
                        "Tracker test server failed to start",
                        failure
                );
            }

            try (Socket ignored = connect(port)) {
                return;
            } catch (IOException ignored) {
                Thread.sleep(25L);
            }
        }

        throw new AssertionError(
                "Tracker test server did not become ready in time"
        );
    }

    private static final class InMemoryUserRepository
            implements UserRepository {
        private final UserRecord user;

        private InMemoryUserRepository(UserRecord user) {
            this.user = user;
        }

        @Override
        public Optional<UserRecord> findByUsername(String username) {
            if (user.username().equalsIgnoreCase(username)) {
                return Optional.of(user);
            }
            return Optional.empty();
        }

        @Override
        public UserRecord create(String username, String passwordHash) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InMemoryPeerRepository
            implements PeerRepository {
        private final Map<UUID, PeerRecord> peers =
                new ConcurrentHashMap<>();

        @Override
        public Optional<PeerRecord> findById(UUID peerId) {
            return Optional.ofNullable(peers.get(peerId));
        }

        @Override
        public void create(PeerRecord peer) throws SQLException {
            PeerRecord previous = peers.putIfAbsent(peer.peerId(), peer);
            if (previous != null) {
                throw new SQLException("duplicate peer", "23505");
            }
        }

        @Override
        public void updateLastLogin(
                UUID peerId,
                OffsetDateTime lastLoginAt
        ) {
            peers.computeIfPresent(peerId, (id, old) -> new PeerRecord(
                    old.peerId(),
                    old.userId(),
                    old.deviceName(),
                    old.createdAt(),
                    lastLoginAt
            ));
        }
    }

    private static final class InMemoryPeerSessionRepository
            implements PeerSessionRepository {
        private final Map<UUID, PeerSessionRecord> sessions =
                new ConcurrentHashMap<>();

        @Override
        public synchronized void create(PeerSessionRecord session) {
            sessions.put(session.sessionId(), session);
        }

        @Override
        public synchronized Optional<PeerSessionRecord> findBySessionId(
                UUID sessionId
        ) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public synchronized int closeActiveForPeer(
                UUID peerId,
                OffsetDateTime closedAt
        ) {
            return closeOnlineForPeer(peerId, closedAt);
        }

        @Override
        public synchronized int replaceActiveForPeer(
                PeerSessionRecord newSession,
                OffsetDateTime closedAt
        ) {
            int replaced = closeOnlineForPeer(
                    newSession.peerId(),
                    closedAt
            );
            sessions.put(newSession.sessionId(), newSession);
            return replaced;
        }

        @Override
        public synchronized boolean updateLastSeen(
                UUID sessionId,
                OffsetDateTime lastSeen
        ) {
            PeerSessionRecord current = sessions.get(sessionId);
            if (current == null || current.status() != PeerStatus.ONLINE) {
                return false;
            }

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
        public synchronized boolean markLoggedOut(
                UUID sessionId,
                OffsetDateTime logoutAt
        ) {
            PeerSessionRecord current = sessions.get(sessionId);
            if (current == null || current.status() != PeerStatus.ONLINE) {
                return false;
            }

            sessions.put(sessionId, new PeerSessionRecord(
                    current.sessionId(),
                    current.peerId(),
                    current.ipAddress(),
                    current.listeningPort(),
                    PeerStatus.LOGGED_OUT,
                    current.loginAt(),
                    current.lastSeen(),
                    logoutAt
            ));
            return true;
        }

        @Override
        public synchronized int expireStaleSessions(
                OffsetDateTime staleBefore
        ) {
            int expired = 0;

            for (Map.Entry<UUID, PeerSessionRecord> entry
                    : new ArrayList<>(sessions.entrySet())) {
                PeerSessionRecord current = entry.getValue();

                if (current.status() == PeerStatus.ONLINE
                        && current.lastSeen().isBefore(staleBefore)) {
                    sessions.put(entry.getKey(), new PeerSessionRecord(
                            current.sessionId(),
                            current.peerId(),
                            current.ipAddress(),
                            current.listeningPort(),
                            PeerStatus.EXPIRED,
                            current.loginAt(),
                            current.lastSeen(),
                            staleBefore
                    ));
                    expired++;
                }
            }

            return expired;
        }

        synchronized int onlineSessionCount() {
            return (int) sessions.values()
                    .stream()
                    .filter(session ->
                            session.status() == PeerStatus.ONLINE
                    )
                    .count();
        }

        synchronized boolean isOnline(String rawSessionId) {
            PeerSessionRecord session = sessions.get(
                    UUID.fromString(rawSessionId)
            );
            return session != null
                    && session.status() == PeerStatus.ONLINE;
        }

        private int closeOnlineForPeer(
                UUID peerId,
                OffsetDateTime closedAt
        ) {
            int changed = 0;

            for (Map.Entry<UUID, PeerSessionRecord> entry
                    : new ArrayList<>(sessions.entrySet())) {
                PeerSessionRecord current = entry.getValue();

                if (current.peerId().equals(peerId)
                        && current.status() == PeerStatus.ONLINE) {
                    sessions.put(entry.getKey(), new PeerSessionRecord(
                            current.sessionId(),
                            current.peerId(),
                            current.ipAddress(),
                            current.listeningPort(),
                            PeerStatus.LOGGED_OUT,
                            current.loginAt(),
                            current.lastSeen(),
                            closedAt
                    ));
                    changed++;
                }
            }

            return changed;
        }
    }

    private static final class FixedFileSourceRepository
            implements FileSourceRepository {
        private final FileSourceRecord source;

        private FixedFileSourceRepository(FileSourceRecord source) {
            this.source = source;
        }

        @Override
        public Optional<String> findReadyCatalogHash(long fileId) {
            return fileId == source.fileId() ? Optional.of("a".repeat(64)) : Optional.empty();
        }

        @Override
        public List<FileSourceRecord> findOnlineSources(
                long fileId,
                UUID excludingPeerId
        ) {
            if (source.fileId() != fileId
                    || source.peerId().equals(excludingPeerId)) {
                return List.of();
            }
            return List.of(source);
        }
    }
}
