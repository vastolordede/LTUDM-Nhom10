package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ProtocolException;
import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateExpiredException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TrackerConnectionDiagnosticsTest {
    @Test void descriptionsAreActionable() {
        assertTrue(TrackerConnectionDiagnostics.explain(new SSLHandshakeException("bad cert")).contains("TLS"));
        assertTrue(TrackerConnectionDiagnostics.explain(new UnknownHostException()).contains("DNS"));
        assertTrue(TrackerConnectionDiagnostics.explain(new ConnectException()).contains("port"));
    }

    @Test void explainsProtocolCompatibilityFailuresClearly() {
        String message = TrackerConnectionDiagnostics.explain(
                new ProtocolCompatibilityException("Tracker", "unsupported version"));
        assertTrue(message.contains("Tracker"));
        assertTrue(message.contains("không tương thích"));
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("cập nhật"));
    }

    @Test
    void mapsNetworkFailureTypesToSafeVietnameseMessages() {
        Map<TrackerConnectionDiagnostics.FailureKind, Throwable> failures = Map.of(
                TrackerConnectionDiagnostics.FailureKind.CONNECTION_REFUSED,
                new ConnectException("sensitive-endpoint"),
                TrackerConnectionDiagnostics.FailureKind.HOST_UNRESOLVED,
                new UnknownHostException("sensitive-endpoint"),
                TrackerConnectionDiagnostics.FailureKind.CONNECT_TIMEOUT,
                new SocketTimeoutException("Connect timed out: sensitive-endpoint"),
                TrackerConnectionDiagnostics.FailureKind.READ_TIMEOUT,
                new SocketTimeoutException("Read timed out: sensitive-value"),
                TrackerConnectionDiagnostics.FailureKind.CLIENT_TLS_SERVER_PLAINTEXT,
                new SSLHandshakeException("Unrecognized SSL message: sensitive-value"),
                TrackerConnectionDiagnostics.FailureKind.CLIENT_PLAINTEXT_SERVER_TLS,
                new ProtocolException("Invalid control-message length: 369098752"),
                TrackerConnectionDiagnostics.FailureKind.TLS_HANDSHAKE_FAILED,
                new SSLHandshakeException("handshake failure: sensitive-value"),
                TrackerConnectionDiagnostics.FailureKind.CERTIFICATE_INVALID,
                new CertificateExpiredException("sensitive-certificate-path"),
                TrackerConnectionDiagnostics.FailureKind.PROTOCOL_VERSION_MISMATCH,
                new ProtocolCompatibilityException("Tracker", "sensitive-version"),
                TrackerConnectionDiagnostics.FailureKind.REMOTE_CLOSED,
                new EOFException("sensitive-session")
        );

        failures.forEach((expected, failure) -> {
            assertEquals(expected, TrackerConnectionDiagnostics.classify(failure));
            String message = TrackerConnectionDiagnostics.explain(failure);
            assertFalse(message.chars().allMatch(character -> character < 128), message);
            assertFalse(message.contains("sensitive"), message);
        });
        SSLHandshakeException certificateFailure = new SSLHandshakeException("certificate failure");
        certificateFailure.initCause(new CertificateExpiredException("secret"));
        assertEquals(TrackerConnectionDiagnostics.FailureKind.CERTIFICATE_INVALID,
                TrackerConnectionDiagnostics.classify(certificateFailure));
        assertEquals(TrackerConnectionDiagnostics.FailureKind.CLIENT_TLS_SERVER_PLAINTEXT,
                TrackerConnectionDiagnostics.classify(new EOFException(),
                        new TrackerConnectionConfig("127.0.0.1", 9000, true,
                                java.nio.file.Path.of("unused"), java.util.UUID.randomUUID().toString(),
                                "PKCS12")));
    }
}
