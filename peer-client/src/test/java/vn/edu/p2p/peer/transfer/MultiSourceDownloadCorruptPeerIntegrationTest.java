package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.BitfieldResponse;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.GetBitfieldRequest;
import vn.edu.p2p.common.dto.PieceRequest;
import vn.edu.p2p.common.dto.PieceResponseHeader;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceDownloadCorruptPeerIntegrationTest {
    private static final long FILE_ID = 924L;
    private static final int PIECE_SIZE = 64 * 1024;
    private static final int PIECE_COUNT = 8;
    private static final String BAD_PEER_ID = "corrupt-peer";
    private static final String GOOD_PEER_ID = "verified-peer";
    private static final byte SENTINEL = (byte) 0xA5;
    private static final int CORRUPT_STRIKE_LIMIT = 3;

    @TempDir
    Path tempDir;

    @Test
    void blacklistsCorruptPeerAndCompletesFromVerifiedSeed() throws Exception {
        Scenario scenario = createScenario("corrupt-with-good-source.bin");
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (FakeCorruptPeer badPeer = new FakeCorruptPeer(
                     scenario.content(), scenario.metadata(), keys, BAD_PEER_ID);
             PeerUploadServer goodPeer = createGoodPeer(scenario, keys)) {
            LocalFileRegistry downloader = new LocalFileRegistry();
            AtomicReference<String> completedPieceError = new AtomicReference<>();
            DownloadProgressListener verifyCompletedPieces = (fileId, completed, total, bytes, size, speed) -> {
                try {
                    verifyRegistryPieces(downloader, scenario.metadata(), completedPieceError);
                } catch (IOException e) {
                    completedPieceError.compareAndSet(null, e.getMessage());
                }
            };
            MultiSourceDownloadEngine engine = engine(
                    downloader, PieceScheduler.Strategy.ROUND_ROBIN);

            DownloadSessionResult result = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    engine.downloadFile(FILE_ID, List.of(
                                    badPeer.source(), goodPeerSource(goodPeer, keys)),
                            scenario.target(), verifyCompletedPieces, new java.util.concurrent.atomic.AtomicBoolean(false)));

            assertEquals("COMPLETED", result.status());
            assertEquals(PIECE_COUNT, totalPiecesReceived(result));
            assertEquals(CORRUPT_STRIKE_LIMIT, badPeer.pieceRequestCount());
            assertEquals(0, piecesReceivedFrom(result, BAD_PEER_ID));
            assertEquals(PIECE_COUNT, piecesReceivedFrom(result, GOOD_PEER_ID));
            assertNull(completedPieceError.get());
            assertEquals(scenario.metadata().fileHash(), FileHashUtil.sha256(scenario.target()));
            assertArrayEquals(scenario.content(), Files.readAllBytes(scenario.target()));
        }
    }

    @Test
    void failsWithoutWritingCorruptPiecesWhenCorruptPeerIsTheOnlySource() throws Exception {
        Scenario scenario = createScenario("corrupt-only-source.bin");
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (FakeCorruptPeer badPeer = new FakeCorruptPeer(
                scenario.content(), scenario.metadata(), keys, BAD_PEER_ID)) {
            MultiSourceDownloadEngine engine = engine(
                    new LocalFileRegistry(), PieceScheduler.Strategy.ROUND_ROBIN);

            DownloadSessionResult result = assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    engine.downloadFile(FILE_ID, List.of(badPeer.source()), scenario.target(),
                            null, new java.util.concurrent.atomic.AtomicBoolean(false)));

            assertEquals("FAILED", result.status());
            assertTrue(result.failureReason().contains("incomplete"));
            assertEquals(CORRUPT_STRIKE_LIMIT, badPeer.pieceRequestCount());
            assertEquals(0, piecesReceivedFrom(result, BAD_PEER_ID));
            assertArrayEquals(filled(scenario.content().length, SENTINEL),
                    Files.readAllBytes(scenario.target()));
        }
    }

    @Test
    void rankedSchedulerPrefersGoodPeerAfterBadPeerReceivesACorruptionStrike() {
        PieceScheduler scheduler = new PieceScheduler(
                1, null, PieceScheduler.Strategy.RANKED);
        BitSet hasPiece = new BitSet();
        hasPiece.set(0);
        FileSourceInfo badPeer = new FileSourceInfo(
                BAD_PEER_ID, "COMPLETE", "127.0.0.1", 1, 0L);
        FileSourceInfo goodPeer = new FileSourceInfo(
                GOOD_PEER_ID, "COMPLETE", "127.0.0.1", 2, 0L);
        scheduler.registerSource(badPeer, hasPiece);
        scheduler.registerSource(goodPeer, hasPiece);

        PieceScheduler.Assignment first = scheduler.nextAssignment().orElseThrow();
        assertEquals(BAD_PEER_ID, first.source().peerId());
        scheduler.recordCorruptPieceFromPeer(BAD_PEER_ID);
        scheduler.releasePieceToPending(first.pieceIndex());

        assertEquals(GOOD_PEER_ID, scheduler.nextAssignment().orElseThrow().source().peerId());
    }

    private Scenario createScenario(String targetName) throws Exception {
        Path sourceFile = tempDir.resolve("source-" + targetName);
        byte[] content = new byte[PIECE_SIZE * (PIECE_COUNT - 1) + 31];
        new Random(24024).nextBytes(content);
        Files.write(sourceFile, content);
        FileMetadata metadata = new FileMetadataBuilder(PIECE_SIZE).build(sourceFile);
        assertEquals(PIECE_COUNT, metadata.pieceCount());
        Path target = tempDir.resolve(targetName);
        Files.write(target, filled(content.length, SENTINEL));
        return new Scenario(content, metadata, sourceFile, target);
    }

    private PeerUploadServer createGoodPeer(Scenario scenario, KeyPair keys) throws Exception {
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(FILE_ID, scenario.source(), scenario.metadata());
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        PeerUploadServer server = new PeerUploadServer(
                0, registry, socketFactory, 2, keys.getPublic(), false);
        server.setUploaderPeerId(GOOD_PEER_ID);
        server.start();
        return server;
    }

    private static FileSourceInfo goodPeerSource(PeerUploadServer server, KeyPair keys) {
        String ticket = vn.edu.p2p.common.security.PeerAccessTickets.issue(
                keys.getPrivate(), FILE_ID, "corruption-test", GOOD_PEER_ID, Instant.now());
        return new FileSourceInfo(GOOD_PEER_ID, "COMPLETE", "127.0.0.1",
                server.listeningPort(), System.currentTimeMillis(), ticket);
    }

    private static MultiSourceDownloadEngine engine(
            LocalFileRegistry registry, PieceScheduler.Strategy strategy) {
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        return new MultiSourceDownloadEngine(
                registry, new PeerDownloadClient(socketFactory), 1, strategy);
    }

    private static void verifyRegistryPieces(
            LocalFileRegistry registry,
            FileMetadata metadata,
            AtomicReference<String> pieceError
    ) throws IOException {
        LocalFileRegistry.RegisteredFile file = registry.find(FILE_ID).orElseThrow();
        BitSet completed = file.bitSetSnapshot();
        byte[] current = Files.readAllBytes(file.filePath());
        for (int pieceIndex = completed.nextSetBit(0);
             pieceIndex >= 0;
             pieceIndex = completed.nextSetBit(pieceIndex + 1)) {
            int offset = pieceIndex * metadata.pieceSize();
            int length = metadata.pieces().get(pieceIndex).pieceLength();
            String actualHash = FileHashUtil.sha256(current, offset, length);
            if (!metadata.pieces().get(pieceIndex).pieceHash().equalsIgnoreCase(actualHash)) {
                pieceError.compareAndSet(null, "Completed piece hash mismatch: " + pieceIndex);
            }
        }
    }

    private static int totalPiecesReceived(DownloadSessionResult result) {
        return result.sourceStats().stream()
                .mapToInt(DownloadSessionResult.SourceTransferStat::piecesReceived)
                .sum();
    }

    private static int piecesReceivedFrom(DownloadSessionResult result, String peerId) {
        return result.sourceStats().stream()
                .filter(stat -> peerId.equals(stat.sourcePeerId()))
                .mapToInt(DownloadSessionResult.SourceTransferStat::piecesReceived)
                .sum();
    }

    private static byte[] filled(int length, byte value) {
        byte[] data = new byte[length];
        Arrays.fill(data, value);
        return data;
    }

    private record Scenario(byte[] content, FileMetadata metadata, Path source, Path target) {
    }

    private static final class FakeCorruptPeer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final byte[] content;
        private final FileMetadata metadata;
        private final KeyPair ticketKeys;
        private final String peerId;
        private final FileSourceInfo source;
        private final AtomicInteger pieceRequestCount = new AtomicInteger();
        private final CompletableFuture<Void> acceptLoop;

        private FakeCorruptPeer(byte[] content, FileMetadata metadata, KeyPair ticketKeys,
                                String peerId) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.content = content;
            this.metadata = metadata;
            this.ticketKeys = ticketKeys;
            this.peerId = peerId;
            String ticket = vn.edu.p2p.common.security.PeerAccessTickets.issue(
                    ticketKeys.getPrivate(), FILE_ID, "corruption-test", peerId, Instant.now());
            this.source = new FileSourceInfo(peerId, "COMPLETE", "127.0.0.1",
                    serverSocket.getLocalPort(), System.currentTimeMillis(), ticket);
            this.acceptLoop = CompletableFuture.runAsync(this::serve);
        }

        private FileSourceInfo source() {
            return source;
        }

        private int pieceRequestCount() {
            return pieceRequestCount.get();
        }

        private void serve() {
            while (!serverSocket.isClosed()) {
                try (Socket client = serverSocket.accept()) {
                    client.setSoTimeout(3_000);
                    MessageEnvelope request = ProtocolCodec.read(client.getInputStream());
                    if (request.version() != MessageEnvelope.CURRENT_VERSION) {
                        writeError(client, request, "UNSUPPORTED_PROTOCOL_VERSION");
                    } else if (request.type() == MessageType.GET_BITFIELD_REQUEST) {
                        handleBitfield(client, request);
                    } else if (request.type() == MessageType.REQUEST_PIECE) {
                        handlePiece(client, request);
                    } else {
                        writeError(client, request, "UNSUPPORTED_P2P_COMMAND");
                    }
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        throw new IllegalStateException("Fake peer failed to serve request", e);
                    }
                    return;
                }
            }
        }

        private void handleBitfield(Socket client, MessageEnvelope request) throws IOException {
            GetBitfieldRequest payload = ProtocolCodec.fromPayload(
                    request.payload(), GetBitfieldRequest.class);
            if (!ticketValid(payload.fileId(), payload.accessTicket())) {
                writeError(client, request, "ACCESS_DENIED");
                return;
            }
            BitSet pieces = new BitSet(metadata.pieceCount());
            pieces.set(0, metadata.pieceCount());
            BitfieldResponse response = new BitfieldResponse(
                    FILE_ID, pieces.toByteArray(), metadata);
            ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.success(
                    MessageType.BITFIELD_RESPONSE, request.requestId(),
                    ProtocolCodec.toPayload(response)));
        }

        private void handlePiece(Socket client, MessageEnvelope request) throws IOException {
            PieceRequest payload = ProtocolCodec.fromPayload(request.payload(), PieceRequest.class);
            if (!ticketValid(payload.fileId(), payload.accessTicket())) {
                writeError(client, request, "ACCESS_DENIED");
                return;
            }
            pieceRequestCount.incrementAndGet();
            int offset = payload.pieceIndex() * metadata.pieceSize();
            int length = metadata.pieces().get(payload.pieceIndex()).pieceLength();
            byte[] corrupt = Arrays.copyOfRange(content, offset, offset + length);
            corrupt[0] ^= (byte) 0xFF;
            PieceResponseHeader header = new PieceResponseHeader(
                    FILE_ID, payload.pieceIndex(), length,
                    metadata.pieces().get(payload.pieceIndex()).pieceHash());
            ProtocolCodec.writeWithBinaryPayload(client.getOutputStream(),
                    MessageEnvelope.success(MessageType.PIECE_RESPONSE, request.requestId(),
                            ProtocolCodec.toPayload(header)),
                    corrupt, 0, corrupt.length);
        }

        private boolean ticketValid(long fileId, String accessTicket) {
            return fileId == FILE_ID && vn.edu.p2p.common.security.PeerAccessTickets.verify(
                    ticketKeys.getPublic(), accessTicket, FILE_ID, peerId, Instant.now());
        }

        private static void writeError(Socket client, MessageEnvelope request, String code)
                throws IOException {
            ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.error(
                    MessageType.ERROR, request.requestId(), code, code));
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            acceptLoop.get(3, TimeUnit.SECONDS);
        }
    }
}
