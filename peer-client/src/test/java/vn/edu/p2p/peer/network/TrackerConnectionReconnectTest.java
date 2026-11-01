package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** R40: stale sockets must not permanently break login after Tracker timeout. */
class TrackerConnectionReconnectTest {
    @Test
    void nextLoginRecoversFromRemoteCloseAfterInvalidCredentials() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket first = server.accept()) {
                    MessageEnvelope req = ProtocolCodec.read(first.getInputStream());
                    ProtocolCodec.write(first.getOutputStream(), MessageEnvelope.error(
                            MessageType.LOGIN_RESPONSE, req.requestId(),
                            "INVALID_CREDENTIALS", "No match"));
                    // Mimic Tracker closing idle client connection.
                } catch (Exception e) { throw new RuntimeException(e); }
                try (Socket second = server.accept()) {
                    MessageEnvelope req = ProtocolCodec.read(second.getInputStream());
                    ProtocolCodec.write(second.getOutputStream(), MessageEnvelope.success(
                            MessageType.LOGIN_RESPONSE, req.requestId(),
                            ProtocolCodec.toPayload(java.util.Map.of("peerId", UUID.randomUUID().toString(),
                                  "sessionId", UUID.randomUUID().toString(), "heartbeatIntervalSeconds", 10))));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            TrackerConnectionConfig cfg = new TrackerConnectionConfig("127.0.0.1", server.getLocalPort(),
                    false, null, null, null);
            try (TrackerConnection client = new TrackerConnection(cfg)) {
                MessageEnvelope firstReq = login();
                assertEquals(ResponseStatus.ERROR, client.request(firstReq).status());
                MessageEnvelope second = client.request(login());
                assertEquals(ResponseStatus.SUCCESS, second.status());
            }
            backend.get(5, TimeUnit.SECONDS);
        }
    }
    private static MessageEnvelope login() {
        return MessageEnvelope.request(MessageType.LOGIN_REQUEST, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new LoginRequest("alice", "correct password", 7001,
                        UUID.randomUUID().toString(), "laptop")));
    }
}
