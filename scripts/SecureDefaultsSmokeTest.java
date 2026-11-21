import vn.edu.p2p.common.config.LocalEnv;
import vn.edu.p2p.common.security.SecurityProfile;
import vn.edu.p2p.common.security.TlsContextFactory;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.peer.network.PeerSettings;
import vn.edu.p2p.peer.network.UserTrackerSettingsStore;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;
import vn.edu.p2p.peer.transfer.PeerP2pSocketFactory;
import vn.edu.p2p.peer.transfer.PeerP2pSocketConfig;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/** JDK-only smoke test for a provisioned test Peer; does not start Tracker/PostgreSQL/JavaFX. */
public final class SecureDefaultsSmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: SecureDefaultsSmokeTest PEER_PROFILE_DIR");
        Path peer = Path.of(args[0]).toAbsolutePath();
        Path home = Files.createTempDirectory("p2p-secure-user-");
        Path profile = home.resolve(".fileshare-p2p");
        Files.createDirectories(profile);
        Files.copy(peer.resolve(".env"), profile.resolve(".env"));
        Path certs = profile.resolve("certs");
        Files.createDirectories(certs);
        try (var files = Files.list(peer.resolve("certs"))) {
            files.forEach(file -> {
                try { Files.copy(file, certs.resolve(file.getFileName())); }
                catch (IOException ex) { throw new RuntimeException(ex); }
            });
        }
        System.setProperty("user.home", home.toString());
        require(SecurityProfile.configuredMode() == SecurityProfile.Mode.PRODUCTION, "Production default");
        require(SecurityProfile.tlsRequiredByDefault(SecurityProfile.Mode.PRODUCTION, false), "Legacy TLS false ignored");
        require(!PeerAccessTickets.optionalPeerKeys().isEmpty(), "Ticket public key provisioned");
        PeerSettings tracker = PeerSettings.load();
        require(tracker.trackerTlsEnabled() && tracker.trackerTrustStorePath() != null, "Tracker TLS+CA provisioned");
        PeerP2pSocketConfig p2p = PeerP2pSocketConfig.fromEnvironment();
        require(p2p.tlsEnabled(), "P2P TLS forced");
        require(Files.isRegularFile(p2p.keyStorePath()), "Distinct Peer keystore exists");

        Path oldSettings = profile.resolve("tracker-settings.properties");
        Files.writeString(oldSettings, "tracker.host=attack.local\ntracker.port=22\ntracker.tls=false\n"
                + "tracker.truststore.path=other.p12\n");
        var config = new UserTrackerSettingsStore(oldSettings).load(tracker);
        require(config.host().equals(tracker.trackerHost()), "Untrusted saved host ignored");
        require(config.port() == tracker.trackerPort(), "Untrusted saved port ignored");
        require(config.tls(), "Untrusted saved TLS toggle ignored");
        boolean trackerDenied = false;
        try (var ignored = new TrackerConnection(
                new TrackerConnectionConfig("localhost", 9000, false, null, "", "PKCS12"))) {
            // Must never reach here in production.
        } catch (IllegalStateException expected) { trackerDenied = true; }
        require(trackerDenied, "Plain Tracker socket refused in production");
        boolean peerDenied = false;
        try { new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp()); }
        catch (IllegalStateException expected) { peerDenied = true; }
        require(peerDenied, "Plain P2P sockets refused in production");

        SSLContext serverContext = TlsContextFactory.createServerContext(
                p2p.keyStorePath(), p2p.keyStorePassword(), p2p.keyStoreType());
        SSLContext clientContext = TlsContextFactory.createClientContext(
                p2p.trustStorePath(), p2p.trustStorePassword(), p2p.trustStoreType());
        testHandshake(serverContext, clientContext, "localhost", true);
        testHandshake(serverContext, clientContext, "127.0.0.1", false);
        boolean missingStoreDenied = false;
        try { TlsContextFactory.createClientContext(profile.resolve("missing.p12"), "x", "PKCS12"); }
        catch (IOException expected) { missingStoreDenied = true; }
        require(missingStoreDenied, "Missing truststore denied");
        System.out.println("PASS 10 checks: production TLS policy, ticket trust, profile load, "
                + "legacy settings ignored, no plaintext Tracker/P2P, valid SAN, "
                + "mismatched SAN rejected, missing CA rejected");
    }

    private static void testHandshake(SSLContext serverCtx, SSLContext clientCtx,
                                      String host, boolean shouldPass) throws Exception {
        try (SSLServerSocket server = (SSLServerSocket) serverCtx.getServerSocketFactory()
                .createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Throwable> serverError = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) { socket.startHandshake(); }
                catch (Throwable e) { serverError.set(e); }
            });
            worker.start();
            boolean passed = false;
            try (SSLSocket socket = (SSLSocket) clientCtx.getSocketFactory()
                    .createSocket(host, server.getLocalPort())) {
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                socket.startHandshake();
                passed = true;
            } catch (javax.net.ssl.SSLHandshakeException expected) {
                // Expected for the wrong SAN.
            } finally {
                worker.join(10000);
                if (worker.isAlive()) worker.interrupt();
            }
            require(passed == shouldPass, "TLS SAN verification for " + host);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError("FAIL: " + message);
    }
}
