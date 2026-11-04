package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.common.security.RequestReplayGuard;
import vn.edu.p2p.peer.auth.TicketRevocationList;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceTicketRefreshIntegrationTest {
    private static final long FILE_ID = 8_201L;
    private static final String REQUESTER_ID = "refresh-requester";
    private static final String UPLOADER_ID = "refresh-uploader";
    private static final Instant START = Instant.parse("2026-11-03T08:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void refreshesBeforeNextPieceAndCompletesWithoutRedownloadingPieces() throws Exception {
        byte[] content = new byte[24];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 3);
        Path seed = tempDir.resolve("seed.bin");
        Files.write(seed, content);
        FileMetadata metadata = new FileMetadataBuilder(3).build(seed);
        MutableClock clock = new MutableClock(START);
        KeyPair trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair requesterKeys = PeerRequestProof.generateKeyPair();

        LocalFileRegistry seedRegistry = new LocalFileRegistry();
        seedRegistry.registerCompleteFile(FILE_ID, seed, metadata);
        PeerP2pSocketFactory sockets =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        PeerUploadServer server = new PeerUploadServer(0, seedRegistry, sockets, 2,
                trackerKeys.getPublic(), false, clock,
                new RequestReplayGuard(clock, Duration.ofSeconds(60), 100),
                new TicketRevocationList(clock));
        server.setUploaderPeerId(UPLOADER_ID);
        server.start();
        try (server) {
            String initialTicket = PeerAccessTickets.issue(trackerKeys.getPrivate(),
                    PeerAccessTickets.DEFAULT_KEY_ID, FILE_ID, REQUESTER_ID, UPLOADER_ID,
                    requesterKeys.getPublic(),
                    clock.instant(), 65);
            FileSourceInfo source = new FileSourceInfo(UPLOADER_ID, "COMPLETE", "127.0.0.1",
                    server.listeningPort(), clock.millis(), initialTicket);
            LocalFileRegistry downloadRegistry = new LocalFileRegistry();
            AtomicInteger refreshes = new AtomicInteger();
            TicketProvider provider = (fileId, oldSource) -> {
                refreshes.incrementAndGet();
                return PeerAccessTickets.issue(trackerKeys.getPrivate(),
                        PeerAccessTickets.DEFAULT_KEY_ID, FILE_ID, REQUESTER_ID, UPLOADER_ID,
                        requesterKeys.getPublic(),
                        clock.instant(), 900);
            };
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(downloadRegistry,
                    new PeerDownloadClient(sockets, requesterKeys.getPrivate(), clock),
                    1, PieceScheduler.Strategy.RANKED, provider, clock, Duration.ofSeconds(60));

            DownloadSessionResult result = engine.downloadFile(FILE_ID, List.of(source),
                    tempDir.resolve("received.bin"), new DownloadProgressListener() {
                        @Override
                        public void onProgress(long fileId, int completedPieces, int totalPieces,
                                               long bytesReceived, long totalBytes, double speed) {
                            clock.advance(Duration.ofSeconds(10));
                        }
                    }, new AtomicBoolean(false), java.util.UUID.randomUUID(), metadata.fileHash());

            assertEquals("COMPLETED", result.status(), result.failureReason());
            assertEquals(FileHashUtil.sha256(seed),
                    FileHashUtil.sha256(tempDir.resolve("received.bin")));
            assertTrue(refreshes.get() >= 1);
            assertEquals(content.length, server.totalBytesUploaded());
        }
    }

    @Test
    void failedRefreshReturnsIncompleteWithoutSendingOldTicketAgain() throws Exception {
        byte[] content = new byte[]{1, 2, 3};
        Path seed = tempDir.resolve("seed-failed-refresh.bin");
        Files.write(seed, content);
        FileMetadata metadata = new FileMetadataBuilder(3).build(seed);
        MutableClock clock = new MutableClock(START);
        KeyPair trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair requesterKeys = PeerRequestProof.generateKeyPair();
        LocalFileRegistry seedRegistry = new LocalFileRegistry();
        seedRegistry.registerCompleteFile(FILE_ID, seed, metadata);
        PeerP2pSocketFactory sockets =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        PeerUploadServer server = new PeerUploadServer(0, seedRegistry, sockets, 1,
                trackerKeys.getPublic(), false, clock,
                new RequestReplayGuard(clock, Duration.ofSeconds(60), 8),
                new TicketRevocationList(clock));
        server.setUploaderPeerId(UPLOADER_ID);
        server.start();
        try (server) {
            FileSourceInfo source = new FileSourceInfo(UPLOADER_ID, "COMPLETE", "127.0.0.1",
                    server.listeningPort(), clock.millis(), "unparseable-ticket");
            AtomicInteger refreshAttempts = new AtomicInteger();
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    new LocalFileRegistry(),
                    new PeerDownloadClient(sockets, requesterKeys.getPrivate(), clock),
                    1, PieceScheduler.Strategy.RANKED,
                    (fileId, oldSource) -> {
                        refreshAttempts.incrementAndGet();
                        throw new IOException("Tracker session expired");
                    }, clock, Duration.ofSeconds(60));

            DownloadSessionResult result = engine.downloadFile(FILE_ID, List.of(source),
                    tempDir.resolve("not-downloaded.bin"), null, new AtomicBoolean(false),
                    java.util.UUID.randomUUID(), metadata.fileHash());

            assertEquals("FAILED", result.status());
            assertTrue(result.failureReason().startsWith("Download incomplete:"));
            assertTrue(result.failureReason().contains("ticket refresh failed"));
            assertEquals(1, refreshAttempts.get());
            assertEquals(0L, server.totalBytesUploaded());
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(Instant initial) {
            millis = initial.toEpochMilli();
        }

        synchronized void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override public synchronized Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized long millis() { return millis; }
    }
}
