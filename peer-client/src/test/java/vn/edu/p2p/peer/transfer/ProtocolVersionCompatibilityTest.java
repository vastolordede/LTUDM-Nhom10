package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ResponseStatus;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolVersionCompatibilityTest {
    @Test
    void rejectsPeerResponseWithUnsupportedProtocolVersion() throws Exception {
        assertPeerResponseRejected(new MessageEnvelope(
                MessageEnvelope.CURRENT_VERSION + 1,
                MessageType.BITFIELD_RESPONSE,
                "fake-peer-response",
                ResponseStatus.SUCCESS,
                null,
                null,
                ProtocolCodec.toPayload(Map.of())
        ));
    }

    @Test
    void mapsPeerUnsupportedProtocolErrorToCompatibilityFailure() throws Exception {
        assertPeerResponseRejected(MessageEnvelope.error(
                MessageType.ERROR,
                "fake-peer-response",
                "UNSUPPORTED_PROTOCOL_VERSION",
                "Unsupported protocol version"
        ));
    }

    @Test
    void rejectsWrongProtocolVersionBeforeServingPiece() throws Exception {
        var keyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PeerP2pSocketFactory socketFactory = new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        try (PeerUploadServer server = new PeerUploadServer(
                0, new LocalFileRegistry(), socketFactory, 2, keyPair.getPublic(), false)) {
            server.start();
            try (Socket client = new Socket("127.0.0.1", server.listeningPort())) {
                ProtocolCodec.write(client.getOutputStream(), new MessageEnvelope(
                        MessageEnvelope.CURRENT_VERSION + 1,
                        MessageType.REQUEST_PIECE,
                        "wrong-version-piece",
                        null,
                        null,
                        null,
                        ProtocolCodec.toPayload(new vn.edu.p2p.common.dto.PieceRequest(1L, 0, "ticket"))
                ));
                MessageEnvelope response = ProtocolCodec.read(client.getInputStream());

                assertEquals(ResponseStatus.ERROR, response.status());
                assertEquals("UNSUPPORTED_PROTOCOL_VERSION", response.errorCode());
                assertEquals(0L, server.totalBytesUploaded());
            }
        }
    }

    private static void assertPeerResponseRejected(MessageEnvelope reply) throws Exception {
        try (ServerSocket fakePeer = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = fakePeer.accept()) {
                    MessageEnvelope request = ProtocolCodec.read(socket.getInputStream());
                    ProtocolCodec.write(socket.getOutputStream(), new MessageEnvelope(
                            reply.version(),
                            reply.type(),
                            request.requestId(),
                            reply.status(),
                            reply.errorCode(),
                            reply.message(),
                            reply.payload()
                    ));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            PeerDownloadClient client = new PeerDownloadClient(
                    new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp()));
            ProtocolCompatibilityException error = assertThrows(ProtocolCompatibilityException.class, () ->
                    client.fetchBitfield("127.0.0.1", fakePeer.getLocalPort(), 1L));
            assertTrue(error.getMessage().contains("Peer"));
            assertTrue(error.getMessage().contains("compatible"));
            backend.get(5, TimeUnit.SECONDS);
        }
    }
}
