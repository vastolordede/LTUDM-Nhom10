package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ProtocolException;
import vn.edu.p2p.peer.security.TlsTestCertificates;
import javax.net.ssl.SSLHandshakeException;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.BindException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TrackerConnectionDiagnosticsTest {
    @TempDir
    Path tempDir;

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

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void categorizesPeerFirewallNatAndRelayFailuresWithSafeGuidance() {
        var portFailure = TrackerConnectionDiagnostics.classify(new BindException("local-port-secret"));
        var directFailure = TrackerConnectionDiagnostics.classifyDirectPeer(
                new ConnectException("peer-ip-secret"), true);
        var firewallFailure = TrackerConnectionDiagnostics.classifyDirectPeer(
                new SocketTimeoutException("connect timed out: peer-ip-secret"), true);
        var natFailure = TrackerConnectionDiagnostics.classifyNatProbe(
                new NoRouteToHostException("tracker-ip-secret"));
        var relayFailure = TrackerConnectionDiagnostics.classifyRelay(
                new SocketTimeoutException("relay ticket-secret"));

        assertEquals(TrackerConnectionDiagnostics.FailureKind.PEER_PORT_UNAVAILABLE, portFailure);
        assertEquals(TrackerConnectionDiagnostics.FailureKind.DIRECT_PEER_UNREACHABLE, directFailure);
        assertEquals(TrackerConnectionDiagnostics.FailureKind.FIREWALL_SUSPECTED, firewallFailure);
        assertEquals(TrackerConnectionDiagnostics.FailureKind.NAT_PROBE_FAILED, natFailure);
        assertEquals(TrackerConnectionDiagnostics.FailureKind.RELAY_UNAVAILABLE, relayFailure);

        for (var kind : List.of(portFailure, directFailure, firewallFailure, natFailure, relayFailure)) {
            var diagnostic = TrackerConnectionDiagnostics.diagnostic(kind);
            assertFalse(diagnostic.message().contains("secret"), diagnostic.message());
            assertFalse(diagnostic.guidance().contains("secret"), diagnostic.guidance());
            assertTrue(diagnostic.remediation().size() >= 2, diagnostic.guidance());
            assertFalse(diagnostic.guidance().contains("peer-ip-secret"), diagnostic.guidance());
        }
        for (var kind : TrackerConnectionDiagnostics.FailureKind.values()) {
            var diagnostic = TrackerConnectionDiagnostics.diagnostic(kind);
            assertTrue(diagnostic.remediation().size() <= 3, kind.toString());
            if (kind != TrackerConnectionDiagnostics.FailureKind.NONE) {
                assertTrue(diagnostic.remediation().size() >= 2, kind.toString());
            }
            assertFalse(diagnostic.guidance().contains("127.0.0.1"), kind.toString());
            assertFalse(diagnostic.guidance().contains("secret"), kind.toString());
        }
        assertEquals(TrackerConnectionDiagnostics.FailureKind.TLS_HANDSHAKE_FAILED,
                TrackerConnectionDiagnostics.classifyRelay(
                        new SSLHandshakeException("private-endpoint-secret")));
        assertEquals(TrackerConnectionDiagnostics.FailureKind.TLS_HANDSHAKE_FAILED,
                TrackerConnectionDiagnostics.classifyDirectPeer(
                        new SSLHandshakeException("private-endpoint-secret"), true));
        assertEquals(TrackerConnectionDiagnostics.FailureKind.DIRECT_PEER_UNREACHABLE,
                TrackerConnectionDiagnostics.classifyDirectPeer(
                        new ConnectException("peer-ip-secret"), false));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void tlsDiagnosticLogDoesNotExposeTrustStorePathOrPassword() throws Exception {
        var material = TlsTestCertificates.create(tempDir, "IP:127.0.0.1", null);
        Logger logger = Logger.getLogger(TrackerConnectionDiagnostics.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Level previousLevel = logger.getLevel();
        boolean previousParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try (ServerSocket plaintextEndpoint = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = plaintextEndpoint.accept()) {
                    socket.setSoTimeout(2_000);
                    assertTrue(socket.getInputStream().readNBytes(5).length > 0);
                    socket.getOutputStream().write(
                            "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig config = new TrackerConnectionConfig("127.0.0.1",
                    plaintextEndpoint.getLocalPort(), true, material.trustStore(),
                    material.password(), "PKCS12");
            String result = TrackerConnectionDiagnostics.probe(config);
            backend.get(3, TimeUnit.SECONDS);
            assertTrue(result.contains("TLS"));
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousParentHandlers);
            logger.setLevel(previousLevel);
        }

        assertFalse(records.isEmpty());
        assertTrue(records.stream().allMatch(record -> record.getThrown() == null));
        String text = records.stream().map(LogRecord::getMessage).reduce("", String::concat);
        assertFalse(text.contains(tempDir.toString()));
        assertFalse(text.contains(material.password()));
        assertFalse(text.contains("private-key"));
    }
}
