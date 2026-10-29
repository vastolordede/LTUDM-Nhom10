package vn.edu.p2p.peer.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Máy khách của cổng Rendezvous trên Tracker: NAT probe và relay. */
public final class RendezvousClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    public record Probe(String observedIp, boolean reachable) {}

        private final String host;
        private final int port;
        private final TlsTrackerSocketFactory tls; // null = plain TCP

        public RendezvousClient(String host, int port) {
            this.host = host;
            this.port = port;
            this.tls = null;
        }

        /** D81: dùng TLS cho cổng Rendezvous khi Tracker TLS đang bật. */
        public RendezvousClient(PeerSettings settings, int port) {
            this.host = settings.trackerHost();
            this.port = port;
            this.tls = settings.trackerTlsEnabled()
                    ? new TlsTrackerSocketFactory(settings.trackerConnectionConfig())
                    : null;
        }

        private Socket open() throws IOException {
            if (tls != null) {
                return tls.connect(host, port, CONNECT_TIMEOUT_MS, HANDSHAKE_TIMEOUT_MS);
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
        }

    /** D77. */
    public Probe probe(String sessionId, int listeningPort) throws IOException {
        try (Socket socket = open()) {
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
        Socket socket = open();
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
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}