package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ConnectException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionPlannerSecurityTest {
    @Test void tlsFailuresMustNotTriggerRelayFallback() {
        assertFalse(ConnectionPlanner.mayRetryViaRelay(new SSLHandshakeException("untrusted certificate")));
        assertFalse(ConnectionPlanner.mayRetryViaRelay(
                new IOException("wrapped", new SSLHandshakeException("SAN mismatch"))));
    }
    @Test void connectionFailuresCanUseRelay() {
        assertTrue(ConnectionPlanner.mayRetryViaRelay(new ConnectException("connection refused")));
    }
}
