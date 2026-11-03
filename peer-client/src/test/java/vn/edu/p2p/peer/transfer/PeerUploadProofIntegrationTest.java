package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.GetBitfieldRequest;
import vn.edu.p2p.common.dto.PieceRequest;
import vn.edu.p2p.common.dto.PieceResponseHeader;
import vn.edu.p2p.common.dto.TicketRevocation;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.common.security.RequestReplayGuard;
import vn.edu.p2p.peer.auth.TicketRevocationList;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.net.Socket;
import java.net.ServerSocket;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerUploadProofIntegrationTest {
    private static final long FILE_ID = 671L;
    private static final String UPLOADER_ID = "proof-uploader";
    private static final String REQUESTER_ID = "proof-requester";
    private static final String OTHER_UPLOADER_ID = "other-uploader";
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void requesterProofCanFetchBitfieldAndPiece() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        try (server) {
            PeerDownloadClient client = new PeerDownloadClient(fixture.socketFactory(),
                    fixture.requesterKeys().getPrivate(), fixture.clock());

            var bitfield = client.fetchBitfield("127.0.0.1", server.listeningPort(),
                    UPLOADER_ID, FILE_ID, fixture.ticket());
            var piece = client.downloadPiece("127.0.0.1", server.listeningPort(),
                    UPLOADER_ID, FILE_ID, 0, fixture.ticket());

            assertEquals(2, bitfield.toBitSet().cardinality());
            assertArrayEquals(new byte[]{1, 2, 3}, piece.data());
            assertEquals(piece.header().pieceHash(),
                    FileHashUtil.sha256(piece.data(), 0, piece.data().length));
            assertEquals(FileHashUtil.sha256(fixture.file()), fixture.metadata().fileHash());
            assertEquals(3L, server.totalBytesUploaded());
            assertEquals(2, fixture.replayGuard().size());
        }
    }

    @Test
    void closeReleasesPortAndSameInstanceCanServeAgain() throws Exception {
        Fixture fixture = createFixture(freePort());
        PeerUploadServer server = fixture.server();
        int port = server.listeningPort();
        server.close();
        server.close();

        assertTrue(server.workersTerminated());
        server.start();
        assertEquals(port, server.listeningPort());
        try (server) {
            PeerDownloadClient client = new PeerDownloadClient(fixture.socketFactory(),
                    fixture.requesterKeys().getPrivate(), fixture.clock());
            assertEquals(2, client.fetchBitfield("127.0.0.1", port, UPLOADER_ID,
                    FILE_ID, fixture.ticket()).toBitSet().cardinality());
            assertArrayEquals(new byte[]{1, 2, 3}, client.downloadPiece("127.0.0.1", port,
                    UPLOADER_ID, FILE_ID, 0, fixture.ticket()).data());
        }
    }

    @Test
    void closeTerminatesAcceptAndClosesAnIncompleteClientRequest() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        try (Socket client = new Socket("127.0.0.1", server.listeningPort())) {
            await(() -> server.activeSocketCount() == 1, 1_000);
            server.close();
            client.setSoTimeout(500);
            assertEquals(-1, client.getInputStream().read());
            assertFalse(server.acceptThreadAlive());
            assertTrue(server.workersTerminated());
            server.close();
        }
    }

    @Test
    void stolenTicketCannotFetchBitfieldOrPieceAndInvalidProofDoesNotUseReplayCapacity()
            throws Exception {
        Fixture fixture = createFixture();
        KeyPair attackerKeys = PeerRequestProof.generateKeyPair();
        PeerUploadServer server = fixture.server();
        try (server) {
            MessageEnvelope bitfield = bitfieldRequest(fixture, attackerKeys.getPrivate(),
                    UPLOADER_ID, FILE_ID, fixture.ticket());
            MessageEnvelope piece = pieceRequest(fixture, attackerKeys.getPrivate(),
                    UPLOADER_ID, FILE_ID, 0, fixture.ticket());

            assertDenied(exchange(fixture, bitfield, false));
            assertDenied(exchange(fixture, piece, true));

            assertEquals(0, fixture.replayGuard().size());
            assertEquals(0L, server.totalBytesUploaded());
        }
    }

    @Test
    void replayingTheSamePieceRequestIsDeniedAfterFirstDelivery() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        try (server; Socket socket = new Socket("127.0.0.1", server.listeningPort())) {
            MessageEnvelope request = pieceRequest(fixture, fixture.requesterKeys().getPrivate(),
                    UPLOADER_ID, FILE_ID, 0, fixture.ticket());

            Exchange first = exchange(socket, request, true);
            Exchange replay = exchange(socket, request, true);

            assertEquals(ResponseStatus.SUCCESS, first.response().status());
            assertArrayEquals(new byte[]{1, 2, 3}, first.pieceBytes());
            assertDenied(replay);
            assertEquals(1, fixture.replayGuard().size());
            assertEquals(3L, server.totalBytesUploaded());
        }
    }

    @Test
    void rejectsTamperingOldAndFutureProofsMissingProofAndInvalidTicket() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        try (server) {
            MessageEnvelope missingProof = MessageEnvelope.request(MessageType.GET_BITFIELD_REQUEST,
                    UUID.randomUUID().toString(), ProtocolCodec.toPayload(
                            new GetBitfieldRequest(FILE_ID, fixture.ticket())));
            assertDenied(exchange(fixture, missingProof, false));

            MessageEnvelope invalidTicket = bitfieldRequest(fixture,
                    fixture.requesterKeys().getPrivate(), UPLOADER_ID, FILE_ID, "not-a-ticket");
            assertDenied(exchange(fixture, invalidTicket, false));

            PeerRequestProof.Proof ticketlessProof = PeerRequestProof.sign(
                    fixture.requesterKeys().getPrivate(), PeerRequestProof.RequestType.BITFIELD,
                    UPLOADER_ID, FILE_ID, PeerRequestProof.BITFIELD_PIECE_INDEX,
                    "not-a-ticket", fixture.clock());
            MessageEnvelope missingTicket = MessageEnvelope.request(MessageType.GET_BITFIELD_REQUEST,
                    UUID.randomUUID().toString(), ProtocolCodec.toPayload(new GetBitfieldRequest(
                            FILE_ID, null, ticketlessProof.timestampEpochMillis(),
                            ticketlessProof.requestNonce(), ticketlessProof.signature())));
            assertDenied(exchange(fixture, missingTicket, false));

            MessageEnvelope alteredPieceIndex = pieceRequest(fixture,
                    fixture.requesterKeys().getPrivate(), UPLOADER_ID, FILE_ID, 0, fixture.ticket(),
                    1);
            assertDenied(exchange(fixture, alteredPieceIndex, true));

            MessageEnvelope proofForAnotherFile = pieceRequest(fixture,
                    fixture.requesterKeys().getPrivate(), UPLOADER_ID, FILE_ID, 0, fixture.ticket(),
                    FILE_ID + 1);
            assertDenied(exchange(fixture, proofForAnotherFile, true));

            MessageEnvelope proofForAnotherUploader = bitfieldRequest(fixture,
                    fixture.requesterKeys().getPrivate(), OTHER_UPLOADER_ID, FILE_ID, fixture.ticket());
            assertDenied(exchange(fixture, proofForAnotherUploader, false));

            PeerRequestProof.Proof fresh = PeerRequestProof.sign(fixture.requesterKeys().getPrivate(),
                    PeerRequestProof.RequestType.BITFIELD, UPLOADER_ID, FILE_ID,
                    PeerRequestProof.BITFIELD_PIECE_INDEX, fixture.ticket(), fixture.clock());
            PeerRequestProof.Proof tooOld = new PeerRequestProof.Proof(
                    NOW.minusSeconds(61).toEpochMilli(), fresh.requestNonce(), fresh.signature());
            PeerRequestProof.Proof tooFarFuture = new PeerRequestProof.Proof(
                    NOW.plusSeconds(61).toEpochMilli(), fresh.requestNonce(), fresh.signature());
            assertFalse(PeerRequestProof.verify(fixture.requesterKeys().getPublic(),
                    PeerRequestProof.RequestType.BITFIELD, UPLOADER_ID, FILE_ID,
                    PeerRequestProof.BITFIELD_PIECE_INDEX, fixture.ticket(), tooOld, fixture.clock()));
            assertFalse(PeerRequestProof.verify(fixture.requesterKeys().getPublic(),
                    PeerRequestProof.RequestType.BITFIELD, UPLOADER_ID, FILE_ID,
                    PeerRequestProof.BITFIELD_PIECE_INDEX, fixture.ticket(), tooFarFuture, fixture.clock()));
            assertDenied(exchange(fixture, bitfieldRequest(fixture, tooOld), false));
            assertDenied(exchange(fixture, bitfieldRequest(fixture, tooFarFuture), false));

            assertEquals(0, fixture.replayGuard().size());
            assertEquals(0L, server.totalBytesUploaded());
        }
    }

    @Test
    void expiredTicketRemainsRejectedByUploader() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        String expired = PeerAccessTickets.issue(fixture.trackerKeys().getPrivate(),
                PeerAccessTickets.DEFAULT_KEY_ID, FILE_ID, REQUESTER_ID, UPLOADER_ID,
                fixture.requesterKeys().getPublic(),
                NOW.minusSeconds(PeerAccessTickets.DEFAULT_TTL_SECONDS + 1),
                PeerAccessTickets.DEFAULT_TTL_SECONDS);
        try (server) {
            MessageEnvelope request = bitfieldRequest(fixture,
                    fixture.requesterKeys().getPrivate(), UPLOADER_ID, FILE_ID, expired);
            assertDenied(exchange(fixture, request, false));
            assertEquals(0L, server.totalBytesUploaded());
        }
    }

    @Test
    void uploaderAcceptsOverlappingKeyIdsAndRejectsRemovedKey() throws Exception {
        Path file = tempDir.resolve("rotating-key-content.bin");
        Files.write(file, new byte[]{4, 5, 6});
        FileMetadata metadata = new FileMetadataBuilder(3).build(file);
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(FILE_ID, file, metadata);
        KeyPair oldTrackerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair newTrackerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair requesterKey = PeerRequestProof.generateKeyPair();
        PeerP2pSocketFactory sockets =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        PeerUploadServer overlapping = new PeerUploadServer(0, registry, sockets, 2,
                Map.of("key-a", oldTrackerKey.getPublic(), "key-b", newTrackerKey.getPublic()),
                false);
        overlapping.setUploaderPeerId(UPLOADER_ID);
        overlapping.start();
        try (overlapping) {
            PeerDownloadClient client = new PeerDownloadClient(sockets, requesterKey.getPrivate());
            String oldTicket = PeerAccessTickets.issue(oldTrackerKey.getPrivate(), "key-a",
                    FILE_ID, REQUESTER_ID, UPLOADER_ID, requesterKey.getPublic(),
                    Instant.now(), 900);
            String newTicket = PeerAccessTickets.issue(newTrackerKey.getPrivate(), "key-b",
                    FILE_ID, REQUESTER_ID, UPLOADER_ID, requesterKey.getPublic(),
                    Instant.now(), 900);

            assertNotNull(client.fetchBitfield("127.0.0.1", overlapping.listeningPort(),
                    UPLOADER_ID, FILE_ID, oldTicket));
            assertNotNull(client.fetchBitfield("127.0.0.1", overlapping.listeningPort(),
                    UPLOADER_ID, FILE_ID, newTicket));
        }

        PeerUploadServer newKeyOnly = new PeerUploadServer(0, registry, sockets, 1,
                Map.of("key-b", newTrackerKey.getPublic()), false);
        newKeyOnly.setUploaderPeerId(UPLOADER_ID);
        newKeyOnly.start();
        try (newKeyOnly) {
            PeerDownloadClient client = new PeerDownloadClient(sockets, requesterKey.getPrivate());
            String oldTicket = PeerAccessTickets.issue(oldTrackerKey.getPrivate(), "key-a",
                    FILE_ID, REQUESTER_ID, UPLOADER_ID, requesterKey.getPublic(),
                    Instant.now(), 900);
            assertThrows(IOException.class, () -> client.fetchBitfield("127.0.0.1",
                    newKeyOnly.listeningPort(), UPLOADER_ID, FILE_ID, oldTicket));
        }
    }

    @Test
    void logoutRevocationIsAppliedOnHeartbeatAndLaterTicketRemainsValid() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        try (server) {
            PeerDownloadClient client = new PeerDownloadClient(fixture.socketFactory(),
                    fixture.requesterKeys().getPrivate(), fixture.clock());
            assertNotNull(client.fetchBitfield("127.0.0.1", server.listeningPort(),
                    UPLOADER_ID, FILE_ID, fixture.ticket()));

            fixture.ticketRevocations().apply(new HeartbeatResponse(NOW.toEpochMilli(),
                    java.util.List.of(new TicketRevocation(1, TicketRevocation.Type.REQUESTER,
                            REQUESTER_ID, null, 0, NOW.plusSeconds(1).toEpochMilli())), 1L));
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () ->
                    client.fetchBitfield("127.0.0.1", server.listeningPort(),
                            UPLOADER_ID, FILE_ID, fixture.ticket()));

            String renewed = PeerAccessTickets.issue(fixture.trackerKeys().getPrivate(),
                    FILE_ID, REQUESTER_ID, UPLOADER_ID, fixture.requesterKeys().getPublic(),
                    NOW.plusSeconds(2));
            assertNotNull(client.fetchBitfield("127.0.0.1", server.listeningPort(),
                    UPLOADER_ID, FILE_ID, renewed));
        }
    }

    @Test
    void fileRevocationIsScopedToTheUnsharedFile() throws Exception {
        Fixture fixture = createFixture();
        PeerUploadServer server = fixture.server();
        try (server) {
            fixture.ticketRevocations().apply(new HeartbeatResponse(NOW.toEpochMilli(),
                    java.util.List.of(new TicketRevocation(1, TicketRevocation.Type.FILE,
                            null, UPLOADER_ID, FILE_ID, NOW.plusSeconds(1).toEpochMilli())), 1L));

            PeerDownloadClient client = new PeerDownloadClient(fixture.socketFactory(),
                    fixture.requesterKeys().getPrivate(), fixture.clock());
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () ->
                    client.fetchBitfield("127.0.0.1", server.listeningPort(),
                            UPLOADER_ID, FILE_ID, fixture.ticket()));
            String otherFileTicket = PeerAccessTickets.issue(fixture.trackerKeys().getPrivate(),
                    FILE_ID + 1, REQUESTER_ID, UPLOADER_ID, fixture.requesterKeys().getPublic(),
                    NOW.plusSeconds(2));
            assertNotNull(client.fetchBitfield("127.0.0.1", server.listeningPort(),
                    UPLOADER_ID, FILE_ID + 1, otherFileTicket));
        }
    }

    private Fixture createFixture() throws Exception {
        return createFixture(0);
    }

    private Fixture createFixture(int listeningPort) throws Exception {
        Path file = tempDir.resolve("proof-content.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6});
        FileMetadata metadata = new FileMetadataBuilder(3).build(file);
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(FILE_ID, file, metadata);
        registry.registerCompleteFile(FILE_ID + 1, file, metadata);
        KeyPair trackerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair requesterKeys = PeerRequestProof.generateKeyPair();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RequestReplayGuard guard = new RequestReplayGuard(clock, Duration.ofSeconds(60), 8);
        TicketRevocationList ticketRevocations = new TicketRevocationList(clock);
        PeerP2pSocketFactory socketFactory =
                new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        PeerUploadServer server = new PeerUploadServer(listeningPort, registry, socketFactory, 2,
                trackerKeys.getPublic(), false, clock, guard, ticketRevocations);
        server.setUploaderPeerId(UPLOADER_ID);
        server.start();
        String ticket = PeerAccessTickets.issue(trackerKeys.getPrivate(), FILE_ID, REQUESTER_ID,
                UPLOADER_ID, requesterKeys.getPublic(), NOW);
        return new Fixture(file, metadata, trackerKeys, requesterKeys, ticket, clock, guard,
                ticketRevocations, socketFactory, server);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS
                .toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static MessageEnvelope bitfieldRequest(Fixture fixture, java.security.PrivateKey signer,
                                                    String signedUploader, long fileId, String ticket) {
        PeerRequestProof.Proof proof = PeerRequestProof.sign(signer,
                PeerRequestProof.RequestType.BITFIELD, signedUploader, fileId,
                PeerRequestProof.BITFIELD_PIECE_INDEX, ticket, fixture.clock());
        return MessageEnvelope.request(MessageType.GET_BITFIELD_REQUEST, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new GetBitfieldRequest(fileId, ticket,
                        proof.timestampEpochMillis(), proof.requestNonce(), proof.signature())));
    }

    private static MessageEnvelope bitfieldRequest(Fixture fixture, PeerRequestProof.Proof proof) {
        return MessageEnvelope.request(MessageType.GET_BITFIELD_REQUEST, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new GetBitfieldRequest(FILE_ID, fixture.ticket(),
                        proof.timestampEpochMillis(), proof.requestNonce(), proof.signature())));
    }

    private static MessageEnvelope pieceRequest(Fixture fixture, java.security.PrivateKey signer,
                                                 String signedUploader, long fileId, int signedPiece,
                                                 String ticket) {
        return pieceRequest(fixture, signer, signedUploader, fileId, signedPiece, ticket, signedPiece);
    }

    private static MessageEnvelope pieceRequest(Fixture fixture, java.security.PrivateKey signer,
                                                 String signedUploader, long signedFileId,
                                                 int signedPiece, String ticket, int requestPiece) {
        return pieceRequest(fixture, signer, signedUploader, signedFileId, signedPiece,
                ticket, signedFileId, requestPiece);
    }

    private static MessageEnvelope pieceRequest(Fixture fixture, java.security.PrivateKey signer,
                                                 String signedUploader, long signedFileId,
                                                 int signedPiece, String ticket, long requestFileId) {
        return pieceRequest(fixture, signer, signedUploader, signedFileId, signedPiece,
                ticket, requestFileId, signedPiece);
    }

    private static MessageEnvelope pieceRequest(Fixture fixture, java.security.PrivateKey signer,
                                                 String signedUploader, long signedFileId,
                                                 int signedPiece, String ticket, long requestFileId,
                                                 int requestPiece) {
        PeerRequestProof.Proof proof = PeerRequestProof.sign(signer,
                PeerRequestProof.RequestType.PIECE, signedUploader, signedFileId,
                signedPiece, ticket, fixture.clock());
        return MessageEnvelope.request(MessageType.REQUEST_PIECE, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new PieceRequest(requestFileId, requestPiece, ticket,
                        proof.timestampEpochMillis(), proof.requestNonce(), proof.signature())));
    }

    private static Exchange exchange(Fixture fixture, MessageEnvelope request,
                                     boolean pieceResponsePossible) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", fixture.server().listeningPort())) {
            return exchange(socket, request, pieceResponsePossible);
        }
    }

    private static Exchange exchange(Socket socket, MessageEnvelope request,
                                     boolean pieceResponsePossible) throws Exception {
        ProtocolCodec.write(socket.getOutputStream(), request);
        MessageEnvelope response = ProtocolCodec.read(socket.getInputStream());
        byte[] pieceBytes = null;
        if (pieceResponsePossible && response.status() == ResponseStatus.SUCCESS
                && response.type() == MessageType.PIECE_RESPONSE) {
            PieceResponseHeader header = ProtocolCodec.fromPayload(
                    response.payload(), PieceResponseHeader.class);
            pieceBytes = ProtocolCodec.readBinaryPayload(socket.getInputStream(), header.pieceLength());
        }
        return new Exchange(response, pieceBytes);
    }

    private static void assertDenied(Exchange exchange) {
        assertNotNull(exchange.response());
        assertEquals(ResponseStatus.ERROR, exchange.response().status());
        assertEquals("ACCESS_DENIED", exchange.response().errorCode());
        assertTrue(exchange.pieceBytes() == null || exchange.pieceBytes().length == 0);
    }

    private record Fixture(Path file, FileMetadata metadata, KeyPair trackerKeys,
                           KeyPair requesterKeys, String ticket, Clock clock,
                           RequestReplayGuard replayGuard, TicketRevocationList ticketRevocations,
                           PeerP2pSocketFactory socketFactory,
                           PeerUploadServer server) {}

    private record Exchange(MessageEnvelope response, byte[] pieceBytes) {}
}
