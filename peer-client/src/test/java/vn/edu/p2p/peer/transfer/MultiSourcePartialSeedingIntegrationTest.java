
package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MultiSourcePartialSeedingIntegrationTest {
    private static final long FILE_ID = 8_221L;
    private static final int PIECE_SIZE = 256;
    private static final int PIECE_COUNT = 8;
    private static final int VERIFIED_PIECES_WHILE_PAUSED = 3;
    private static final String PEER_A_ID = "partial-seed-a";
    private static final String PEER_B_ID = "partial-seed-b";

    @TempDir
    Path tempDir;

    @Test
    void uploadingPeerServesOnlyHashVerifiedPiecesWhileDownloadIsPaused() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            byte[] content = new byte[PIECE_SIZE * PIECE_COUNT];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i * 31 + 17);
            }
            Path sourcePath = tempDir.resolve("source-a.bin");
            Path targetPath = tempDir.resolve("partial-b.bin");
            Files.write(sourcePath, content);
            byte[] sentinel = new byte[content.length];
            java.util.Arrays.fill(sentinel, (byte) 0x5a);
            Files.write(targetPath, sentinel);

            FileMetadata metadata = new FileMetadataBuilder(PIECE_SIZE).build(sourcePath);
            KeyPair trackerKeys = PeerRequestProof.generateKeyPair();
            KeyPair requesterB = PeerRequestProof.generateKeyPair();
            KeyPair requesterC = PeerRequestProof.generateKeyPair();
            PeerP2pSocketFactory socketFactory =
                    new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());

            LocalFileRegistry registryA = new LocalFileRegistry();
            registryA.registerCompleteFile(FILE_ID, sourcePath, metadata);
            PeerUploadServer serverA = new PeerUploadServer(
                    0, registryA, socketFactory, 2, trackerKeys.getPublic(), false);
            serverA.setUploaderPeerId(PEER_A_ID);
            serverA.start();

            LocalFileRegistry registryB = new LocalFileRegistry();
            PeerUploadServer serverB = new PeerUploadServer(
                    0, registryB, socketFactory, 2, trackerKeys.getPublic(), false);
            serverB.setUploaderPeerId(PEER_B_ID);
            serverB.start();

            ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
            CountDownLatch bPausedAtThreePieces = new CountDownLatch(1);
            CountDownLatch resumeB = new CountDownLatch(1);
            try (serverA; serverB) {
                String ticketForB = issueTicket(trackerKeys, FILE_ID, PEER_B_ID,
                        PEER_A_ID, requesterB);
                FileSourceInfo sourceA = new FileSourceInfo(PEER_A_ID, "COMPLETE",
                        "127.0.0.1", serverA.listeningPort(), System.currentTimeMillis(),
                        ticketForB);
                MultiSourceDownloadEngine engineB = new MultiSourceDownloadEngine(
                        registryB,
                        new PeerDownloadClient(socketFactory, requesterB.getPrivate()),
                        1,
                        PieceScheduler.Strategy.RANKED
                );
                Future<DownloadSessionResult> downloadB = downloadExecutor.submit(() ->
                        engineB.downloadFile(FILE_ID, List.of(sourceA), targetPath,
                                (fileId, completedPieces, totalPieces, bytesReceived,
                                 totalBytes, speed) -> {
                                    if (completedPieces == VERIFIED_PIECES_WHILE_PAUSED) {
                                        bPausedAtThreePieces.countDown();
                                        try {
                                            if (!resumeB.await(10, TimeUnit.SECONDS)) {
                                                throw new IllegalStateException(
                                                        "Timed out waiting to resume Peer B");
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(
                                                    "Interrupted while Peer B was paused", e);
                                        }
                                    }
                                }, new AtomicBoolean(false)));

                try {
                    assertTrue(bPausedAtThreePieces.await(10, TimeUnit.SECONDS),
                            "Peer B did not pause after exactly three verified pieces");
                    LocalFileRegistry.RegisteredFile bRegistered =
                            registryB.find(FILE_ID).orElseThrow();
                    assertEquals(VERIFIED_PIECES_WHILE_PAUSED,
                            bRegistered.completedPieceCount());
                    Path registeredTargetPath = bRegistered.filePath();
                    assertNotEquals(targetPath, registeredTargetPath,
                            "An unrelated existing file must not be used as the partial download");

                    String ticketForC = issueTicket(trackerKeys, FILE_ID, "partial-reader-c",
                            PEER_B_ID, requesterC);
                    PeerDownloadClient clientC = new PeerDownloadClient(
                            socketFactory, requesterC.getPrivate());

                    var partialBitfield = clientC.fetchBitfield("127.0.0.1",
                            serverB.listeningPort(), PEER_B_ID, FILE_ID, ticketForC);
                    BitSet expectedPartial = new BitSet(PIECE_COUNT);
                    expectedPartial.set(0, VERIFIED_PIECES_WHILE_PAUSED);
                    assertEquals(expectedPartial, partialBitfield.toBitSet());

                    var verifiedPiece = clientC.downloadPiece("127.0.0.1",
                            serverB.listeningPort(), PEER_B_ID, FILE_ID, 1, ticketForC);
                    assertArrayEquals(piece(content, 1), verifiedPiece.data());
                    assertEquals(metadata.pieces().get(1).pieceHash(),
                            FileHashUtil.sha256(verifiedPiece.data(), 0, verifiedPiece.data().length));

                    long bytesBeforeUnavailableRequest = serverB.totalBytesUploaded();
                    assertThrows(IOException.class, () -> clientC.downloadPiece("127.0.0.1",
                            serverB.listeningPort(), PEER_B_ID, FILE_ID,
                            VERIFIED_PIECES_WHILE_PAUSED, ticketForC));
                    assertEquals(bytesBeforeUnavailableRequest, serverB.totalBytesUploaded(),
                            "An unverified piece must not send any binary bytes");
                    assertArrayEquals(new byte[PIECE_SIZE],
                            piece(Files.readAllBytes(registeredTargetPath),
                                    VERIFIED_PIECES_WHILE_PAUSED),
                            "The unavailable piece region must remain unchanged");
                    assertArrayEquals(sentinel, Files.readAllBytes(targetPath),
                            "The original same-name file must remain untouched");

                    resumeB.countDown();
                    DownloadSessionResult resultB = downloadB.get(10, TimeUnit.SECONDS);
                    assertEquals("COMPLETED", resultB.status(), resultB.failureReason());
                    Path completedTargetPath = resultB.outputFile();
                    assertEquals(registeredTargetPath, completedTargetPath);
                    assertEquals(FileHashUtil.sha256(sourcePath),
                            FileHashUtil.sha256(completedTargetPath));

                    var completeBitfield = clientC.fetchBitfield("127.0.0.1",
                            serverB.listeningPort(), PEER_B_ID, FILE_ID, ticketForC);
                    assertEquals(PIECE_COUNT, completeBitfield.toBitSet().cardinality());
                    for (int pieceIndex = VERIFIED_PIECES_WHILE_PAUSED;
                         pieceIndex < PIECE_COUNT; pieceIndex++) {
                        var downloaded = clientC.downloadPiece("127.0.0.1",
                                serverB.listeningPort(), PEER_B_ID, FILE_ID, pieceIndex, ticketForC);
                        assertArrayEquals(piece(content, pieceIndex), downloaded.data());
                        assertEquals(metadata.pieces().get(pieceIndex).pieceHash(),
                                FileHashUtil.sha256(downloaded.data(), 0, downloaded.data().length));
                    }

                    int corruptedPieceIndex = PIECE_COUNT - 1;
                    try (RandomAccessFile file = new RandomAccessFile(
                            completedTargetPath.toFile(), "rw")) {
                        long offset = (long) corruptedPieceIndex * PIECE_SIZE;
                        file.seek(offset);
                        file.writeByte(content[(int) offset] ^ 0xff);
                    }
                    long bytesBeforeCorruptRequest = serverB.totalBytesUploaded();
                    assertThrows(IOException.class, () -> clientC.downloadPiece("127.0.0.1",
                            serverB.listeningPort(), PEER_B_ID, FILE_ID,
                            corruptedPieceIndex, ticketForC));
                    assertEquals(bytesBeforeCorruptRequest, serverB.totalBytesUploaded(),
                            "A piece that fails the upload-time hash check must not be sent");
                    assertFalse(clientC.fetchBitfield("127.0.0.1", serverB.listeningPort(),
                            PEER_B_ID, FILE_ID, ticketForC).toBitSet().get(corruptedPieceIndex),
                            "The corrupted piece must be removed from the advertised bitfield");
                } finally {
                    resumeB.countDown();
                }
            } finally {
                resumeB.countDown();
                downloadExecutor.shutdownNow();
                downloadExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        });
    }

    private static String issueTicket(KeyPair trackerKeys, long fileId, String requesterId,
                                      String uploaderId, KeyPair requesterKeys) {
        return PeerAccessTickets.issue(trackerKeys.getPrivate(), fileId, requesterId, uploaderId,
                requesterKeys.getPublic(), Instant.now());
    }

    private static byte[] piece(byte[] file, int pieceIndex) {
        return java.util.Arrays.copyOfRange(file, pieceIndex * PIECE_SIZE,
                (pieceIndex + 1) * PIECE_SIZE);
    }
}
