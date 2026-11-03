package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSourceClientServiceProtocolTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void nullTrackerDtoIsRejectedInsteadOfReturningNull() throws Exception {
        assertInvalidTrackerResponse(
                MessageEnvelope.success(MessageType.FILE_SOURCES_RESPONSE, "request", null));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void invalidTrackerDtoIsReportedAsClientProtocolFailure() throws Exception {
        MessageEnvelope invalid = ProtocolCodec.fromJson(
                "{\"version\":3,\"type\":\"FILE_SOURCES_RESPONSE\",\"status\":\"SUCCESS\","
                        + "\"requestId\":\"request\",\"payload\":{\"fileId\":7,\"sources\":[],"
                        + "\"fileHash\":\"not-a-sha256-hash\"}}");
        assertInvalidTrackerResponse(invalid);
    }

    private void assertInvalidTrackerResponse(MessageEnvelope response) throws Exception {
        try (ServerSocket tracker = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = tracker.accept()) {
                    MessageEnvelope request = ProtocolCodec.read(socket.getInputStream());
                    ProtocolCodec.write(socket.getOutputStream(), new MessageEnvelope(
                            response.version(), response.type(), request.requestId(),
                            response.status(), response.errorCode(), response.message(),
                            response.payload()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnection connection = new TrackerConnection(new TrackerConnectionConfig(
                    "127.0.0.1", tracker.getLocalPort(), false, null, null, null),
                    500, 500);
            try (connection) {
                ShareFileClientException error = assertThrows(ShareFileClientException.class,
                        () -> new FileSourceClientService(connection)
                                .findSources(UUID.randomUUID().toString(), 7));
                assertNotNull(error.getMessage());
            }
            backend.get(2, TimeUnit.SECONDS);
        }
    }
}
