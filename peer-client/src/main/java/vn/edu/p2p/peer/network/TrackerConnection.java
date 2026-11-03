package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolException;
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

public final class TrackerConnection implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    private final Object socketLock = new Object();
    private volatile TrackerConnectionConfig config;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final AtomicLong requestGeneration = new AtomicLong();
    private volatile Socket socket;

    public TrackerConnection(TrackerConnectionConfig config) {
        this(config, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
    }

    public TrackerConnection(TrackerConnectionConfig config, int connectTimeoutMillis,
                             int readTimeoutMillis) {
        this.config = java.util.Objects.requireNonNull(config);
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("Tracker timeouts must be positive");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** Reuse the actual selected Tracker endpoint (including UI-saved TLS settings). */
    public TrackerConnectionConfig configuration() { return config; }

    /** Replaces endpoint settings without waiting for a blocked request or retaining its socket. */
    public void reconfigure(TrackerConnectionConfig newConfig) {
        java.util.Objects.requireNonNull(newConfig, "newConfig");
        synchronized (socketLock) {
            if (config.equals(newConfig)) return;
            config = newConfig;
            requestGeneration.incrementAndGet();
            closeSocketLocked();
        }
    }

    /** A stale persistent socket can appear connected even after Tracker timeout. */
    public MessageEnvelope request(MessageEnvelope request) throws IOException {
        long generation = requestGeneration.get();
        synchronized (this) {
            if (generation != requestGeneration.get()) {
                throw new IOException("Tracker request was cancelled");
            }
            return requestLocked(request, generation);
        }
    }

    private MessageEnvelope requestLocked(MessageEnvelope request, long generation)
            throws IOException {
        boolean wasEstablished = socket != null && socket.isConnected() && !socket.isClosed();
        try {
            return requestOnce(request);
        } catch (IOException first) {
            closeSocket();
            // Never replay registration, share, unshare or history mutations after
            // uncertain delivery. A duplicate login replaces the previous Peer session.
            if (generation != requestGeneration.get()
                    || !wasEstablished || !isSafeToReplay(request.type())
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
        Socket active = ensureConnected();
        ProtocolCodec.write(active.getOutputStream(), request);
        MessageEnvelope response = ProtocolCodec.read(active.getInputStream());
        ProtocolCompatibilityException.validateResponse(response, "Tracker");
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

    private Socket ensureConnected() throws IOException {
        final TrackerConnectionConfig selectedConfig;
        final long selectedGeneration;
        synchronized (socketLock) {
            if (socket != null && socket.isConnected() && !socket.isClosed()) return socket;
            socket = null;
            selectedConfig = config;
            selectedGeneration = requestGeneration.get();
        }

        Socket candidate;
        if (selectedConfig.tlsEnabled()) {
            candidate = new TlsTrackerSocketFactory(selectedConfig).connect(
                    connectTimeoutMillis, readTimeoutMillis);
        } else {
            candidate = new Socket();
            boolean connected = false;
            try {
                candidate.connect(new InetSocketAddress(selectedConfig.host(), selectedConfig.port()),
                        connectTimeoutMillis);
                candidate.setSoTimeout(readTimeoutMillis);
                candidate.setTcpNoDelay(true);
                connected = true;
            } finally {
                if (!connected) candidate.close();
            }
        }

        synchronized (socketLock) {
            if (selectedGeneration != requestGeneration.get() || selectedConfig != config) {
                candidate.close();
                throw new IOException("Tracker configuration changed while connecting");
            }
            if (socket != null && socket.isConnected() && !socket.isClosed()) {
                candidate.close();
                return socket;
            }
            socket = candidate;
            return candidate;
        }
    }

    @Override
    public void close() {
        abortCurrentRequest();
    }

    /** Closes a blocked request without waiting for the request monitor. */
    public void abortCurrentRequest() {
        synchronized (socketLock) {
            requestGeneration.incrementAndGet();
            closeSocketLocked();
        }
    }

    private void closeSocket() {
        synchronized (socketLock) {
            closeSocketLocked();
        }
    }

    private void closeSocketLocked() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
