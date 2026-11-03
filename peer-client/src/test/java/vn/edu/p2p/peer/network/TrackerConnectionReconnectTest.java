package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.common.security.PeerRequestProof;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** R40: stale sockets must not permanently break login after Tracker timeout. */
class TrackerConnectionReconnectTest {
    @TempDir
    Path tempDir;

    @Test
    void readTimeoutReturnsWithinConfiguredDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    ProtocolCodec.read(socket.getInputStream());
                    release.await();
                } catch (Exception e) {
                    if (!(e instanceof java.net.SocketException)) throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig config = plainConfig(server.getLocalPort());
            try (TrackerConnection client = new TrackerConnection(config, 500, 150)) {
                long started = System.nanoTime();
                IOException error = assertThrows(IOException.class, () -> client.request(login()));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertInstanceOf(SocketTimeoutException.class, error);
                assertEquals(TrackerConnectionDiagnostics.FailureKind.READ_TIMEOUT,
                        TrackerConnectionDiagnostics.classify(error, config));
                assertTrue(elapsed < 1_000, "read timeout exceeded configured bound");
            } finally {
                release.countDown();
            }
            backend.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void realTlsAndPlaintextEndpointsReportModeMismatches() throws Exception {
        String storePassword = UUID.randomUUID().toString();
        Path storePath = createTestKeyStore(storePassword);
        SSLContext serverContext = createServerContext(storePath, storePassword);

        try (ServerSocket plaintextServer = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = plaintextServer.accept()) {
                    assertTrue(socket.getInputStream().readNBytes(5).length > 0);
                    socket.getOutputStream().write(
                            "HTTP/1.1 400 Bad Request\r\n\r\n"
                                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig tlsConfig = new TrackerConnectionConfig("127.0.0.1",
                    plaintextServer.getLocalPort(), true, storePath, storePassword, "PKCS12");
            try (TrackerConnection tlsClient = new TrackerConnection(tlsConfig, 500, 500)) {
                IOException error = assertThrows(IOException.class, () -> tlsClient.request(login()));
                assertEquals(TrackerConnectionDiagnostics.FailureKind.CLIENT_TLS_SERVER_PLAINTEXT,
                        TrackerConnectionDiagnostics.classify(error, tlsConfig),
                        error + " cause=" + error.getCause());
            }
            backend.get(2, TimeUnit.SECONDS);
        }

        try (SSLServerSocket tlsServer = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (SSLSocket socket = (SSLSocket) tlsServer.accept()) {
                    socket.setSoTimeout(1_000);
                    socket.startHandshake();
                } catch (javax.net.ssl.SSLException expectedOnProtocolMismatch) {
                    // The plaintext client sends an application frame instead of a TLS ClientHello.
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig plainConfig = plainConfig(tlsServer.getLocalPort());
            try (TrackerConnection plainClient = new TrackerConnection(plainConfig, 500, 500)) {
                IOException error = assertThrows(IOException.class, () -> plainClient.request(login()));
                assertEquals(TrackerConnectionDiagnostics.FailureKind.CLIENT_PLAINTEXT_SERVER_TLS,
                        TrackerConnectionDiagnostics.classify(error, plainConfig));
            }
            backend.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsTrackerResponseWithUnsupportedProtocolVersion() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    MessageEnvelope request = ProtocolCodec.read(socket.getInputStream());
                    ProtocolCodec.write(socket.getOutputStream(), new MessageEnvelope(
                            MessageEnvelope.CURRENT_VERSION - 1, MessageType.LOGIN_RESPONSE,
                            request.requestId(), ResponseStatus.SUCCESS, null, null,
                            ProtocolCodec.toPayload(java.util.Map.of())));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig config = plainConfig(server.getLocalPort());
            try (TrackerConnection client = new TrackerConnection(config)) {
                ProtocolCompatibilityException error = assertThrows(
                        ProtocolCompatibilityException.class, () -> client.request(login()));
                assertTrue(error.getMessage().contains("Tracker"));
                assertTrue(error.getMessage().contains("compatible"));
            }
            backend.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void mapsTrackerUnsupportedProtocolErrorToCompatibilityFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    MessageEnvelope request = ProtocolCodec.read(socket.getInputStream());
                    ProtocolCodec.write(socket.getOutputStream(), MessageEnvelope.error(
                            MessageType.LOGIN_RESPONSE, request.requestId(),
                            "UNSUPPORTED_PROTOCOL_VERSION", "Unsupported protocol version"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig config = plainConfig(server.getLocalPort());
            try (TrackerConnection client = new TrackerConnection(config)) {
                ProtocolCompatibilityException error = assertThrows(
                        ProtocolCompatibilityException.class, () -> client.request(login()));
                assertTrue(error.getMessage().contains("Tracker"));
                assertTrue(error.getMessage().contains("compatible"));
            }
            backend.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nextLoginRecoversFromRemoteCloseAfterInvalidCredentials() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> backend = CompletableFuture.runAsync(() -> {
                try (Socket first = server.accept()) {
                    MessageEnvelope request = ProtocolCodec.read(first.getInputStream());
                    ProtocolCodec.write(first.getOutputStream(), MessageEnvelope.error(
                            MessageType.LOGIN_RESPONSE, request.requestId(),
                            "INVALID_CREDENTIALS", "No match"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try (Socket second = server.accept()) {
                    MessageEnvelope request = ProtocolCodec.read(second.getInputStream());
                    ProtocolCodec.write(second.getOutputStream(), MessageEnvelope.success(
                            MessageType.LOGIN_RESPONSE, request.requestId(),
                            ProtocolCodec.toPayload(java.util.Map.of(
                                    "peerId", UUID.randomUUID().toString(),
                                    "sessionId", UUID.randomUUID().toString(),
                                    "heartbeatIntervalSeconds", 10))));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            TrackerConnectionConfig config = plainConfig(server.getLocalPort());
            try (TrackerConnection client = new TrackerConnection(config)) {
                assertEquals(ResponseStatus.ERROR, client.request(login()).status());
                assertEquals(ResponseStatus.SUCCESS, client.request(login()).status());
            }
            backend.get(5, TimeUnit.SECONDS);
        }
    }

    private Path createTestKeyStore(String password) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "keytool.exe" : "keytool").toString();
        Path keyStore = tempDir.resolve("ephemeral-tracker-test.p12");
        Process process = new ProcessBuilder(executable, "-genkeypair", "-alias", "tracker-test",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("keytool timed out");
        }
        assertEquals(0, process.exitValue(), "could not create ephemeral TLS test certificate");
        return keyStore;
    }

    private static SSLContext createServerContext(Path keyStorePath, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, password.toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    private static TrackerConnectionConfig plainConfig(int port) {
        return new TrackerConnectionConfig("127.0.0.1", port, false, null, null, null);
    }

    private static MessageEnvelope login() {
        return MessageEnvelope.request(MessageType.LOGIN_REQUEST, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new LoginRequest("alice", "test-password", 7001,
                        UUID.randomUUID().toString(), "laptop",
                        PeerRequestProof.encodePublicKey(PeerRequestProof.generateKeyPair().getPublic()))));
    }
}
