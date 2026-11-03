package vn.edu.p2p.peer.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import vn.edu.p2p.common.security.TlsContextFactory;

/** Máy khách của cổng Rendezvous trên Tracker: NAT probe và relay. */
public final class RendezvousClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    public record Probe(String observedIp, boolean reachable) {}
    private record OpenedSocket(Socket socket, Socket cancellationHandle, long epoch) {}

    private final String host;
    private final int port;
    private final SSLContext trackedTlsContext;
    private final Set<Socket> pendingHandshakes = ConcurrentHashMap.newKeySet();
    private final AtomicLong cancellationEpoch = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();

    public RendezvousClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.trackedTlsContext = null;
    }

    /** D81: use the active selected Tracker TCP/TLS configuration for Rendezvous. */
    public RendezvousClient(PeerSettings settings, int port) {
        this(settings.trackerConnectionConfig(), port);
    }

    public RendezvousClient(TrackerConnectionConfig config, int port) {
        this.host = config.host();
        this.port = port;
        this.trackedTlsContext = config.tlsEnabled() ? createTlsContext(config) : null;
    }

    private static SSLContext createTlsContext(TrackerConnectionConfig config) {
        try {
            return TlsContextFactory.createClientContext(
                    config.trustStorePath(),
                    config.trustStorePassword(),
                    config.trustStoreType());
        } catch (IOException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize Rendezvous TLS context", e);
        }
    }

    private OpenedSocket openForHandshake() throws IOException {
        if (closed.get()) throw new IOException("Rendezvous client is closed");
        long epoch = cancellationEpoch.get();
        Socket transport = new Socket();
        pendingHandshakes.add(transport);
        if (closed.get() || cancellationEpoch.get() != epoch) {
            pendingHandshakes.remove(transport);
            closeQuietly(transport);
            throw new IOException(closed.get()
                    ? "Rendezvous client is closed" : "Rendezvous handshake cancelled");
        }
        try {
            transport.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            transport.setTcpNoDelay(true);
            Socket socket = transport;
            if (trackedTlsContext != null) {
                SSLSocket sslSocket = (SSLSocket) trackedTlsContext.getSocketFactory()
                        .createSocket(transport, host, port, true);
                sslSocket.setEnabledProtocols(supportedProtocols(sslSocket.getSupportedProtocols()));
                sslSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                sslSocket.setTcpNoDelay(true);
                SSLParameters parameters = sslSocket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                sslSocket.setSSLParameters(parameters);
                sslSocket.startHandshake();
                socket = sslSocket;
            }
            if (closed.get() || cancellationEpoch.get() != epoch) {
                throw new IOException("Rendezvous handshake cancelled");
            }
            return new OpenedSocket(socket, transport, epoch);
        } catch (IOException | RuntimeException e) {
            pendingHandshakes.remove(transport);
            closeQuietly(transport);
            if (closed.get() || cancellationEpoch.get() != epoch) {
                throw new IOException("Rendezvous handshake cancelled", e);
            }
            throw e;
        }
    }

    private static String[] supportedProtocols(String[] supported) {
        java.util.List<String> preferred = new java.util.ArrayList<>();
        Set<String> available = Set.of(supported);
        if (available.contains("TLSv1.3")) preferred.add("TLSv1.3");
        if (available.contains("TLSv1.2")) preferred.add("TLSv1.2");
        if (preferred.isEmpty()) {
            throw new IllegalStateException("JVM does not support TLSv1.2 or TLSv1.3");
        }
        return preferred.toArray(String[]::new);
    }

    /** D77. */
    public Probe probe(String sessionId, int listeningPort) throws IOException {
        OpenedSocket opened = openForHandshake();
        try (Socket socket = opened.socket()) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF("PROBE");
            out.writeUTF(sessionId);
            out.writeInt(listeningPort);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String status = in.readUTF();
            if (!"OK".equals(status)) {
                throw new IOException("PROBE refused: " + in.readUTF());
            }
            return new Probe(in.readUTF(), in.readBoolean());
        } catch (IOException e) {
            if (closed.get() || cancellationEpoch.get() != opened.epoch()) {
                throw new IOException("Rendezvous probe cancelled", e);
            }
            throw e;
        } finally {
            pendingHandshakes.remove(opened.cancellationHandle());
        }
    }

    /** D79: downloader xin kết nối relay tới peer đích; trả socket đã nối sẵn. */
    public Socket connectViaRelay(String sessionId, String targetPeerId, int readTimeoutMs) throws IOException {
        Socket socket = handshake("CONNECT", sessionId, targetPeerId);
        socket.setSoTimeout(readTimeoutMs);
        return socket;
    }

    /** Peer nguồn: đăng ký kênh điều khiển (Tracker gửi PING/INCOMING vào đây). */
    public Socket register(String sessionId) throws IOException {
        Socket socket = handshake("REGISTER", sessionId);
        socket.setSoTimeout(60_000);
        return socket;
    }

    /** Peer nguồn: mở kết nối dữ liệu theo token. */
    public Socket accept(String sessionId, String token) throws IOException {
        return handshake("ACCEPT", sessionId, token);
    }

    private Socket handshake(String command, String... fields) throws IOException {
        OpenedSocket opened = openForHandshake();
        Socket socket = opened.socket();
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF(command);
            for (String field : fields) {
                out.writeUTF(field);
            }
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String status = in.readUTF();
            if (!"OK".equals(status)) {
                throw new IOException(command + " refused: " + in.readUTF());
            }
            pendingHandshakes.remove(opened.cancellationHandle());
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            closeQuietly(opened.cancellationHandle());
            pendingHandshakes.remove(opened.cancellationHandle());
            if (cancellationEpoch.get() != opened.epoch()) {
                throw new IOException("Rendezvous handshake cancelled", e);
            }
            throw e;
        }
    }

    /** Closes in-flight handshakes; subsequent handshakes remain available. */
    public void cancelPendingHandshakes() {
        cancellationEpoch.incrementAndGet();
        for (Socket socket : pendingHandshakes) {
            closeQuietly(socket);
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelPendingHandshakes();
    }

    int pendingHandshakeCount() { return pendingHandshakes.size(); }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}