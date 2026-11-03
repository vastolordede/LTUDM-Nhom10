package vn.edu.p2p.common.protocol;

/** Indicates that a remote endpoint speaks an unsupported protocol version. */
public final class ProtocolCompatibilityException extends ProtocolException {
    private final String endpoint;

    public ProtocolCompatibilityException(String endpoint, String detail) {
        super("Protocol version mismatch with " + endpoint + ": " + detail
                + ". Update the application and " + endpoint + " to compatible versions.");
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }

    public static void validateResponse(MessageEnvelope response, String endpoint)
            throws ProtocolCompatibilityException {
        if (response.version() != MessageEnvelope.CURRENT_VERSION) {
            throw new ProtocolCompatibilityException(endpoint,
                    "remote version " + response.version() + " is not supported");
        }
        if ("UNSUPPORTED_PROTOCOL_VERSION".equals(response.errorCode())) {
            throw new ProtocolCompatibilityException(endpoint,
                    "the remote endpoint rejected the protocol version");
        }
    }
}
