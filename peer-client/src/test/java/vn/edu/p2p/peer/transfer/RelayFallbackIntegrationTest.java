package vn.edu.p2p.peer.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.security.RequestReplayGuard;
import vn.edu.p2p.peer.network.RendezvousClient;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;
import vn.edu.p2p.peer.security.TlsTestCertificates;
import vn.edu.p2p.peer.security.TlsTestCertificates.Material;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;
import vn.edu.p2p.tracker.catalog.SessionPeerResolver;
import vn.edu.p2p.tracker.network.RendezvousServer;
import vn.edu.p2p.tracker.network.TrackerServerSocketProvider;

class RelayFallbackIntegrationTest {
    private static final long FILE_ID = 11_074L;
    private static final Instant FIXED_TIME = Instant.parse("2026-11-05T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void directFailureFallsBackToTlsRelayAndTransfersWholeFile() throws Exception {
        Environment env = Environment.start(tempDir);
        try {
            byte[] content = new byte[64 * 1024 * 3 + 73];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i * 19 + 7);
            }
            Path sourcePath = tempDir.resolve("relay-source.bin");
            Files.write(sourcePath, content);
            FileMetadata metadata = new FileMetadataBuilder(64 * 1024).build(sourcePath);
            LocalFileRegistry seedRegistry = new LocalFileRegistry();
            seedRegistry.registerCompleteFile(FILE_ID, sourcePath, metadata);
            env.startUploader(seedRegistry);
            env.startRelayAgent();
            await(() -> env.isRegistered(), Duration.ofSeconds(3));

            String ticket = PeerTestTickets.issue(env.trackerKeys.getPrivate(), FILE_ID,
                    env.downloaderSession.toString(), env.targetPeer.toString(), FIXED_TIME);
            FileSourceInfo source = env.source(unusedPort(), ticket);
            ConnectionPlanner planner = env.newPlanner(env.peerSockets, env.downloaderSession);
            ConnectionPlanner.ConnectivityStatusModel connectivity =
                    new ConnectionPlanner.ConnectivityStatusModel();
            planner.setConnectivityStatus(connectivity);
            planner.remember(List.of(source));
            AtomicReference<ConnectionPlanner.Mode> selectedMode = new AtomicReference<>();
            planner.setListener((peerId, mode) -> selectedMode.set(mode));

            LocalFileRegistry destination = new LocalFileRegistry();
            PeerDownloadClient downloadClient = env.newDownloadClient(planner);
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(destination,
                    downloadClient, 2, PieceScheduler.Strategy.RANKED, null,
                    FIXED_CLOCK, Duration.ofSeconds(60));
            DownloadSessionResult result = engine.downloadFile(FILE_ID, List.of(source),
                    tempDir.resolve("relay-received.bin"), null,
                    new java.util.concurrent.atomic.AtomicBoolean(false),
                    UUID.randomUUID(), metadata.fileHash());

            assertEquals("COMPLETED", result.status(), result.failureReason());
            assertEquals(ConnectionPlanner.Mode.RELAY, selectedMode.get());
            assertEquals(ConnectionPlanner.ConnectivityStatusModel.RelayState.READY,
                    connectivity.snapshot().relay());
            assertEquals(metadata.fileHash(), FileHashUtil.sha256(result.outputFile()));
            assertArrayEquals(content, Files.readAllBytes(result.outputFile()));
            assertEquals(0, activeSocketCount(downloadClient));
            assertEventuallyEmpty(activeSockets(env.uploader), Duration.ofSeconds(2));
            assertRelayStateClean(env.rendezvousServer, env.initialRelaySlots);

            env.relayAgent.close();
            assertTrue(env.relayAgent.loopTerminated());
            assertTrue(env.relayAgent.acceptorsTerminated());
        } finally {
            env.close();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unresponsiveRelayTargetTimesOutAndReleasesPendingState() throws Exception {
        Environment env = Environment.start(tempDir);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Socket control = env.downloaderRendezvous.register(env.targetSession.toString());
            try {
                ConnectionPlanner planner = env.newPlanner(env.peerSockets, env.downloaderSession);
                FileSourceInfo source = env.source(unusedPort(), "unused-valid-shape-ticket");
                planner.remember(List.of(source));
                Future<Socket> attempt = caller.submit(
                        () -> planner.open(source.ipAddress(), source.listeningPort()));

                await(() -> pendingCount(env.rendezvousServer) == 1, Duration.ofSeconds(2));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> attempt.get(12, TimeUnit.SECONDS));
                IOException relayFailure = assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(containsThrowableMessage(relayFailure, "Target peer did not answer in time"),
                        relayFailure.toString());
                assertRelayStateClean(env.rendezvousServer, env.initialRelaySlots);

                int probePort = unusedPort();
                env.downloaderRendezvous.probe(env.downloaderSession.toString(), probePort);
                Map<UUID, Long> probes = lastProbeEntries(env.rendezvousServer);
                assertTrue(probes.containsKey(env.downloaderPeer));
                probes.put(env.downloaderPeer, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(6));
                invoke(env.rendezvousServer, "pingControls");
                assertFalse(probes.containsKey(env.downloaderPeer),
                        "Expired probe rate-limit entries should be evicted during the ping sweep");
            } finally {
                control.close();
            }
        } finally {
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
            env.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void tlsAndAccessDeniedFailuresDoNotStartRelay() throws Exception {
        Environment env = Environment.start(tempDir);
        try {
            Path seedPath = tempDir.resolve("direct-tls-source.bin");
            byte[] content = new byte[4096];
            Files.write(seedPath, content);
            FileMetadata metadata = new FileMetadataBuilder(1024).build(seedPath);
            LocalFileRegistry registry = new LocalFileRegistry();
            registry.registerCompleteFile(FILE_ID, seedPath, metadata);
            env.startUploader(registry);
            env.startRelayAgent();
            await(() -> env.isRegistered(), Duration.ofSeconds(3));

            String validTicket = PeerTestTickets.issue(env.trackerKeys.getPrivate(), FILE_ID,
                    env.downloaderSession.toString(), env.targetPeer.toString(), FIXED_TIME);
            FileSourceInfo validSource = env.source(env.uploader.listeningPort(), validTicket);

            Material unrelated = TlsTestCertificates.createUnrelatedTrustStore(tempDir);
            PeerP2pSocketFactory untrustedPeerSockets = new PeerP2pSocketFactory(
                    peerConfig(env.material, unrelated));
            ConnectionPlanner tlsPlanner = env.newPlanner(untrustedPeerSockets, env.downloaderSession);
            ConnectionPlanner.ConnectivityStatusModel tlsConnectivity =
                    new ConnectionPlanner.ConnectivityStatusModel();
            tlsPlanner.setConnectivityStatus(tlsConnectivity);
            tlsPlanner.remember(List.of(validSource));
            IOException tlsFailure = assertThrows(IOException.class,
                    () -> tlsPlanner.open(validSource.ipAddress(), validSource.listeningPort()));
            assertTrue(hasCause(tlsFailure, SSLException.class), tlsFailure.toString());
            assertEquals(ConnectionPlanner.ConnectivityStatusModel.RelayState.DISABLED,
                    tlsConnectivity.snapshot().relay());
            assertRelayStateClean(env.rendezvousServer, env.initialRelaySlots);

            ConnectionPlanner ticketPlanner = env.newPlanner(env.peerSockets, env.downloaderSession);
            ticketPlanner.remember(List.of(validSource));
            PeerDownloadClient client = env.newDownloadClient(ticketPlanner);
            IOException denied = assertThrows(IOException.class,
                    () -> client.fetchBitfield(validSource.ipAddress(), validSource.listeningPort(),
                            validSource.peerId(), FILE_ID, "invalid-or-expired-ticket"));
            assertTrue(denied.getMessage().contains("ACCESS_DENIED"), denied.getMessage());
            assertEquals(ConnectionPlanner.Mode.LAN,
                    ticketPlannerMode(ticketPlanner, validSource));
            assertRelayStateClean(env.rendezvousServer, env.initialRelaySlots);
        } finally {
            env.close();
        }
    }

    private static ConnectionPlanner.Mode ticketPlannerMode(
            ConnectionPlanner planner, FileSourceInfo source) throws Exception {
        Object mode = field(planner, "lastMode", Map.class)
                .get(source.ipAddress() + ":" + source.listeningPort());
        return assertInstanceOf(ConnectionPlanner.Mode.class, mode);
    }

    private static boolean containsThrowableMessage(Throwable error, String expected) {
        if (error.getMessage() != null && error.getMessage().contains(expected)) return true;
        for (Throwable suppressed : error.getSuppressed()) {
            if (containsThrowableMessage(suppressed, expected)) return true;
        }
        return error.getCause() != null && containsThrowableMessage(error.getCause(), expected);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    private static int activeSocketCount(PeerDownloadClient client) throws Exception {
        return field(client, "activeSockets", java.util.Set.class).size();
    }

    private static java.util.Set<?> activeSockets(PeerUploadServer server) throws Exception {
        return field(server, "activeSockets", java.util.Set.class);
    }

    private static void assertRelayStateClean(RendezvousServer server, int initialSlots) throws Exception {
        assertEquals(0, pendingCount(server), "Relay pending session leaked");
        assertEquals(initialSlots, field(server, "relaySlots", java.util.concurrent.Semaphore.class)
                .availablePermits(), "Relay slot was not released");
    }

    private static int pendingCount(RendezvousServer server) throws Exception {
        return field(server, "pending", Map.class).size();
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Long> lastProbeEntries(RendezvousServer server) throws Exception {
        return (Map<UUID, Long>) field(server, "lastProbe", Map.class);
    }

    private static void assertEventuallyEmpty(java.util.Set<?> values, Duration timeout) throws Exception {
        await(values::isEmpty, timeout);
    }

    private static void await(CheckedCondition condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean getAsBoolean() throws Exception;
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static PeerP2pSocketConfig peerConfig(Material key, Material trust) {
        return new PeerP2pSocketConfig(true, key.keyStore(), key.password(), "PKCS12",
            trust.trustStore(), trust.password(), "PKCS12", 700, 12_000);
    }

    private static final class Environment implements AutoCloseable {
        private final Material material;
        private final PeerP2pSocketFactory peerSockets;
        private final KeyPair trackerKeys;
        private final UUID targetPeer = UUID.randomUUID();
        private final UUID targetSession = UUID.randomUUID();
        private final UUID downloaderPeer = UUID.randomUUID();
        private final UUID downloaderSession = UUID.randomUUID();
        private final RendezvousServer rendezvousServer;
        private final Thread trackerThread;
        private final int trackerPort;
        private final RendezvousClient targetRendezvous;
        private final RendezvousClient downloaderRendezvous;
        private final int initialRelaySlots;
        private PeerUploadServer uploader;
        private RelayAgent relayAgent;

        private Environment(Path directory) throws Exception {
            material = TlsTestCertificates.create(directory, "IP:127.0.0.1", null);
            peerSockets = new PeerP2pSocketFactory(peerConfig(material, material));
            trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            trackerPort = unusedPort();
            Map<UUID, UUID> sessions = Map.of(
                    targetSession, targetPeer,
                    downloaderSession, downloaderPeer);
            SessionPeerResolver resolver = sessionId -> {
                UUID peerId = sessions.get(sessionId);
                if (peerId == null) throw new SQLException("Unknown test session");
                return peerId;
            };
            SSLContext serverTls = TlsTestCertificates.serverContext(material);
            TrackerServerSocketProvider tlsProvider = port -> {
                SSLServerSocket socket = (SSLServerSocket) serverTls.getServerSocketFactory()
                        .createServerSocket(port);
                socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                return socket;
            };
            rendezvousServer = new RendezvousServer(trackerPort, resolver, true, tlsProvider);
            trackerThread = new Thread(() -> {
                try {
                    rendezvousServer.start();
                } catch (IOException e) {
                    throw new IllegalStateException("Rendezvous test server failed", e);
                }
            }, "test-rendezvous-server");
            trackerThread.setDaemon(true);
            trackerThread.start();
            awaitPort(trackerPort);

            TrackerConnectionConfig trackerConfig = new TrackerConnectionConfig(
                    "127.0.0.1", trackerPort, true, material.trustStore(),
                    material.password(), "PKCS12");
            targetRendezvous = new RendezvousClient(trackerConfig, trackerPort);
            downloaderRendezvous = new RendezvousClient(trackerConfig, trackerPort);
            initialRelaySlots = field(rendezvousServer, "relaySlots",
                    java.util.concurrent.Semaphore.class).availablePermits();
        }

        static Environment start(Path directory) throws Exception {
            return new Environment(directory);
        }

        private static void awaitPort(int port) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    return;
                } catch (IOException notReady) {
                    Thread.sleep(10);
                }
            }
            throw new AssertionError("Rendezvous server did not bind its dynamic port");
        }

        private void startUploader(LocalFileRegistry registry) throws Exception {
            uploader = new PeerUploadServer(0, registry, peerSockets, 2,
                    trackerKeys.getPublic(), false, FIXED_CLOCK,
                    new RequestReplayGuard(FIXED_CLOCK, Duration.ofMinutes(2), 128),
                    new vn.edu.p2p.peer.auth.TicketRevocationList(FIXED_CLOCK));
            uploader.setUploaderPeerId(targetPeer.toString());
            uploader.start();
        }

        private void startRelayAgent() {
            relayAgent = new RelayAgent(targetRendezvous, targetSession.toString(), uploader);
            relayAgent.start();
        }

        private boolean isRegistered() throws Exception {
            return field(rendezvousServer, "controls", Map.class).containsKey(targetPeer);
        }

        private FileSourceInfo source(int port, String ticket) {
            return new FileSourceInfo(targetPeer.toString(), "COMPLETE", "127.0.0.1",
                    port, FIXED_TIME.toEpochMilli(), ticket);
        }

        private ConnectionPlanner newPlanner(PeerP2pSocketFactory sockets, UUID session) {
            return new ConnectionPlanner(sockets, downloaderRendezvous, session.toString());
        }

        private PeerDownloadClient newDownloadClient(ConnectionPlanner planner) {
            return new PeerDownloadClient(peerSockets,
                    PeerTestTickets.requesterPrivateKey(), FIXED_CLOCK).withPlanner(planner);
        }

        @Override
        public void close() {
            if (relayAgent != null) relayAgent.close();
            if (uploader != null) uploader.close();
            targetRendezvous.close();
            downloaderRendezvous.close();
            rendezvousServer.close();
            try {
                trackerThread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
