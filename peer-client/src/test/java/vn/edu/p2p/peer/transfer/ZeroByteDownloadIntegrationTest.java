package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.RecordTransferRequest;
import vn.edu.p2p.common.dto.RecordTransferResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeroByteDownloadIntegrationTest {
    private static final long EMPTY_FILE_ID = 7901L;
    private static final long REGULAR_FILE_ID = 7902L;
    private static final int PIECE_SIZE = 16 * 1024;
    private static final String EMPTY_SHA_256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String PEER_ID = "r79-seed";

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void downloadsZeroByteFileAndReusesVerifiedTargetOnRepeat() throws Exception {
        Path seedFile = Files.createFile(tempDir.resolve("empty-seed.bin"));
        try (SeedFixture seed = startSeed(Map.of(EMPTY_FILE_ID, seedFile))) {
            Path target = tempDir.resolve("downloads/empty.bin");
            MultiSourceDownloadEngine engine = newEngine();

            DownloadSessionResult first = download(engine, seed, EMPTY_FILE_ID, target);
            assertEquals("COMPLETED", first.status());
            assertEquals(0L, first.bytesReceived());
            assertTrue(Files.isRegularFile(target));
            assertEquals(0L, Files.size(target));
            assertEquals(EMPTY_SHA_256, FileHashUtil.sha256(target));
            assertEquals(0L, seed.server.totalBytesUploaded());

            DownloadSessionResult repeated = download(engine, seed, EMPTY_FILE_ID, target);
            assertEquals("COMPLETED", repeated.status());
            assertEquals(target, repeated.outputFile());
            assertEquals(0L, repeated.bytesReceived());
            try (var files = Files.list(target.getParent())) {
                assertEquals(List.of(target), files.toList(),
                        "A verified empty file must not create a renamed duplicate");
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void wrongCatalogHashFailsBeforeCreatingEmptyTarget() throws Exception {
        Path seedFile = Files.createFile(tempDir.resolve("empty-hash-seed.bin"));
        try (SeedFixture seed = startSeed(Map.of(EMPTY_FILE_ID, seedFile))) {
            Path target = tempDir.resolve("downloads/rejected.bin");
            DownloadSessionResult result = newEngine().downloadFile(
                    EMPTY_FILE_ID, List.of(seed.source(EMPTY_FILE_ID)), target, null,
                    new AtomicBoolean(false), UUID.randomUUID(), "0".repeat(64));

            assertEquals("FAILED", result.status());
            assertFalse(Files.exists(target), "A catalog mismatch must not leave an invalid target");
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void folderDownloadPreservesEmptyFilesDirectoriesAndResumesRegularFile() throws Exception {
        Path emptySeed = Files.createFile(tempDir.resolve("folder-empty-seed.bin"));
        Path regularSeed = tempDir.resolve("folder-regular-seed.bin");
        byte[] content = new byte[PIECE_SIZE * 2 + 37];
        new Random(79).nextBytes(content);
        Files.write(regularSeed, content);
        try (SeedFixture seed = startSeed(Map.of(
                EMPTY_FILE_ID, emptySeed,
                REGULAR_FILE_ID, regularSeed))) {
            Path root = tempDir.resolve("folder-download");
            Path emptyDirectory = FolderPathResolver.resolve(root, "nested/empty/deeper");
            Files.createDirectories(emptyDirectory);
            Path emptyTarget = FolderPathResolver.resolve(root, "nested/zero.bin");
            Path regularTarget = FolderPathResolver.resolve(root, "nested/regular.bin");
            Files.createDirectories(regularTarget.getParent());
            writePartialFileWithFirstPiece(regularTarget, content);

            MultiSourceDownloadEngine engine = newEngine();
            DownloadSessionResult emptyResult = download(engine, seed, EMPTY_FILE_ID, emptyTarget);
            FileMetadata regularMetadata = seed.metadata.get(REGULAR_FILE_ID);
            DownloadSessionResult regularResult = engine.downloadFile(
                    REGULAR_FILE_ID, List.of(seed.source(REGULAR_FILE_ID)), regularTarget,
                    null, new AtomicBoolean(false), UUID.randomUUID(), regularMetadata.fileHash());

            assertEquals("COMPLETED", emptyResult.status());
            assertEquals(0L, Files.size(emptyTarget));
            assertEquals(EMPTY_SHA_256, FileHashUtil.sha256(emptyTarget));
            assertEquals("COMPLETED", regularResult.status());
            assertEquals(regularTarget, regularResult.outputFile());
            assertEquals(content.length, regularResult.bytesReceived());
            assertEquals(FileHashUtil.sha256(regularSeed), FileHashUtil.sha256(regularTarget));
            assertTrue(Files.isDirectory(emptyDirectory));

            DownloadSessionResult resumedEmpty = download(engine, seed, EMPTY_FILE_ID, emptyTarget);
            DownloadSessionResult resumedRegular = engine.downloadFile(
                    REGULAR_FILE_ID, List.of(seed.source(REGULAR_FILE_ID)), regularTarget,
                    null, new AtomicBoolean(false), UUID.randomUUID(), regularMetadata.fileHash());
            assertEquals(emptyTarget, resumedEmpty.outputFile());
            assertEquals("COMPLETED", resumedEmpty.status());
            assertEquals(regularTarget, resumedRegular.outputFile());
            assertEquals("COMPLETED", resumedRegular.status());
            assertEquals(content.length, resumedRegular.bytesReceived());
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void zeroByteCompletionIsReportedAsOneTerminalHistoryRecord() throws Exception {
        Path seedFile = Files.createFile(tempDir.resolve("empty-history-seed.bin"));
        try (SeedFixture seed = startSeed(Map.of(EMPTY_FILE_ID, seedFile));
             ServerSocket tracker = new ServerSocket(0)) {
            DownloadSessionResult result = download(
                    newEngine(), seed, EMPTY_FILE_ID, tempDir.resolve("history-empty.bin"));
            ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
            try {
                var recordsFuture = serverExecutor.submit(() -> receiveHistoryRecords(tracker));
                try (TrackerConnection connection = new TrackerConnection(
                        new TrackerConnectionConfig("127.0.0.1", tracker.getLocalPort(),
                                false, null, null, null), 1_000, 1_000)) {
                    TransferHistoryClientService history = new TransferHistoryClientService(connection);
                    UUID transferId = result.transferId();
                    history.reportTransferStart("test-session", transferId, EMPTY_FILE_ID);
                    history.reportTransferResult("test-session", result);
                }

                List<RecordTransferRequest> records = recordsFuture.get(2, TimeUnit.SECONDS);
                assertEquals(2, records.size());
                assertEquals(1, records.stream()
                        .filter(record -> "COMPLETED".equals(record.status())).count());
                RecordTransferRequest completed = records.stream()
                        .filter(record -> "COMPLETED".equals(record.status()))
                        .findFirst().orElseThrow();
                assertEquals(0L, completed.bytesReceived());
                assertEquals(result.transferId().toString(), completed.transferId());
            } finally {
                serverExecutor.shutdownNow();
                assertTrue(serverExecutor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    private SeedFixture startSeed(Map<Long, Path> files) throws Exception {
        LocalFileRegistry registry = new LocalFileRegistry();
        Map<Long, FileMetadata> metadata = new java.util.HashMap<>();
        for (var entry : files.entrySet()) {
            FileMetadata built = new FileMetadataBuilder(PIECE_SIZE).build(entry.getValue());
            metadata.put(entry.getKey(), built);
            registry.registerCompleteFile(entry.getKey(), entry.getValue(), built);
        }
        var ticketKeys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        PeerUploadServer server = new PeerUploadServer(
                0, registry, socketFactory, 4, ticketKeys.getPublic(), false);
        server.setUploaderPeerId(PEER_ID);
        server.start();
        return new SeedFixture(server, ticketKeys.getPrivate(), metadata);
    }

    private static MultiSourceDownloadEngine newEngine() {
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        return new MultiSourceDownloadEngine(new LocalFileRegistry(),
                new PeerDownloadClient(socketFactory, PeerTestTickets.requesterPrivateKey()));
    }

    private static DownloadSessionResult download(
            MultiSourceDownloadEngine engine, SeedFixture seed, long fileId, Path target) {
        FileMetadata metadata = seed.metadata.get(fileId);
        return engine.downloadFile(fileId, List.of(seed.source(fileId)), target, null,
                new AtomicBoolean(false), UUID.randomUUID(), metadata.fileHash());
    }

    private static void writePartialFileWithFirstPiece(Path target, byte[] content) throws Exception {
        byte[] partial = new byte[content.length];
        System.arraycopy(content, 0, partial, 0, PIECE_SIZE);
        Files.write(target, partial);
    }

    private static List<RecordTransferRequest> receiveHistoryRecords(ServerSocket tracker)
            throws Exception {
        List<RecordTransferRequest> records = new ArrayList<>();
        try (Socket socket = tracker.accept()) {
            socket.setSoTimeout(1_000);
            for (int i = 0; i < 2; i++) {
                MessageEnvelope request = ProtocolCodec.read(socket.getInputStream());
                assertEquals(MessageType.RECORD_TRANSFER_REQUEST, request.type());
                RecordTransferRequest payload = ProtocolCodec.fromPayload(
                        request.payload(), RecordTransferRequest.class);
                records.add(payload);
                ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.success(
                        MessageType.RECORD_TRANSFER_RESPONSE, request.requestId(),
                        ProtocolCodec.toPayload(new RecordTransferResponse(
                                payload.transferId(), payload.status()))));
            }
        }
        return records;
    }

    private static final class SeedFixture implements AutoCloseable {
        private final PeerUploadServer server;
        private final java.security.PrivateKey ticketPrivateKey;
        private final Map<Long, FileMetadata> metadata;

        private SeedFixture(PeerUploadServer server, java.security.PrivateKey ticketPrivateKey,
                            Map<Long, FileMetadata> metadata) {
            this.server = server;
            this.ticketPrivateKey = ticketPrivateKey;
            this.metadata = metadata;
        }

        private FileSourceInfo source(long fileId) {
            return new FileSourceInfo(PEER_ID, "COMPLETE", "127.0.0.1",
                    server.listeningPort(), System.currentTimeMillis(),
                    PeerTestTickets.issue(ticketPrivateKey, fileId, "r79-reader", PEER_ID, Instant.now()));
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
