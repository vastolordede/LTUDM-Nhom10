package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiSourceDownloadResumeIntegrationTest {
    private static final long FILE_ID = 902L;
    private static final int PIECE_SIZE = 64 * 1024;
    private static final String PEER_ID = "resume-seed";

    @TempDir
    Path tempDir;

    @Test
    void resumesOnlyPiecesWithNoVerifiedContentAndVerifiesWholeFileHash() throws Exception {
        ScenarioResult result = runResumeScenario(
                "resume-first-pieces.bin",
                bitSetOf(0, 1),
                -1
        );

        assertEquals(3, result.session().sourceStats().getFirst().piecesReceived());
        assertEquals(result.bytesForPieces(2, 5), result.serverBytesUploaded());
        assertCompletedAndHasExpectedHash(result);
    }

    @Test
    void redownloadsExistingPieceWhenItsContentHashIsInvalid() throws Exception {
        BitSet initiallyGood = new BitSet();
        initiallyGood.set(0, 5);
        ScenarioResult result = runResumeScenario(
                "resume-corrupt-piece.bin",
                initiallyGood,
                2
        );

        assertEquals(1, result.session().sourceStats().getFirst().piecesReceived());
        assertEquals(result.metadata().pieces().get(2).pieceLength(), result.serverBytesUploaded());
        assertCompletedAndHasExpectedHash(result);
    }

    private ScenarioResult runResumeScenario(
            String targetName,
            BitSet initiallyGood,
            int corruptPiece
    ) throws Exception {
        Path sourceFile = tempDir.resolve("source-" + targetName);
        byte[] content = new byte[PIECE_SIZE * 4 + 19];
        new Random(7331).nextBytes(content);
        Files.write(sourceFile, content);
        FileMetadata metadata = new FileMetadataBuilder(PIECE_SIZE).build(sourceFile);
        assertEquals(5, metadata.pieceCount());

        LocalFileRegistry seedRegistry = new LocalFileRegistry();
        seedRegistry.registerCompleteFile(FILE_ID, sourceFile, metadata);
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        var ticketKeys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path targetFile = tempDir.resolve(targetName);
        writeInterruptedDownload(targetFile, content, metadata, initiallyGood, corruptPiece);
        // Simulate an interrupted transfer from this application, not an arbitrary user file.
        // The engine creates this marker before writing the first piece in production.
        ResumeOwnershipMarker.claim(targetFile, metadata);
        Path oldAppImage = tempDir.resolve("old-app-image");
        Files.createDirectories(oldAppImage);
        Files.writeString(oldAppImage.resolve("application.bin"), "old application image");
        try (var paths = Files.walk(oldAppImage)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        if (!Files.isRegularFile(targetFile)) {
            throw new AssertionError("Partial download must remain outside the replaced app image");
        }

        try (PeerUploadServer seed = new PeerUploadServer(
                0, seedRegistry, socketFactory, 2, ticketKeys.getPublic(), false)) {
            seed.setUploaderPeerId(PEER_ID);
            seed.start();
            String ticket = PeerTestTickets.issue(
                    ticketKeys.getPrivate(), FILE_ID, "resume-reader", PEER_ID, Instant.now());
            FileSourceInfo source = new FileSourceInfo(
                    PEER_ID, "COMPLETE", "127.0.0.1", seed.listeningPort(),
                    System.currentTimeMillis(), ticket);
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    new LocalFileRegistry(),
                    new PeerDownloadClient(socketFactory, PeerTestTickets.requesterPrivateKey()),
                    3,
                    PieceScheduler.Strategy.ROUND_ROBIN
            );

            DownloadSessionResult session = engine.downloadFile(
                    FILE_ID,
                    List.of(source),
                    targetFile,
                    null,
                    new AtomicBoolean(false),
                    java.util.UUID.randomUUID(),
                    metadata.fileHash()
            );
            return new ScenarioResult(session, metadata, targetFile, seed.totalBytesUploaded());
        }
    }

    private static void writeInterruptedDownload(
            Path targetFile,
            byte[] content,
            FileMetadata metadata,
            BitSet initiallyGood,
            int corruptPiece
    ) throws Exception {
        byte[] interrupted = new byte[content.length];
        for (int i = 0; i < interrupted.length; i++) {
            interrupted[i] = (byte) (content[i] ^ 0x5A);
        }
        for (int piece = initiallyGood.nextSetBit(0);
             piece >= 0;
             piece = initiallyGood.nextSetBit(piece + 1)) {
            int offset = piece * metadata.pieceSize();
            int length = metadata.pieces().get(piece).pieceLength();
            System.arraycopy(content, offset, interrupted, offset, length);
        }
        if (corruptPiece >= 0) {
            int offset = corruptPiece * metadata.pieceSize();
            interrupted[offset] ^= (byte) 0xFF;
        }
        Files.write(targetFile, interrupted);
    }

    private static BitSet bitSetOf(int... pieces) {
        BitSet bitSet = new BitSet();
        for (int piece : pieces) {
            bitSet.set(piece);
        }
        return bitSet;
    }

    private static void assertCompletedAndHasExpectedHash(ScenarioResult result) throws Exception {
        assertEquals("COMPLETED", result.session().status());
        assertEquals(result.metadata().fileSize(), result.session().bytesReceived());
        assertEquals(result.metadata().fileHash(), FileHashUtil.sha256(result.targetFile()));
        org.junit.jupiter.api.Assertions.assertFalse(
                ResumeOwnershipMarker.matches(result.targetFile(), result.metadata()),
                "A successful transfer must remove its durable resume marker");
    }

    private record ScenarioResult(
            DownloadSessionResult session,
            FileMetadata metadata,
            Path targetFile,
            long serverBytesUploaded
    ) {
        private long bytesForPieces(int firstPiece, int endExclusive) {
            return metadata.pieces().subList(firstPiece, endExclusive).stream()
                    .mapToLong(piece -> piece.pieceLength())
                    .sum();
        }
    }
}
