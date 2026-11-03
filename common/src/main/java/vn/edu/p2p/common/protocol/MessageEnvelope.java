package vn.edu.p2p.common.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Common envelope for every control message.
 *
 * Request:
 * {
 *   "version": CURRENT_VERSION,
 *   "type": "LOGIN_REQUEST",
 *   "requestId": "...",
 *   "payload": { ... }
 * }
 *
 * Response can additionally contain status/errorCode/message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageEnvelope(
        int version,
        MessageType type,
        String requestId,
        ResponseStatus status,
        String errorCode,
        String message,
        JsonNode payload
) {
    public static final int CURRENT_VERSION = 3;

    public MessageEnvelope {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(requestId, "requestId");
    }

    public static MessageEnvelope request(
            MessageType type,
            String requestId,
            JsonNode payload
    ) {
        return new MessageEnvelope(
                CURRENT_VERSION,
                type,
                requestId,
                null,
                null,
                null,
                payload
        );
    }

    public static MessageEnvelope success(
            MessageType type,
            String requestId,
            JsonNode payload
    ) {
        return new MessageEnvelope(
                CURRENT_VERSION,
                type,
                requestId,
                ResponseStatus.SUCCESS,
                null,
                null,
                payload
        );
    }

    public static MessageEnvelope error(
            MessageType type,
            String requestId,
            String errorCode,
            String message
    ) {
        return new MessageEnvelope(
                CURRENT_VERSION,
                type,
                requestId,
                ResponseStatus.ERROR,
                errorCode,
                message,
                null
        );
    }
}
