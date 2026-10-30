package vn.edu.p2p.common.protocol;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolCodecLimitsTest {
    @Test void rejectsOverLimitBinaryBeforeAllocating() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.readBinaryPayload(
                new ByteArrayInputStream(new byte[0]), ProtocolCodec.MAX_BINARY_PIECE_BYTES + 1));
    }
    @Test void rejectsNegativeBinaryLength() {
        assertThrows(ProtocolException.class, () -> ProtocolCodec.readBinaryPayload(
                new ByteArrayInputStream(new byte[0]), -1));
    }
    @Test void maliciousJsonDoesNotLeakPayloadInError() throws IOException {
        byte[] raw = "{bad-password-secret}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var stream = new java.io.ByteArrayOutputStream();
        new java.io.DataOutputStream(stream).writeInt(raw.length);
        stream.write(raw);
        IOException exception = assertThrows(IOException.class,
                () -> ProtocolCodec.read(new ByteArrayInputStream(stream.toByteArray())));
        assertFalse(exception.getMessage().contains("secret"));
    }
}
