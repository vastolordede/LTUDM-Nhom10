package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceDownloadFailoverIntegrationTest {
    private static final long FILE_ID = 923L;
    private static final int PIECE_SIZE = 64 * 1024;
    private static final int PIECE_COUNT = 8;
    private static final int K = 2;
    private static final String PEER_A = "failover-seed-a";
    private static final String PEER_B = "failover-seed-b";
    private static final byte SENTINEL = (byte) 0xA5;

    @TempDir
    Path tempDir;

    @Test
    void completesThroughSeedBAfterSeedAClosesAfterKPieces() throws Exception {
        Scenario scenario = createScenario("two-seed-failover.bin");
        try (Seeds seeds = createSeeds(scenario, true)) {
            LocalFileRegistry downloader = new LocalFileRegistry();
            MultiSourceDownloadEngine engine = engine(downloader);
            AtomicReference<PeerUploadServer> seedToStop = new AtomicReference<>(seeds.a());
            DownloadProgressListener stopSeedAAfterK = (fileId, completed, total, bytes, size, speed) -> {
                if (seeds.a().totalBytesUploaded() == bytesForFirstPieces(scenario.metadata(), K)
                        && seedToStop.compareAndSet(seeds.a(), null)) {
                    seeds.a().close();
                }
            };

            DownloadSessionResult result = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    engine.downloadFile(FILE_ID, seeds.sources(), scenario.target(),
                            stopSeedAAfterK, new AtomicBoolean(false)));

            assertEquals("COMPLETED", result.status());
            assertEquals(scenario.metadata().pieceCount(), result.sourceStats().stream()
                    .mapToInt(DownloadSessionResult.SourceTransferStat::piecesReceived).sum());
            assertEquals(K, piecesReceivedFrom(result, PEER_A));
            assertEquals(PIECE_COUNT - K, piecesReceivedFrom(result, PEER_B));
            assertEquals(bytesForFirstPieces(scenario.metadata(), K), seeds.a().totalBytesUploaded());
            assertEquals(bytesForPieces(scenario.metadata(), K, PIECE_COUNT),
                    seeds.b().totalBytesUploaded());
            assertEquals(scenario.metadata().fileHash(), FileHashUtil.sha256(scenario.target()));
            assertTrue(downloader.find(FILE_ID).orElseThrow().isComplete());
        }
    }

    @Test
    void failsClearlyAndPreservesOnlyDeliveredPiecesWhenOnlySeedDies() throws Exception {
        Scenario scenario = createScenario("single-seed-failure.bin");
        try (Seeds seeds = createSeeds(scenario, false)) {
            PeerUploadServer seed = seeds.a();
            MultiSourceDownloadEngine engine = engine(new LocalFileRegistry());
            DownloadProgressListener stopAfterK = (fileId, completed, total, bytes, size, speed) -> {
                if (seed.totalBytesUploaded() == bytesForFirstPieces(scenario.metadata(), K)) {
                    seed.close();
                }
            };

            DownloadSessionResult result = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    engine.downloadFile(FILE_ID, seeds.sources(), scenario.target(),
                            stopAfterK, new AtomicBoolean(false)));

            assertEquals("FAILED", result.status());
            assertTrue(result.failureReason().contains("incomplete"));
            assertEquals(K, piecesReceivedFrom(result, PEER_A));
            assertEquals(bytesForFirstPieces(scenario.metadata(), K), seed.totalBytesUploaded());
            assertArrayEquals(expectedPartialFile(scenario.content(), scenario.metadata(), K),
                    Files.readAllBytes(scenario.target()));
        }
    }

    private Scenario createScenario(String targetName) throws Exception {
        Path sourceFile = tempDir.resolve("source-" + targetName);
        byte[] content = new byte[PIECE_SIZE * (PIECE_COUNT - 1) + 37];
        new Random(23023).nextBytes(content);
        Files.write(sourceFile, content);
        FileMetadata metadata = new FileMetadataBuilder(PIECE_SIZE).build(sourceFile);
        assertEquals(PIECE_COUNT, metadata.pieceCount());
        Path target = tempDir.resolve(targetName);
        byte[] interrupted = new byte[content.length];
        Arrays.fill(interrupted, SENTINEL);
        Files.write(target, interrupted);
        return new Scenario(content, metadata, sourceFile, target);
    }

    private Seeds createSeeds(Scenario scenario, boolean includeB) throws Exception {
        LocalFileRegistry registryA = new LocalFileRegistry();
        registryA.registerCompleteFile(FILE_ID, scenario.source(), scenario.metadata());
        LocalFileRegistry registryB = new LocalFileRegistry();
        registryB.registerCompleteFile(FILE_ID, scenario.source(), scenario.metadata());
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerUploadServer seedA = new PeerUploadServer(0, registryA, socketFactory, 2,
                keys.getPublic(), false);
        PeerUploadServer seedB = new PeerUploadServer(0, registryB, socketFactory, 2,
                keys.getPublic(), false);
        seedA.setUploaderPeerId(PEER_A);
        seedB.setUploaderPeerId(PEER_B);
        seedA.start();
        if (includeB) {
            seedB.start();
        }
        String ticketA = PeerTestTickets.issue(
                keys.getPrivate(), FILE_ID, "failover-reader", PEER_A, Instant.now());
        FileSourceInfo sourceA = new FileSourceInfo(PEER_A, "COMPLETE", "127.0.0.1",
                seedA.listeningPort(), System.currentTimeMillis(), ticketA);
        if (!includeB) {
            return new Seeds(seedA, null, List.of(sourceA));
        }
        String ticketB = PeerTestTickets.issue(
                keys.getPrivate(), FILE_ID, "failover-reader", PEER_B, Instant.now());
        FileSourceInfo sourceB = new FileSourceInfo(PEER_B, "COMPLETE", "127.0.0.1",
                seedB.listeningPort(), System.currentTimeMillis(), ticketB);
        return new Seeds(seedA, seedB, List.of(sourceA, sourceB));
    }

    private static MultiSourceDownloadEngine engine(LocalFileRegistry registry) {
        return new MultiSourceDownloadEngine(registry,
                new PeerDownloadClient(new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp())),
                1,
                PieceScheduler.Strategy.ROUND_ROBIN);
    }

    private static int piecesReceivedFrom(DownloadSessionResult result, String peerId) {
        return result.sourceStats().stream()
                .filter(stat -> peerId.equals(stat.sourcePeerId()))
                .mapToInt(DownloadSessionResult.SourceTransferStat::piecesReceived)
                .sum();
    }

    private static long bytesForFirstPieces(FileMetadata metadata, int count) {
        return bytesForPieces(metadata, 0, count);
    }

    private static long bytesForPieces(FileMetadata metadata, int first, int endExclusive) {
        return metadata.pieces().subList(first, endExclusive).stream()
                .mapToLong(piece -> piece.pieceLength())
                .sum();
    }

    private static byte[] expectedPartialFile(byte[] source, FileMetadata metadata, int deliveredCount) {
        byte[] expected = new byte[source.length];
        Arrays.fill(expected, SENTINEL);
        for (int pieceIndex = 0; pieceIndex < deliveredCount; pieceIndex++) {
            int offset = pieceIndex * metadata.pieceSize();
            int length = metadata.pieces().get(pieceIndex).pieceLength();
            System.arraycopy(source, offset, expected, offset, length);
        }
        return expected;
    }

    private record Scenario(byte[] content, FileMetadata metadata, Path source, Path target) {
    }

    private record Seeds(PeerUploadServer a, PeerUploadServer b, List<FileSourceInfo> sources)
            implements AutoCloseable {
        @Override
        public void close() {
            a.close();
            if (b != null) {
                b.close();
            }
        }
    }
}
