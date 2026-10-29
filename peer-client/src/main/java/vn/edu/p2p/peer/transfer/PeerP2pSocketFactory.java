package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.security.TlsContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * S07-S08: Khởi tạo ServerSocket (cho Upload Server) và Socket (cho Downloader)
 * giữa 2 Peer, dùng chung TlsContextFactory của dự án.
 */
public final class PeerP2pSocketFactory {

    private final PeerP2pSocketConfig config;
    private final SSLContext serverSslContext;
    private final SSLContext clientSslContext;

    public PeerP2pSocketFactory(PeerP2pSocketConfig config) {
        this.config = config;
        if (config.tlsEnabled()) {
            try {
                this.serverSslContext = TlsContextFactory.createServerContext(
                        config.keyStorePath(),
                        config.keyStorePassword(),
                        config.keyStoreType()
                );
                this.clientSslContext = TlsContextFactory.createClientContext(
                        config.trustStorePath(),
                        config.trustStorePassword(),
                        config.trustStoreType()
                );
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("Cannot initialize Peer-to-Peer TLS context", e);
            }
        } else {
            this.serverSslContext = null;
            this.clientSslContext = null;
        }
    }

    /** S01, S07: Mở ServerSocket lắng nghe tại listeningPort của Peer. */
    public ServerSocket openServerSocket(int listeningPort) throws IOException {
        if (!config.tlsEnabled()) {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(listeningPort));
            return serverSocket;
        }

        SSLServerSocket sslServerSocket = (SSLServerSocket)
                serverSslContext.getServerSocketFactory().createServerSocket();
        sslServerSocket.setReuseAddress(true);
        sslServerSocket.bind(new InetSocketAddress(listeningPort));
        sslServerSocket.setEnabledProtocols(
                supportedProtocols(sslServerSocket.getSupportedProtocols())
        );
        sslServerSocket.setNeedClientAuth(false);
        return sslServerSocket;
    }

    /** S07, S10: Mở kết nối P2P từ Downloader tới Uploader Peer. */
    public Socket openClientSocket(String host, int port) throws IOException {
        if (!config.tlsEnabled()) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), config.connectTimeoutMillis());
            socket.setSoTimeout(config.readTimeoutMillis());
            socket.setTcpNoDelay(true);
            return socket;
        }

        SSLSocket sslSocket = (SSLSocket) clientSslContext.getSocketFactory().createSocket();
        sslSocket.connect(new InetSocketAddress(host, port), config.connectTimeoutMillis());
        sslSocket.setEnabledProtocols(supportedProtocols(sslSocket.getSupportedProtocols()));
        sslSocket.setSoTimeout(config.readTimeoutMillis());
        sslSocket.setTcpNoDelay(true);
        sslSocket.startHandshake();
        return sslSocket;
    }

        /** D81: bọc TLS lên socket relay đã nối sẵn (phía downloader). Tracker chỉ thấy dữ liệu mã hóa. */
    public Socket wrapClient(Socket plain, String host, int port) throws IOException {
        if (!config.tlsEnabled()) {
            return plain;
        }
        SSLSocket ssl = (SSLSocket) clientSslContext.getSocketFactory()
                .createSocket(plain, host, port, true);
        ssl.setEnabledProtocols(supportedProtocols(ssl.getSupportedProtocols()));
        ssl.setSoTimeout(config.readTimeoutMillis());
        ssl.startHandshake();
        return ssl;
    }

    /** D81: bọc TLS phía peer nguồn cho socket do relay nối. */
    public Socket wrapServer(Socket plain) throws IOException {
        if (!config.tlsEnabled()) {
            return plain;
        }
        SSLSocket ssl = (SSLSocket) serverSslContext.getSocketFactory()
                .createSocket(plain, null, plain.getPort(), true);
        ssl.setUseClientMode(false);
        ssl.setNeedClientAuth(false);
        ssl.setEnabledProtocols(supportedProtocols(ssl.getSupportedProtocols()));
        return ssl;
    }

    public int readTimeoutMillis() {
        return config.readTimeoutMillis();
    }

    private static String[] supportedProtocols(String[] supported) {
        List<String> preferred = new ArrayList<>();
        Set<String> available = Set.of(supported);
        if (available.contains("TLSv1.3")) {
            preferred.add("TLSv1.3");
        }
        if (available.contains("TLSv1.2")) {
            preferred.add("TLSv1.2");
        }
        if (preferred.isEmpty()) {
            throw new IllegalStateException("JVM does not support TLSv1.2 or TLSv1.3");
        }
        return preferred.toArray(String[]::new);
    }
}