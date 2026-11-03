package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;
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
        assertTrue(message.contains("cập nhật"));
    }
}
