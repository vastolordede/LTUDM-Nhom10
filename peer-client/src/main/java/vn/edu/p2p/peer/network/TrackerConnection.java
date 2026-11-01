package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class TrackerConnection implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final TrackerConnectionConfig config;
    private Socket socket;

    public TrackerConnection(TrackerConnectionConfig config) {
        this.config = java.util.Objects.requireNonNull(config);
    }

    /** Reuse the actual selected Tracker endpoint (including UI-saved TLS settings). */
    public TrackerConnectionConfig configuration() { return config; }

    /** A stale persistent socket can appear connected even after Tracker timeout. */
    public synchronized MessageEnvelope request(MessageEnvelope request) throws IOException {
        boolean wasEstablished = socket != null && socket.isConnected() && !socket.isClosed();
        try {
            return requestOnce(request);
        } catch (IOException first) {
            closeSocket();
            // Never replay registration, share, unshare or history mutations after
            // uncertain delivery. A duplicate login replaces the previous Peer session.
            if (!wasEstablished || !isSafeToReplay(request.type())
                    || first instanceof ProtocolException) throw first;
            try {
                return requestOnce(request);
            } catch (IOException retryFailed) {
                closeSocket();
                retryFailed.addSuppressed(first);
                throw retryFailed;
            }
        }
    }

    private MessageEnvelope requestOnce(MessageEnvelope request) throws IOException {
        ensureConnected();
        ProtocolCodec.write(socket.getOutputStream(), request);
        MessageEnvelope response = ProtocolCodec.read(socket.getInputStream());
        if (!request.requestId().equals(response.requestId())) {
            throw new ProtocolException("Tracker response requestId mismatch");
        }
        return response;
    }

    private static boolean isSafeToReplay(MessageType type) {
        return switch (type) {
            case LOGIN_REQUEST, HEARTBEAT, SEARCH_REQUEST, FILE_SOURCES_REQUEST,
                    FOLDER_SEARCH_REQUEST, FOLDER_DETAIL_REQUEST,
                    TRANSFER_HISTORY_REQUEST -> true;
            default -> false;
        };
    }

    private void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }

        if (config.tlsEnabled()) {
            socket = new TlsTrackerSocketFactory(config).connect(
                    CONNECT_TIMEOUT_MILLIS,
                    READ_TIMEOUT_MILLIS
            );
            return;
        }

        Socket candidate = new Socket();
        boolean connected = false;
        try {
            candidate.connect(new InetSocketAddress(config.host(), config.port()),
                    CONNECT_TIMEOUT_MILLIS);
            candidate.setSoTimeout(READ_TIMEOUT_MILLIS);
            candidate.setTcpNoDelay(true);
            socket = candidate;
            connected = true;
        } finally {
            if (!connected) candidate.close();
        }
    }

    @Override
    public synchronized void close() {
        closeSocket();
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
            socket = null;
        }
    }
}
