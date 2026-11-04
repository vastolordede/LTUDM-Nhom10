package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                            stopSeedAAfterK, new AtomicBoolean(false), java.util.UUID.randomUUID(),
                            scenario.metadata().fileHash()));

            assertEquals("COMPLETED", result.status());
            assertEquals(scenario.metadata().pieceCount(), result.sourceStats().stream()
                    .mapToInt(DownloadSessionResult.SourceTransferStat::piecesReceived).sum());
            assertEquals(K, piecesReceivedFrom(result, PEER_A));
            assertEquals(PIECE_COUNT - K, piecesReceivedFrom(result, PEER_B));
            assertEquals(bytesForFirstPieces(scenario.metadata(), K), seeds.a().totalBytesUploaded());
            assertEquals(bytesForPieces(scenario.metadata(), K, PIECE_COUNT),
                    seeds.b().totalBytesUploaded());
            assertEquals(scenario.metadata().fileHash(), FileHashUtil.sha256(result.outputFile()));
            assertArrayEquals(expectedPartialFile(scenario.content(), scenario.metadata(), 0),
                    Files.readAllBytes(scenario.target()),
                    "Original destination should remain unchanged; selected=" + result.outputFile());
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
                            stopAfterK, new AtomicBoolean(false), java.util.UUID.randomUUID(),
                            scenario.metadata().fileHash()));

            assertEquals("FAILED", result.status());
            assertTrue(result.failureReason().contains("incomplete"));
            assertEquals(K, piecesReceivedFrom(result, PEER_A));
            assertEquals(bytesForFirstPieces(scenario.metadata(), K), seed.totalBytesUploaded());
            assertNotEquals(scenario.target(), result.outputFile());
            assertArrayEquals(expectedPartialFile(scenario.content(), scenario.metadata(), K,
                            (byte) 0),
                    Files.readAllBytes(result.outputFile()));
            assertArrayEquals(expectedPartialFile(scenario.content(), scenario.metadata(), 0),
                    Files.readAllBytes(scenario.target()));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, unit = TimeUnit.SECONDS)
    void trustedCatalogHashAcceptsAliasAndRejectsMismatchWithoutWritingTarget() throws Exception {
        Scenario scenario = createScenario("catalog-alias.bin");
        try (Seeds seeds = createSeeds(scenario, false)) {
            MultiSourceDownloadEngine engine = engine(new LocalFileRegistry());
            SearchResultItem aliasSearchResult = new SearchResultItem(
                    FILE_ID, "catalog-visible-alias.bin", scenario.metadata().fileSize(),
                    scenario.metadata().fileHash(), 1);
            assertEquals("catalog-visible-alias.bin", aliasSearchResult.fileName());
            Path acceptedTarget = tempDir.resolve("accepted-alias.bin");
            DownloadSessionResult accepted = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    engine.downloadFile(FILE_ID, seeds.sources(), acceptedTarget, null,
                            new AtomicBoolean(false), java.util.UUID.randomUUID(),
                            aliasSearchResult.fileHash()));

            assertEquals("COMPLETED", accepted.status());
            assertEquals(scenario.metadata().fileHash(), FileHashUtil.sha256(accepted.outputFile()));
            assertTrue(Files.exists(accepted.outputFile()));

            Path rejectedTarget = tempDir.resolve("rejected-alias.bin");
            String wrongHash = (scenario.metadata().fileHash().charAt(0) == '0' ? "1" : "0")
                    + scenario.metadata().fileHash().substring(1);
            DownloadSessionResult rejected = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    engine.downloadFile(FILE_ID, seeds.sources(), rejectedTarget, null,
                            new AtomicBoolean(false), java.util.UUID.randomUUID(), wrongHash));

            assertEquals("FAILED", rejected.status());
            assertFalse(Files.exists(rejectedTarget),
                    "A catalog hash mismatch must not leave a file at the requested destination");
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 2, unit = TimeUnit.SECONDS)
    void missingTrustedCatalogHashIsRejected() {
        long downloadFileOverloads = java.util.Arrays.stream(
                        MultiSourceDownloadEngine.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("downloadFile"))
                .count();
        assertEquals(1, downloadFileOverloads,
                "Only the trusted-catalog-hash download entry point should be available");

        MultiSourceDownloadEngine engine = engine(new LocalFileRegistry());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.downloadFile(FILE_ID, List.of(), tempDir.resolve("missing-hash.bin"),
                        null, new AtomicBoolean(false), java.util.UUID.randomUUID(), null));
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
                new PeerDownloadClient(new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp()),
                        PeerTestTickets.requesterPrivateKey()),
                1,
                PieceScheduler.Strategy.ROUND_ROBIN, null,
                Clock.fixed(Instant.now(), ZoneOffset.UTC), Duration.ofSeconds(60));
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
        return expectedPartialFile(source, metadata, deliveredCount, SENTINEL);
    }

    private static byte[] expectedPartialFile(byte[] source, FileMetadata metadata,
                                              int deliveredCount, byte initialValue) {
        byte[] expected = new byte[source.length];
        Arrays.fill(expected, initialValue);
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
