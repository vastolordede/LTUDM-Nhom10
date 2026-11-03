package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.security.TlsTestCertificates;
import vn.edu.p2p.peer.security.TlsTestCertificates.Material;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerP2pTlsNegativeTest {
    @TempDir
    Path tempDir;

    @Test
    void peerRejectsCertificateFromUntrustedTrustStore() throws Exception {
        Material server = TlsTestCertificates.create(tempDir, "IP:127.0.0.1", null);
        Material unrelatedTrust = TlsTestCertificates.createUnrelatedTrustStore(tempDir);
        assertPeerHandshakeRejected(server, server, unrelatedTrust, "127.0.0.1");
    }

    @Test
    void peerRejectsExpiredCertificate() throws Exception {
        Material expired = TlsTestCertificates.create(
                tempDir, "IP:127.0.0.1", "2020/01/01 00:00:00");
        assertPeerHandshakeRejected(expired, expired, expired, "127.0.0.1");
    }

    @Test
    void peerRejectsCertificateWithMismatchedHostname() throws Exception {
        Material server = TlsTestCertificates.create(tempDir, "DNS:localhost", null);
        assertPeerHandshakeRejected(server, server, server, "127.0.0.1");
    }

    @Test
    void peerAcceptsTrustedCertificateWithMatchingSan() throws Exception {
        Material material = TlsTestCertificates.create(tempDir, "IP:127.0.0.1", null);
        PeerP2pSocketFactory serverFactory = new PeerP2pSocketFactory(config(material, material));
        try (ServerSocket server = serverFactory.openServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            PeerP2pSocketFactory clientFactory = new PeerP2pSocketFactory(config(material, material));
            try (var socket = clientFactory.openClientSocket("127.0.0.1", server.getLocalPort())) {
                assertTrue(socket.isConnected());
            }
            backend.get(2, TimeUnit.SECONDS);
        }
    }

    private void assertPeerHandshakeRejected(Material serverMaterial, Material clientKeyMaterial,
                                             Material trustMaterial, String host) throws Exception {
        PeerP2pSocketFactory serverFactory =
                new PeerP2pSocketFactory(config(serverMaterial, serverMaterial));
        try (ServerSocket server = serverFactory.openServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(1_000);
                    socket.startHandshake();
                } catch (SSLException expectedOnRejectedClientCertificate) {
                    // The client rejects the peer certificate and ends the handshake.
                } catch (java.net.SocketException expectedOnClosedClientSocket) {
                    // Some platforms report an aborted socket instead of a TLS alert.
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            PeerP2pSocketFactory clientFactory =
                    new PeerP2pSocketFactory(config(clientKeyMaterial, trustMaterial));
            assertThrows(IOException.class,
                    () -> clientFactory.openClientSocket(host, server.getLocalPort()));
            backend.get(2, TimeUnit.SECONDS);
        }
    }

    private static PeerP2pSocketConfig config(Material keyMaterial, Material trustMaterial) {
        return new PeerP2pSocketConfig(true,
                keyMaterial.keyStore(), keyMaterial.password(), "PKCS12",
                trustMaterial.trustStore(), trustMaterial.password(), "PKCS12",
                500, 700);
    }
}
