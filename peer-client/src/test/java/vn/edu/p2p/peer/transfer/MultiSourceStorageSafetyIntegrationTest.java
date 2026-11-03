package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceStorageSafetyIntegrationTest {
    private static final long FILE_ID = 8_225L;
    private static final int PIECE_SIZE = 256;
    private static final int PIECE_COUNT = 4;
    private static final String UPLOADER_ID = "storage-source";
    private static final String REQUESTER_ID = "storage-requester";

    @TempDir
    Path tempDir;

    @Test
    void differentContentAtSameNameIsPreservedAndDownloadUsesSuffix() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            TransferFixture fixture = createFixture("collision-source.bin");
            try (PeerUploadServer server = fixture.server()) {
                Path target = tempDir.resolve("collision.bin");
                byte[] original = new byte[PIECE_SIZE * PIECE_COUNT];
                Arrays.fill(original, (byte) 0x5a);
                Files.write(target, original);

                DownloadSessionResult result = download(fixture, target, null);

                assertEquals("COMPLETED", result.status(), result.failureReason());
                assertNotEquals(target, result.outputFile());
                assertEquals("collision (1).bin", result.outputFile().getFileName().toString());
                assertArrayEquals(original, Files.readAllBytes(target),
                        "A different file with the same name must remain byte-for-byte unchanged");
                assertEquals(FileHashUtil.sha256(fixture.sourcePath()),
                        FileHashUtil.sha256(result.outputFile()));
                assertNoTemporaryFiles();
            }
        });
    }

    @Test
    void validPartialFileAtSameNameStillResumesInPlace() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            TransferFixture fixture = createFixture("resume-source.bin");
            try (PeerUploadServer server = fixture.server()) {
                Path target = tempDir.resolve("resume.bin");
                byte[] partial = new byte[PIECE_SIZE * PIECE_COUNT];
                Arrays.fill(partial, (byte) 0x5a);
                System.arraycopy(fixture.content(), 0, partial, 0, PIECE_SIZE);
                Files.write(target, partial);

                DownloadSessionResult result = download(fixture, target, null);

                assertEquals("COMPLETED", result.status(), result.failureReason());
                assertEquals(target, result.outputFile(),
                        "A file with a hash-verified piece should remain the resume target");
                assertEquals(FileHashUtil.sha256(fixture.sourcePath()), FileHashUtil.sha256(target));
                assertEquals(fixture.content().length - PIECE_SIZE, server.totalBytesUploaded());
                assertNoTemporaryFiles();
            }
        });
    }

    @Test
    void writeFailureAfterOnePieceReturnsIncompleteAndDoesNotMarkFailedPieceComplete()
            throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            TransferFixture fixture = createFixture("write-failure-source.bin");
            try (PeerUploadServer server = fixture.server()) {
                Path target = tempDir.resolve("write-failure.bin");
                Path preservedPartial = tempDir.resolve("preserved-partial.bin");
                AtomicBoolean simulatedWriteFailure = new AtomicBoolean(false);
                LocalFileRegistry downloadRegistry = new LocalFileRegistry();
                MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                        downloadRegistry,
                        new PeerDownloadClient(fixture.socketFactory(),
                                PeerTestTickets.requesterPrivateKey()),
                        1,
                        PieceScheduler.Strategy.ROUND_ROBIN
                );

                DownloadSessionResult result = engine.downloadFile(
                        FILE_ID, List.of(fixture.source()), target,
                        (fileId, completedPieces, totalPieces, bytesReceived, totalBytes, speed) -> {
                            if (completedPieces == 1 && !Files.exists(preservedPartial)) {
                                try {
                                    Files.move(target, preservedPartial);
                                    Files.createDirectory(target);
                                    simulatedWriteFailure.set(true);
                                } catch (IOException e) {
                                    throw new IllegalStateException("Could not simulate target write failure", e);
                                }
                            }
                        },
                        new AtomicBoolean(false)
                );

                assertEquals("FAILED", result.status());
                assertTrue(simulatedWriteFailure.get(),
                        "The test must actually replace the target after the first piece");
                assertTrue(result.failureReason().contains("Download incomplete"),
                        result.failureReason());
                assertTrue(Files.isDirectory(target));
                assertEquals(1, downloadRegistry.find(FILE_ID).orElseThrow().completedPieceCount(),
                        "An I/O-failed piece must not be marked complete or uploadable");
                assertArrayEquals(Arrays.copyOfRange(fixture.content(), 0, PIECE_SIZE),
                        Arrays.copyOfRange(Files.readAllBytes(preservedPartial), 0, PIECE_SIZE));
                assertNoTemporaryFiles();
            }
        });
    }

    private TransferFixture createFixture(String seedName) throws Exception {
        byte[] content = new byte[PIECE_SIZE * PIECE_COUNT];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 37 + 11);
        }
        Path sourcePath = tempDir.resolve(seedName);
        Files.write(sourcePath, content);
        FileMetadata metadata = new FileMetadataBuilder(PIECE_SIZE).build(sourcePath);

        KeyPair trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        LocalFileRegistry sourceRegistry = new LocalFileRegistry();
        sourceRegistry.registerCompleteFile(FILE_ID, sourcePath, metadata);
        PeerUploadServer server = new PeerUploadServer(
                0, sourceRegistry, socketFactory, 2, trackerKeys.getPublic(), false);
        server.setUploaderPeerId(UPLOADER_ID);
        server.start();
        FileSourceInfo source = new FileSourceInfo(UPLOADER_ID, "COMPLETE", "127.0.0.1",
                server.listeningPort(), System.currentTimeMillis(),
                PeerTestTickets.issue(trackerKeys.getPrivate(), FILE_ID, REQUESTER_ID,
                        UPLOADER_ID, Instant.now()));
        return new TransferFixture(content, sourcePath, source, socketFactory, server);
    }

    private DownloadSessionResult download(TransferFixture fixture, Path target,
                                           DownloadProgressListener listener) {
        MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                new LocalFileRegistry(),
                new PeerDownloadClient(fixture.socketFactory(),
                        PeerTestTickets.requesterPrivateKey()),
                1,
                PieceScheduler.Strategy.ROUND_ROBIN
        );
        return engine.downloadFile(FILE_ID, List.of(fixture.source()), target,
                listener, new AtomicBoolean(false));
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString().endsWith(".tmp")
                            || path.getFileName().toString().endsWith(".part")));
        }
    }

    private record TransferFixture(byte[] content, Path sourcePath, FileSourceInfo source,
                                   PeerP2pSocketFactory socketFactory, PeerUploadServer server) {
    }
}
