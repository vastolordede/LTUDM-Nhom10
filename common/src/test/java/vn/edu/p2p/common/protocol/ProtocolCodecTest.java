package vn.edu.p2p.common.protocol;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolCodecTest {

    @Test
    void shouldRoundTripLengthPrefixedLoginRequest() throws Exception {
        String requestId = UUID.randomUUID().toString();
        LoginRequest login = new LoginRequest(
                "dang",
                "secret",
                7001,
                UUID.randomUUID().toString(),
                "DESKTOP-TEST",
                PeerRequestProof.encodePublicKey(PeerRequestProof.generateKeyPair().getPublic())
        );

        MessageEnvelope original = MessageEnvelope.request(
                MessageType.LOGIN_REQUEST,
                requestId,
                ProtocolCodec.toPayload(login)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProtocolCodec.write(out, original);

        MessageEnvelope decoded = ProtocolCodec.read(
                new ByteArrayInputStream(out.toByteArray())
        );

        assertEquals(MessageType.LOGIN_REQUEST, decoded.type());
        assertEquals(requestId, decoded.requestId());
        assertNull(decoded.status());

        LoginRequest decodedLogin = ProtocolCodec.fromPayload(
                decoded.payload(),
                LoginRequest.class
        );

        assertEquals(login, decodedLogin);
    }

    @Test
    void shouldRejectUnknownDtoFieldsWhenDeserializingPayload() throws Exception {
        MessageEnvelope response = ProtocolCodec.fromJson(
                "{\"version\":" + MessageEnvelope.CURRENT_VERSION
                        + ",\"type\":\"FILE_SOURCES_RESPONSE\","
                        + "\"requestId\":\"legacy-client\","
                        + "\"payload\":{\"fileId\":7,\"sources\":[],"
                        + "\"fileHash\":\"" + "a".repeat(64) + "\"}}"
        );

        assertThrows(UnrecognizedPropertyException.class,
                () -> ProtocolCodec.fromPayload(response.payload(), LegacyFileSourcesResponse.class));
    }

    private record LegacyFileSourcesResponse(long fileId, List<Object> sources) {}
}
