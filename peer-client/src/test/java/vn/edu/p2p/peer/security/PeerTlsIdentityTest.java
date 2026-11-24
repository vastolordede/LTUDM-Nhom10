package vn.edu.p2p.peer.security;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.peer.transfer.PeerP2pSocketConfig;
import vn.edu.p2p.peer.transfer.PeerP2pSocketFactory;

import javax.net.ssl.SSLHandshakeException;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PeerTlsIdentityTest {
    @Test void stablePeerTlsIdentityWorksAcrossDifferentIpOrHostnameAndPorts() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String id = UUID.randomUUID().toString();
        var factory = new PeerP2pSocketFactory(PeerP2pSocketConfig.tls(null, null, null, null), id, pair);
        try (var server = factory.openServerSocket(0)) {
            var ready = CompletableFuture.runAsync(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getOutputStream().write(42);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            // The connection uses an IP; the certificate SAN contains a stable Peer ID.
            try (Socket client = factory.openClientSocket("127.0.0.1", server.getLocalPort(),
                    id, vn.edu.p2p.common.security.PeerRequestProof.encodePublicKey(pair.getPublic()))) {
                assertEquals(42, client.getInputStream().read());
            }
            ready.get();
        }
    }

    @Test void differentKeyOrPeerIdFailsClosed() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String id = UUID.randomUUID().toString();
        var factory = new PeerP2pSocketFactory(PeerP2pSocketConfig.tls(null, null, null, null), id, pair);
        try (var server = factory.openServerSocket(0)) {
            var accept = CompletableFuture.runAsync(() -> {
                try (Socket s = server.accept()) { s.getInputStream().read(); }
                catch (Exception ignored) { }
            });
            assertThrows(SSLHandshakeException.class, () -> factory.openClientSocket("localhost",
                    server.getLocalPort(), id,
                    vn.edu.p2p.common.security.PeerRequestProof.encodePublicKey(other.getPublic())));
            accept.get();
        }
        assertThrows(SSLHandshakeException.class, () -> factory.openClientSocket(
                "127.0.0.1", 7001, "invalid-id", "invalid-key"));
    }
}
