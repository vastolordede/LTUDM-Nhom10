package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.TicketRevocation;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;
import vn.edu.p2p.peer.network.TrackerConnectionDiagnostics;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthClientServiceLogoutTest {
    @TempDir
    Path tempDir;

    @Test
    void timeoutClearsSessionAndDropsCallbacksWithinDeadline() throws Exception {
        try (ServerSocket tracker = new ServerSocket(0)) {
            CountDownLatch logoutReceived = new CountDownLatch(1);
            CountDownLatch releaseTracker = new CountDownLatch(1);
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = tracker.accept()) {
                    respondToLogin(socket);
                    MessageEnvelope logout = ProtocolCodec.read(socket.getInputStream());
                    assertEquals(MessageType.LOGOUT_REQUEST, logout.type());
                    logoutReceived.countDown();
                    releaseTracker.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            AuthClientService service = service(tracker.getLocalPort(), "timed-out");
            try {
                service.login("alice", "password", 7401);
                TicketRevocationList revocations = service.ticketRevocations();
                revocations.apply(new vn.edu.p2p.common.dto.HeartbeatResponse(
                        Instant.now().toEpochMilli(),
                        List.of(new TicketRevocation(1, TicketRevocation.Type.REQUESTER,
                                "old-requester", null, 0, Instant.now().toEpochMilli())),
                        1L, "tracker-old"));
                long callbackGeneration = AuthClientService.sessionGeneration();
                AtomicBoolean staleCallbackRan = new AtomicBoolean();
                long started = System.nanoTime();

                assertFalse(service.logoutBestEffort(Duration.ofMillis(150)));

                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsedMillis < 1_000, "logout exceeded its bounded timeout");
                assertTrue(logoutReceived.await(1, TimeUnit.SECONDS));
                assertNull(service.currentSession());
                assertEquals(0L, revocations.latestSequence());
                assertNull(revocations.trackerInstanceId());
                Runnable staleCallback = () -> {
                    if (AuthClientService.isCurrentGeneration(callbackGeneration)) {
                        staleCallbackRan.set(true);
                    }
                };
                staleCallback.run();
                assertFalse(staleCallbackRan.get());
                assertTrue(service.logoutBestEffort(Duration.ofMillis(100)));
            } finally {
                releaseTracker.countDown();
                service.close();
            }
            backend.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void trackerErrorStillClearsSessionAndRepeatedLogoutIsHarmless() throws Exception {
        try (ServerSocket tracker = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = tracker.accept()) {
                    respondToLogin(socket);
                    MessageEnvelope logout = ProtocolCodec.read(socket.getInputStream());
                    ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.error(
                            MessageType.LOGOUT_RESPONSE, logout.requestId(),
                            "SESSION_NOT_ONLINE", "already expired"));
                    MessageEnvelope secondLogin = ProtocolCodec.read(socket.getInputStream());
                    ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.success(
                            MessageType.LOGIN_RESPONSE, secondLogin.requestId(),
                            ProtocolCodec.toPayload(new LoginResponse(UUID.randomUUID().toString(),
                                    UUID.randomUUID().toString(), 30))));
                    MessageEnvelope heartbeat = ProtocolCodec.read(socket.getInputStream());
                    HeartbeatRequest heartbeatRequest = ProtocolCodec.fromPayload(
                            heartbeat.payload(), HeartbeatRequest.class);
                    assertEquals(0L, heartbeatRequest.lastRevocationSeq());
                    assertNull(heartbeatRequest.lastTrackerInstanceId());
                    ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.success(
                            MessageType.HEARTBEAT_ACK, heartbeat.requestId(),
                            ProtocolCodec.toPayload(new HeartbeatResponse(
                                    Instant.now().toEpochMilli(), List.of(), 0L, "tracker-new"))));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            });

            AuthClientService service = service(tracker.getLocalPort(), "tracker-error");
            try {
                service.login("alice", "password", 7402);
                service.ticketRevocations().apply(new HeartbeatResponse(
                        Instant.now().toEpochMilli(),
                        List.of(new TicketRevocation(3, TicketRevocation.Type.REQUESTER,
                                "old-requester", null, 0, Instant.now().toEpochMilli())),
                        3L, "tracker-old"));
                assertFalse(service.logoutBestEffort(Duration.ofSeconds(1)));
                assertNull(service.currentSession());
                assertEquals(0L, service.ticketRevocations().latestSequence());
                assertNull(service.ticketRevocations().trackerInstanceId());
                assertTrue(service.logoutBestEffort(Duration.ofMillis(100)));
                service.login("bob", "password", 7402);
                service.heartbeat();
                assertEquals("tracker-new", service.ticketRevocations().trackerInstanceId());
            } finally {
                service.close();
            }
            backend.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void sameAuthServiceReconnectsAfterTrackerConfigurationIsCorrected() throws Exception {
        int closedPort;
        try (ServerSocket closed = new ServerSocket(0)) {
            closedPort = closed.getLocalPort();
        }
        TrackerConnection connection = new TrackerConnection(new TrackerConnectionConfig(
                "127.0.0.1", closedPort, false, null, null, null), 500, 300);
        PeerIdentityStore identities = new PeerIdentityStore(tempDir.resolve("reconnect.properties"));
        PeerIdentity identity = identities.loadOrCreate();
        AuthClientService service = new AuthClientService(connection, identities, identity,
                new PeerKeyStore(tempDir.resolve("reconnect-keys")), new TicketRevocationList());
        try {
            AuthClientException firstFailure = org.junit.jupiter.api.Assertions.assertThrows(
                    AuthClientException.class, () -> service.login("alice", "password", 7401));
            assertEquals(TrackerConnectionDiagnostics.FailureKind.CONNECTION_REFUSED,
                    TrackerConnectionDiagnostics.classify(firstFailure, connection.configuration()));
            assertNull(service.currentSession());

            try (ServerSocket tracker = new ServerSocket(0)) {
                CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                    try (Socket socket = tracker.accept()) {
                        respondToLogin(socket);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                connection.reconfigure(new TrackerConnectionConfig(
                        "127.0.0.1", tracker.getLocalPort(), false, null, null, null));
                var session = service.login("alice", "password", 7401);
                assertEquals(session, service.currentSession());
                backend.get(2, TimeUnit.SECONDS);
            }
        } finally {
            service.close();
        }
    }

    private AuthClientService service(int port, String keyDirectory) throws Exception {
        TrackerConnection connection = new TrackerConnection(new TrackerConnectionConfig(
                "127.0.0.1", port, false, null, null, null));
        PeerIdentityStore identities = new PeerIdentityStore(tempDir.resolve(keyDirectory + ".properties"));
        PeerIdentity identity = identities.loadOrCreate();
        return new AuthClientService(connection, identities, identity,
                new PeerKeyStore(tempDir.resolve(keyDirectory + "-keys")),
                new TicketRevocationList());
    }

    private static void respondToLogin(Socket socket) throws Exception {
        MessageEnvelope login = ProtocolCodec.read(socket.getInputStream());
        assertEquals(MessageType.LOGIN_REQUEST, login.type());
        ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.success(
                MessageType.LOGIN_RESPONSE, login.requestId(),
                ProtocolCodec.toPayload(new LoginResponse(UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), 30))));
    }
}
