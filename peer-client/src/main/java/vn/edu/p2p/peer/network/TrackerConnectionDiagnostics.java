package vn.edu.p2p.peer.network;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** R44/R59: a diagnostic TCP/TLS handshake, without sending passwords. */
public final class TrackerConnectionDiagnostics {
    private TrackerConnectionDiagnostics() {}

    public static String probe(TrackerConnectionConfig config) {
        try {
            if (config.tlsEnabled()) {
                try (Socket socket = new TlsTrackerSocketFactory(config).connect(3000, 3000)) {
                    return "TLS handshake OK at " + config.host() + ":" + config.port();
                }
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(config.host(), config.port()), 3000);
                return "TCP reachable at " + config.host() + ":" + config.port()
                        + ". TLS is disabled; verify Tracker uses plain TCP.";
            }
        } catch (Exception e) {
            return explain(e);
        }
    }

    public static String explain(Throwable error) {
        if (error == null) return "Unknown network error";
        Throwable current = error;
        while (current != null) {
            if (current instanceof SSLException) return "TLS handshake failed: check TCP/TLS mode, hostname and trusted certificate.";
            if (current instanceof UnknownHostException) return "Tracker hostname cannot be resolved. Check IP/domain and DNS.";
            if (current instanceof ConnectException) return "Connection refused. Check Tracker port, service and firewall.";
            if (current instanceof SocketTimeoutException) return "Tracker timed out. Check connection, firewall and route.";
            if (current instanceof NoRouteToHostException) return "No route to Tracker. Check VPN, LAN, NAT and firewall.";
            if (current instanceof java.io.EOFException) return "Tracker disconnected unexpectedly. Check TCP/TLS mismatch and server logs.";
            current = current.getCause();
        }
        return error.getMessage() == null ? "Connection failed; check Tracker logs." : error.getMessage();
    }
}
