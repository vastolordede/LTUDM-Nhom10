package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadControllerDiagnosticsTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void ticketTlsAndNetworkErrorsAreActionableAndRedacted() {
        String ticket = DownloadController.safeDownloadFailure(
                new IOException("Ticket refresh failed: token=secret C:\\private\\ticket"));
        String tls = DownloadController.safeDownloadFailure(
                new SSLHandshakeException("bad certificate C:\\private\\truststore"));
        String network = DownloadController.safeDownloadFailure(
                new ConnectException("host=private-host password=secret"));

        assertTrue(ticket.contains("đăng nhập lại"));
        assertTrue(tls.contains("TLS"));
        assertTrue(network.contains("Không kết nối được"));
        for (String message : new String[] {ticket, tls, network}) {
            assertFalse(message.contains("secret"), message);
            assertFalse(message.contains("private"), message);
            assertFalse(message.contains("C:\\"), message);
        }
    }
}
