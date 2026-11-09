package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.MetadataPageRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.security.PeerRequestProof;
import java.net.Socket;
import java.util.UUID;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataPageIntegrationTest {
    @TempDir Path dir;

    @Test
    void largeMetadataIsSentAsSmallPages() throws Exception {
        List<PieceInfo> pieces = new ArrayList<>();
        for (int i = 0; i < 2401; i++) {
            pieces.add(new PieceInfo(i, 256 * 1024, "a".repeat(64)));
        }
        FileMetadata metadata = new FileMetadata("large.bin", (long) pieces.size() * 256 * 1024,
                "b".repeat(64), 256 * 1024, pieces.size(), pieces);
        Path dummy = dir.resolve("dummy.bin");
        Files.write(dummy, new byte[1]);
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(9, dummy, metadata);
        var sockets = new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String token = PeerTestTickets.issue(keys.getPrivate(), 9,
                "test-client", "test-server", java.time.Instant.now());
        try (PeerUploadServer server = new PeerUploadServer(0, registry, sockets, 8, keys.getPublic(), false)) {
            server.setUploaderPeerId("test-server");
            server.start();
            PeerDownloadClient client = new PeerDownloadClient(
                    sockets, PeerTestTickets.requesterPrivateKey());
            var header = client.fetchBitfield("127.0.0.1", server.listeningPort(),
                    "test-server", 9, token);
            assertTrue(header.metadata().pieces().isEmpty());
            int offset = 0;
            while (offset < pieces.size()) {
                var page = client.fetchMetadataPage("127.0.0.1", server.listeningPort(),
                        "test-server", 9, offset, 400, token);
                assertEquals(offset, page.startIndex());
                assertEquals(offset, page.pieces().get(0).pieceIndex());
                offset += page.pieces().size();
            }
            assertEquals(pieces.size(), offset);
        }
    }
    @Test
    void metadataPageRequiresProofAndRejectsModifiedPageBounds() throws Exception {
        List<PieceInfo> pieces = List.of(new PieceInfo(0, 3, "a".repeat(64)));
        FileMetadata metadata = new FileMetadata("single.bin", 3L, "b".repeat(64), 3, 1, pieces);
        Path dummy = dir.resolve("single.bin");
        Files.write(dummy, new byte[]{1, 2, 3});
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(9, dummy, metadata);
        var sockets = new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        var signingKey = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String ticket = PeerTestTickets.issue(signingKey.getPrivate(), 9,
                "test-client", "test-server", java.time.Instant.now());
        try (PeerUploadServer server = new PeerUploadServer(0, registry, sockets, 8,
                signingKey.getPublic(), false)) {
            server.setUploaderPeerId("test-server");
            server.start();
            var unproven = new MetadataPageRequest(9, 0, 1, ticket);
            assertEquals("ACCESS_DENIED", exchange(server, unproven).errorCode());
            var proof = PeerRequestProof.signMetadataPage(
                    PeerTestTickets.requesterPrivateKey(), "test-server", 9, 0, 1,
                    ticket, java.time.Clock.systemUTC());
            var modified = new MetadataPageRequest(9, 0, 2, ticket,
                    proof.timestampEpochMillis(), proof.requestNonce(), proof.signature());
            assertEquals("ACCESS_DENIED", exchange(server, modified).errorCode());
            var valid = new MetadataPageRequest(9, 0, 1, ticket,
                    proof.timestampEpochMillis(), proof.requestNonce(), proof.signature());
            assertEquals(null, exchange(server, valid).errorCode());
            assertEquals("ACCESS_DENIED", exchange(server, valid).errorCode());
        }
    }

    private static MessageEnvelope exchange(PeerUploadServer server, MetadataPageRequest request)
            throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.listeningPort())) {
            socket.setSoTimeout(3000);
            ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.request(
                    MessageType.METADATA_PAGE_REQUEST, UUID.randomUUID().toString(),
                    ProtocolCodec.toPayload(request)));
            return ProtocolCodec.read(socket.getInputStream());
        }
    }

}
