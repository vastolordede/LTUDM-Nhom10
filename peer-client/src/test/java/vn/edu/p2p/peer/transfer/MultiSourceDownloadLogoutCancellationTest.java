package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.BitfieldResponse;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceDownloadLogoutCancellationTest {
    private static final long FILE_ID = 9901L;
    private static final String PEER_ID = "blocked-seed";

    @TempDir
    Path tempDir;

    @Test
    void cancellationClosesActivePeerSocketAndKeepsPartialFile() throws Exception {
        byte[] sourceBytes = new byte[256 * 1024];
        java.util.Arrays.fill(sourceBytes, (byte) 0x6A);
        Path seedFile = tempDir.resolve("seed.bin");
        Files.write(seedFile, sourceBytes);
        FileMetadata metadata = new FileMetadataBuilder(128 * 1024)
                .build(seedFile);

        byte[] partialBytes = new byte[sourceBytes.length];
        java.util.Arrays.fill(partialBytes, (byte) 0x2B);
        System.arraycopy(sourceBytes, 0, partialBytes, 0, 128 * 1024);
        Path target = tempDir.resolve("partial.bin");
        Files.write(target, partialBytes);

        CountDownLatch pieceRequested = new CountDownLatch(1);
        CountDownLatch releaseSeed = new CountDownLatch(1);
        try (ServerSocket seed = new ServerSocket(0)) {
            Thread seedThread = new Thread(() -> serveBitfieldThenBlock(
                    seed, metadata, pieceRequested, releaseSeed), "test-blocked-seed");
            seedThread.setDaemon(true);
            seedThread.start();

            PeerP2pSocketFactory sockets =
                    new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
            var trackerKeys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String ticket = PeerTestTickets.issue(trackerKeys.getPrivate(), FILE_ID,
                    "logout-reader", PEER_ID, Instant.now());
            PeerDownloadClient client = new PeerDownloadClient(
                    sockets, PeerTestTickets.requesterPrivateKey());
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    new LocalFileRegistry(), client, 2, PieceScheduler.Strategy.RANKED,
                    null, Clock.systemUTC(), Duration.ZERO);
            FileSourceInfo source = new FileSourceInfo(PEER_ID, "COMPLETE",
                    "127.0.0.1", seed.getLocalPort(), System.currentTimeMillis(), ticket);
            AtomicBoolean cancel = new AtomicBoolean();
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<DownloadSessionResult> result = caller.submit(() -> engine.downloadFile(
                        FILE_ID, List.of(source), target, null, cancel, UUID.randomUUID(),
                        metadata.fileHash()));

                boolean reachedPieceRequest = pieceRequested.await(2, TimeUnit.SECONDS);
                if (!reachedPieceRequest) {
                    DownloadSessionResult earlyResult = result.get(1, TimeUnit.SECONDS);
                    throw new AssertionError("download ended before the piece request: "
                            + earlyResult.status() + " / " + earlyResult.failureReason());
                }
                cancel.set(true);
                client.cancelActiveRequests();

                DownloadSessionResult cancelled = result.get(2, TimeUnit.SECONDS);
                assertEquals("CANCELLED", cancelled.status());
                assertFalse("COMPLETED".equals(cancelled.status()));
                assertTrue(Files.exists(cancelled.outputFile()));
                assertArrayEquals(partialBytes, Files.readAllBytes(cancelled.outputFile()));
                assertNoDownloadWorker(FILE_ID);
            } finally {
                cancel.set(true);
                client.cancelActiveRequests();
                releaseSeed.countDown();
                caller.shutdownNow();
                caller.awaitTermination(1, TimeUnit.SECONDS);
                seedThread.join(1_000);
            }
        }
    }

    private static void serveBitfieldThenBlock(
            ServerSocket seed,
            FileMetadata metadata,
            CountDownLatch pieceRequested,
            CountDownLatch releaseSeed
    ) {
        try {
            try (Socket bitfieldSocket = seed.accept()) {
                MessageEnvelope request = ProtocolCodec.read(bitfieldSocket.getInputStream());
                BitSet available = new BitSet(metadata.pieceCount());
                available.set(0, metadata.pieceCount());
                ProtocolCodec.write(bitfieldSocket.getOutputStream(), MessageEnvelope.success(
                        MessageType.BITFIELD_RESPONSE, request.requestId(),
                        ProtocolCodec.toPayload(new BitfieldResponse(FILE_ID, available.toByteArray(), metadata))));
            }
            try (Socket pieceSocket = seed.accept()) {
                MessageEnvelope request = ProtocolCodec.read(pieceSocket.getInputStream());
                assertEquals(MessageType.REQUEST_PIECE, request.type());
                pieceRequested.countDown();
                releaseSeed.await();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertNoDownloadWorker(long fileId) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        do {
            boolean alive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive()
                            && thread.getName().equals("peer-download-worker-" + fileId));
            if (!alive) return;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive()
                        && thread.getName().equals("peer-download-worker-" + fileId)));
    }
}
