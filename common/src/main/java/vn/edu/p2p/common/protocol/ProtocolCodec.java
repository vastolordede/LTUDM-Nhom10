package vn.edu.p2p.common.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * TCP framing for control messages:
 *
 * [4-byte signed big-endian JSON length][N bytes UTF-8 JSON]
 *
 * Do not assume one Socket.read() call equals one complete message.
 */
public final class ProtocolCodec {
    public static final int MAX_JSON_BYTES = 1_048_576; // 1 MiB for control messages

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtocolCodec() {
    }

    public static JsonNode toPayload(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T fromPayload(JsonNode payload, Class<T> type)
            throws JsonProcessingException {
        if (payload == null || payload.isNull()) {
            return null;
        }
        return MAPPER.treeToValue(payload, type);
    }

    public static String toJson(MessageEnvelope message)
            throws JsonProcessingException {
        return MAPPER.writeValueAsString(message);
    }

    public static MessageEnvelope fromJson(String json)
            throws JsonProcessingException {
        return MAPPER.readValue(json, MessageEnvelope.class);
    }

    public static void write(OutputStream output, MessageEnvelope message)
            throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(message);

        if (json.length == 0 || json.length > MAX_JSON_BYTES) {
            throw new ProtocolException(
                    "Invalid control-message size: " + json.length
            );
        }

        DataOutputStream dataOutput = new DataOutputStream(output);
        dataOutput.writeInt(json.length);
        dataOutput.write(json);
        dataOutput.flush();
    }

    public static MessageEnvelope read(InputStream input)
            throws IOException {
        DataInputStream dataInput = new DataInputStream(input);

        final int length;
        try {
            length = dataInput.readInt();
        } catch (EOFException e) {
            throw e;
        }

        if (length <= 0 || length > MAX_JSON_BYTES) {
            throw new ProtocolException(
                    "Invalid control-message length: " + length
            );
        }

        byte[] json = dataInput.readNBytes(length);
        if (json.length != length) {
            throw new EOFException(
                    "Connection closed before a complete message was received"
            );
        }

        try {
            return MAPPER.readValue(json, MessageEnvelope.class);
        } catch (JsonProcessingException e) {
            // Never echo wire JSON: LOGIN_REQUEST may include passwords or session IDs.
            throw new ProtocolException("Malformed JSON control message", e);
        }
    }

    public static void writeWithBinaryPayload(
            OutputStream output,
            MessageEnvelope header,
            byte[] binaryPayload,
            int offset,
            int length
    ) throws IOException {
        if (length < 0 || offset < 0 || offset + length > binaryPayload.length) {
            throw new IllegalArgumentException("Invalid binary payload bounds: length=" + length);
        }

        byte[] json = MAPPER.writeValueAsBytes(header);
        if (json.length == 0 || json.length > MAX_JSON_BYTES) {
            throw new ProtocolException("Invalid control-message size: " + json.length);
        }

        DataOutputStream dataOutput = new DataOutputStream(output);
        dataOutput.writeInt(json.length);
        dataOutput.write(json);
        if (length > 0) {
            dataOutput.write(binaryPayload, offset, length);
        }
        dataOutput.flush();
    }

    public static byte[] readBinaryPayload(InputStream input, int expectedLength)
            throws IOException {
        if (expectedLength < 0) {
            throw new ProtocolException("Negative binary payload length: " + expectedLength);
        }
        if (expectedLength == 0) {
            return new byte[0];
        }

        DataInputStream dataInput = new DataInputStream(input);
        byte[] data = dataInput.readNBytes(expectedLength);
        if (data.length != expectedLength) {
            throw new EOFException(
                    "Connection closed before receiving full piece payload: expected "
                            + expectedLength + " bytes, got " + data.length
            );
        }
        return data;
    }
}
