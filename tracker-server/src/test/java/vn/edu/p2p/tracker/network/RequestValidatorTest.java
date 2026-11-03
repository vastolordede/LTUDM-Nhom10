package vn.edu.p2p.tracker.network;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestValidatorTest {
    private final RequestValidator validator = new RequestValidator();

    @Test
    void shouldRejectMissingRequestId() {
        MessageEnvelope request = new MessageEnvelope(
                1,
                MessageType.HEARTBEAT,
                " ",
                null,
                null,
                null,
                ProtocolCodec.toPayload(new HeartbeatRequest(UUID.randomUUID().toString()))
        );

        RequestValidationException error = assertThrows(
                RequestValidationException.class,
                () -> validator.validateEnvelope(request)
        );

        assertEquals("INVALID_REQUEST", error.errorCode());
    }

    @Test
    void shouldRejectUnsupportedProtocolVersion() {
        MessageEnvelope request = new MessageEnvelope(
                MessageEnvelope.CURRENT_VERSION + 1,
                MessageType.HEARTBEAT,
                "unsupported-version",
                null,
                null,
                null,
                ProtocolCodec.toPayload(new HeartbeatRequest(UUID.randomUUID().toString()))
        );

        RequestValidationException error = assertThrows(
                RequestValidationException.class,
                () -> validator.validateEnvelope(request)
        );

        assertEquals("UNSUPPORTED_PROTOCOL_VERSION", error.errorCode());
    }

    @Test
    void shouldRejectInvalidLoginPort() {
        RequestValidationException error = assertThrows(
                RequestValidationException.class,
                () -> validator.validateLogin(new LoginRequest(
                        "dang",
                        "secret",
                        0,
                        UUID.randomUUID().toString(),
                        "PC"
                ))
        );

        assertEquals("INVALID_PORT", error.errorCode());
    }

    @Test
    void shouldRejectMalformedHeartbeatSessionId() {
        RequestValidationException error = assertThrows(
                RequestValidationException.class,
                () -> validator.validateHeartbeat(new HeartbeatRequest("bad-id"))
        );

        assertEquals("INVALID_SESSION", error.errorCode());
    }
}
