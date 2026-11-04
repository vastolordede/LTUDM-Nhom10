package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ProtocolException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/** R44/R59: maps Tracker transport failures to safe, actionable UI messages. */
public final class TrackerConnectionDiagnostics {
    private static final Logger LOG = Logger.getLogger(TrackerConnectionDiagnostics.class.getName());

    public enum FailureKind {
        CONNECTION_REFUSED,
        HOST_UNRESOLVED,
        CONNECT_TIMEOUT,
        READ_TIMEOUT,
        CLIENT_TLS_SERVER_PLAINTEXT,
        CLIENT_PLAINTEXT_SERVER_TLS,
        TLS_HANDSHAKE_FAILED,
        CERTIFICATE_INVALID,
        PROTOCOL_VERSION_MISMATCH,
        NO_ROUTE,
        REMOTE_CLOSED,
        INVALID_CONFIGURATION,
        OTHER
    }

    private TrackerConnectionDiagnostics() {}

    public static String probe(TrackerConnectionConfig config) {
        try {
            if (config.tlsEnabled()) {
                try (var socket = new TlsTrackerSocketFactory(config).connect(3000, 3000)) {
                    return "Kết nối TLS tới Tracker thành công (" + config.host() + ":"
                            + config.port() + ").";
                }
            }
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(config.host(), config.port()), 3000);
                return "Đã kết nối TCP tới Tracker (" + config.host() + ":" + config.port()
                        + "). Hãy xác nhận hai đầu cùng cấu hình TCP/TLS.";
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Tracker TCP/TLS diagnostic failed: "
                    + classify(e, config));
            return explain(e, config);
        }
    }

    public static FailureKind classify(Throwable error) {
        return classify(error, null);
    }

    public static FailureKind classify(Throwable error, TrackerConnectionConfig config) {
        if (error == null) return FailureKind.OTHER;
        boolean tlsEnabled = config != null && config.tlsEnabled();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ProtocolCompatibilityException) {
                return FailureKind.PROTOCOL_VERSION_MISMATCH;
            }
            if (current instanceof CertificateException
                    || current instanceof CertPathValidatorException
                    || current instanceof SSLPeerUnverifiedException) {
                return FailureKind.CERTIFICATE_INVALID;
            }
            if (current instanceof SSLException sslException) {
                if (hasCertificateCause(sslException)) return FailureKind.CERTIFICATE_INVALID;
                if (looksLikeTlsPlaintextMismatch(sslException)) {
                    return FailureKind.CLIENT_TLS_SERVER_PLAINTEXT;
                }
                return FailureKind.TLS_HANDSHAKE_FAILED;
            }
            if (current instanceof UnknownHostException) return FailureKind.HOST_UNRESOLVED;
            if (current instanceof ConnectException) return FailureKind.CONNECTION_REFUSED;
            if (current instanceof SocketTimeoutException timeout) {
                return isConnectTimeout(timeout) ? FailureKind.CONNECT_TIMEOUT : FailureKind.READ_TIMEOUT;
            }
            if (current instanceof NoRouteToHostException) return FailureKind.NO_ROUTE;
            if (current instanceof ProtocolException && !tlsEnabled) {
                return FailureKind.CLIENT_PLAINTEXT_SERVER_TLS;
            }
            if (current instanceof EOFException && tlsEnabled) {
                return FailureKind.CLIENT_TLS_SERVER_PLAINTEXT;
            }
            if (current instanceof EOFException) return FailureKind.REMOTE_CLOSED;
            if (current instanceof IllegalArgumentException) return FailureKind.INVALID_CONFIGURATION;
            if (current instanceof IllegalStateException
                    && current.getMessage() != null
                    && current.getMessage().contains("TLS context")) {
                return FailureKind.INVALID_CONFIGURATION;
            }
        }
        return FailureKind.OTHER;
    }

    public static String explain(Throwable error) {
        return message(classify(error));
    }

    public static String explain(Throwable error, TrackerConnectionConfig config) {
        return message(classify(error, config));
    }

    public static String message(FailureKind kind) {
        return switch (kind) {
            case CONNECTION_REFUSED ->
                    "Tracker từ chối kết nối hoặc cổng đang đóng. Kiểm tra host, port và trạng thái Tracker.";
            case HOST_UNRESOLVED ->
                    "Không phân giải được địa chỉ Tracker. Kiểm tra tên host/IP và DNS.";
            case CONNECT_TIMEOUT ->
                    "Hết thời gian kết nối Tracker. Kiểm tra host, port, firewall hoặc mạng.";
            case READ_TIMEOUT ->
                    "Tracker đã nhận kết nối nhưng không phản hồi đúng hạn. Kiểm tra dịch vụ và cấu hình TCP/TLS.";
            case CLIENT_TLS_SERVER_PLAINTEXT ->
                    "Cấu hình không khớp: Peer dùng TLS nhưng Tracker có vẻ dùng TCP thường. Kiểm tra chế độ bảo mật ở cả hai đầu.";
            case CLIENT_PLAINTEXT_SERVER_TLS ->
                    "Cấu hình không khớp: Peer dùng TCP thường nhưng Tracker yêu cầu TLS. Bật TLS theo cấu hình Tracker.";
            case TLS_HANDSHAKE_FAILED ->
                    "Bắt tay TLS thất bại. Kiểm tra phiên bản TLS và cấu hình bảo mật của Tracker.";
            case CERTIFICATE_INVALID ->
                    "Chứng chỉ Tracker không hợp lệ, hết hạn hoặc sai tên máy chủ. Kiểm tra chứng chỉ và truststore.";
            case PROTOCOL_VERSION_MISMATCH ->
                    "Phiên bản giao thức không tương thích. Cập nhật Peer và Tracker lên các phiên bản tương thích.";
            case NO_ROUTE ->
                    "Không có đường mạng tới Tracker. Kiểm tra LAN, VPN, route và firewall.";
            case REMOTE_CLOSED ->
                    "Tracker đã đóng kết nối trước khi trả lời. Kiểm tra dịch vụ và cấu hình TCP/TLS.";
            case INVALID_CONFIGURATION ->
                    "Cấu hình Tracker không hợp lệ. Kiểm tra host, port và truststore.";
            case OTHER -> "Không thể kết nối Tracker. Kiểm tra cấu hình mạng rồi thử lại.";
        };
    }

    private static boolean looksLikeTlsPlaintextMismatch(SSLException error) {
        String text = error.getMessage();
        if (text == null) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("unrecognized ssl message")
                || normalized.contains("not an ssl/tls record")
                || normalized.contains("wrong version number")
                || normalized.contains("plaintext")
                || normalized.contains("record overflow");
    }

    private static boolean hasCertificateCause(Throwable error) {
        for (Throwable current = error.getCause(); current != null; current = current.getCause()) {
            if (current instanceof CertificateException
                    || current instanceof CertPathValidatorException
                    || current instanceof SSLPeerUnverifiedException) return true;
        }
        return false;
    }

    private static boolean isConnectTimeout(SocketTimeoutException error) {
        String message = error.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("connect");
    }
}
