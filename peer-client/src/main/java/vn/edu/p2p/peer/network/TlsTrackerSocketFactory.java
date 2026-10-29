package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.security.TlsContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;

final class TlsTrackerSocketFactory {
    private final TrackerConnectionConfig config;
    private final SSLContext sslContext;

    TlsTrackerSocketFactory(TrackerConnectionConfig config) {
        this.config = config;
        try {
            this.sslContext = TlsContextFactory.createClientContext(
                    config.trustStorePath(),
                    config.trustStorePassword(),
                    config.trustStoreType()
            );
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Cannot initialize Peer TLS context",
                    e
            );
        }
    }

        Socket connect(
            int connectTimeoutMillis,
            int readTimeoutMillis
    ) throws IOException {
        return connect(config.host(), config.port(), connectTimeoutMillis, readTimeoutMillis);
    }

    /** D81: dùng cho cổng Rendezvous (khác cổng Tracker chính). */
    Socket connect(
            String host,
            int port,
            int connectTimeoutMillis,
            int readTimeoutMillis
    ) throws IOException {
        SSLSocket socket = (SSLSocket)
                sslContext.getSocketFactory().createSocket();

        socket.connect(
                new InetSocketAddress(host, port),
                connectTimeoutMillis
        );
        socket.setEnabledProtocols(
                supportedProtocols(socket.getSupportedProtocols())
        );
        socket.setSoTimeout(readTimeoutMillis);
        socket.setTcpNoDelay(true);
        socket.startHandshake();
        return socket;
    }

    private static String[] supportedProtocols(String[] supported) {
        java.util.List<String> preferred = new java.util.ArrayList<>();
        java.util.Set<String> available = java.util.Set.of(supported);

        if (available.contains("TLSv1.3")) {
            preferred.add("TLSv1.3");
        }
        if (available.contains("TLSv1.2")) {
            preferred.add("TLSv1.2");
        }

        if (preferred.isEmpty()) {
            throw new IllegalStateException(
                    "JVM does not support TLSv1.2 or TLSv1.3"
            );
        }
        return preferred.toArray(String[]::new);
    }
}
