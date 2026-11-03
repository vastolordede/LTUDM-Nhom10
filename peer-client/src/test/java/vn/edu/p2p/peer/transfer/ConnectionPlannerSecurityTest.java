package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ConnectException;
import java.security.cert.CertificateExpiredException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPlannerSecurityTest {
    @Test
    void tlsIdentityAndHandshakeFailuresMustNotTriggerRelayFallback() {
        assertFalse(ConnectionPlanner.mayRetryViaRelay(new SSLHandshakeException("untrusted certificate")));
        assertFalse(ConnectionPlanner.mayRetryViaRelay(
                new IOException("wrapped", new SSLHandshakeException("SAN mismatch"))));
        SSLHandshakeException expired = new SSLHandshakeException("certificate rejected");
        expired.initCause(new CertificateExpiredException("expired"));
        assertFalse(ConnectionPlanner.mayRetryViaRelay(expired));
        assertFalse(ConnectionPlanner.mayRetryViaRelay(
                new SSLPeerUnverifiedException("hostname mismatch")));
    }

    @Test
    void plainNetworkFailuresCanStillUseRelay() {
        assertTrue(ConnectionPlanner.mayRetryViaRelay(new ConnectException("connection refused")));
    }
}
