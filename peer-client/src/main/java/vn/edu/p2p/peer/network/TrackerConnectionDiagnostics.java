package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ProtocolException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.BindException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Maps network failures to safe, actionable UI diagnostics. */
public final class TrackerConnectionDiagnostics {
    private static final Logger LOG = Logger.getLogger(TrackerConnectionDiagnostics.class.getName());

    public enum FailureKind {
        NONE,
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
        PEER_PORT_UNAVAILABLE,
        DIRECT_PEER_UNREACHABLE,
        FIREWALL_SUSPECTED,
        NAT_PROBE_FAILED,
        RELAY_UNAVAILABLE,
        OTHER
    }

    public record Diagnostic(FailureKind kind, String title, String message,
                             java.util.List<String> remediation) {
        public Diagnostic {
            remediation = java.util.List.copyOf(remediation);
        }

        public String guidance() {
            return title + "\n" + message + "\n"
                    + String.join("\n", remediation.stream()
                            .map(step -> "• " + step).toList());
        }
    }

    private TrackerConnectionDiagnostics() {}

    public static String probe(TrackerConnectionConfig config) {
        return probeDiagnostic(config).message();
    }

    public static Diagnostic probeDiagnostic(TrackerConnectionConfig config) {
        try {
            if (config.tlsEnabled()) {
                try (var socket = new TlsTrackerSocketFactory(config).connect(3000, 3000)) {
                    return diagnostic(FailureKind.NONE, "Kết nối Tracker thành công.");
                }
            }
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(config.host(), config.port()), 3000);
                return diagnostic(FailureKind.NONE,
                        "Đã kết nối TCP tới Tracker. Hãy xác nhận hai đầu cùng cấu hình TCP/TLS.");
            }
        } catch (Exception e) {
            FailureKind kind = classify(e, config);
            LOG.log(Level.FINE, "Tracker TCP/TLS diagnostic failed: " + kind);
            return diagnostic(kind);
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
            if (current instanceof BindException) return FailureKind.PEER_PORT_UNAVAILABLE;
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

    public static FailureKind classifyDirectPeer(Throwable error, boolean trackerOnline) {
        FailureKind kind = classify(error);
        if (isTlsFailure(kind)) return kind;
        if (trackerOnline && hasCause(error, SocketTimeoutException.class)) {
            return FailureKind.FIREWALL_SUSPECTED;
        }
        if (hasCause(error, UnknownHostException.class)
                || hasCause(error, ConnectException.class)
                || hasCause(error, NoRouteToHostException.class)
                || hasCause(error, SocketTimeoutException.class)) {
            return FailureKind.DIRECT_PEER_UNREACHABLE;
        }
        return kind;
    }

    public static FailureKind classifyNatProbe(Throwable error) {
        FailureKind kind = classify(error);
        return isTlsFailure(kind) ? kind : FailureKind.NAT_PROBE_FAILED;
    }

    public static FailureKind classifyRelay(Throwable error) {
        FailureKind kind = classify(error);
        return isTlsFailure(kind) ? kind : FailureKind.RELAY_UNAVAILABLE;
    }

    public static Diagnostic diagnostic(FailureKind kind) {
        return diagnostic(kind, null);
    }

    public static Diagnostic diagnostic(FailureKind kind, String safeMessage) {
        Diagnostic value = switch (kind) {
            case NONE -> new Diagnostic(kind, "Kết nối bình thường", "",
                    java.util.List.of());
            case CONNECTION_REFUSED -> new Diagnostic(kind, "Tracker không nhận kết nối",
                    message(kind), java.util.List.of(
                            "Kiểm tra địa chỉ Tracker và cổng đã nhập.",
                            "Xác nhận dịch vụ Tracker đang chạy và cổng được mở.",
                            "Kiểm tra firewall mạng nếu cấu hình đúng."));
            case HOST_UNRESOLVED -> new Diagnostic(kind, "Không tìm thấy Tracker",
                    message(kind), java.util.List.of(
                            "Kiểm tra chính tả tên host Tracker.",
                            "Thử xác nhận DNS hoặc dùng địa chỉ được quản trị viên cung cấp."));
            case CONNECT_TIMEOUT, NO_ROUTE -> new Diagnostic(kind, "Không tới được Tracker",
                    message(kind), java.util.List.of(
                            "Kiểm tra kết nối LAN/VPN và route mạng.",
                            "Xác nhận host/port Tracker và firewall cho phép kết nối."));
            case READ_TIMEOUT, REMOTE_CLOSED -> new Diagnostic(kind,
                    "Tracker không phản hồi đúng hạn", message(kind), java.util.List.of(
                            "Kiểm tra Tracker còn hoạt động và không quá tải.",
                            "Xác nhận Peer và Tracker dùng cấu hình TCP/TLS tương thích."));
            case CLIENT_TLS_SERVER_PLAINTEXT, CLIENT_PLAINTEXT_SERVER_TLS,
                    TLS_HANDSHAKE_FAILED -> new Diagnostic(kind, "Lỗi cấu hình TLS",
                    message(kind), java.util.List.of(
                            "Đối chiếu chế độ TLS giữa Peer và Tracker.",
                            "Kiểm tra phiên bản TLS được hỗ trợ ở cả hai đầu."));
            case CERTIFICATE_INVALID -> new Diagnostic(kind, "Chứng chỉ TLS không hợp lệ",
                    message(kind), java.util.List.of(
                            "Kiểm tra chứng chỉ còn hạn và tên máy chủ khớp.",
                            "Cập nhật truststore từ nguồn tin cậy; không bỏ xác minh chứng chỉ."));
            case PROTOCOL_VERSION_MISMATCH -> new Diagnostic(kind, "Sai phiên bản giao thức",
                    message(kind), java.util.List.of(
                            "Cập nhật Peer và Tracker lên các phiên bản tương thích.",
                            "Không thay đổi hoặc hạ phiên bản protocol để bỏ qua lỗi."));
            case INVALID_CONFIGURATION -> new Diagnostic(kind, "Cấu hình Tracker không hợp lệ",
                    message(kind), java.util.List.of(
                            "Kiểm tra host, cổng và cấu hình truststore.",
                            "Nhập lại cấu hình do quản trị viên cung cấp."));
            case PEER_PORT_UNAVAILABLE -> new Diagnostic(kind, "Không mở được cổng upload Peer",
                    "Cổng upload không khả dụng hoặc đang được ứng dụng khác sử dụng.",
                    java.util.List.of(
                            "Đóng Peer đang chạy trùng hoặc ứng dụng khác dùng cổng này.",
                            "Chọn một cổng Peer còn trống rồi đăng nhập lại.",
                            "Cho phép ứng dụng Peer nhận kết nối qua firewall."));
            case DIRECT_PEER_UNREACHABLE -> new Diagnostic(kind,
                    "Peer nguồn không nhận kết nối trực tiếp",
                    "Peer đích có thể đang ngoại tuyến hoặc cổng upload chưa tới được.",
                    java.util.List.of(
                            "Xác nhận Peer nguồn đang online và đang chia sẻ file.",
                            "Kiểm tra cổng upload Peer nguồn và rule firewall.",
                            "Nếu Direct không khả dụng, chỉ dùng Relay khi Tracker báo Relay sẵn sàng."));
            case FIREWALL_SUSPECTED -> new Diagnostic(kind,
                    "Kết nối trực tiếp có thể bị firewall chặn",
                    "Tracker còn online nhưng kết nối trực tiếp tới Peer bị hết thời gian.",
                    java.util.List.of(
                            "Cho phép ứng dụng Peer nhận kết nối trên cổng upload đã cấu hình.",
                            "Kiểm tra firewall hệ điều hành/router và NAT port forwarding.",
                            "Dùng Relay khi trạng thái hiển thị Relay sẵn sàng."));
            case NAT_PROBE_FAILED -> new Diagnostic(kind, "Chưa xác định được NAT/Direct",
                    "Probe mạng ngoài không thành công nên chưa thể kết luận Direct khả dụng.",
                    java.util.List.of(
                            "Xác nhận Rendezvous Tracker đang chạy và cấu hình đúng.",
                            "Kiểm tra firewall cho phép kết nối Peer tới Tracker.",
                            "Thử lại; nếu Relay sẵn sàng, dùng Relay khi Direct không tới được."));
            case RELAY_UNAVAILABLE -> new Diagnostic(kind, "Relay không khả dụng",
                    "Không thiết lập được kênh Relay tới Tracker hoặc Peer đích.",
                    java.util.List.of(
                            "Xác nhận Tracker/Rendezvous còn online và Relay được bật.",
                            "Kiểm tra kết nối Tracker và firewall cho phép cổng Rendezvous.",
                            "Khắc phục lỗi TLS nếu có; TLS thất bại không được bỏ qua bằng Relay."));
            case OTHER -> new Diagnostic(kind, "Có lỗi kết nối mạng",
                    "Không thể hoàn tất thao tác mạng.", java.util.List.of(
                            "Kiểm tra trạng thái Tracker và cấu hình mạng.",
                            "Thử lại sau khi xác nhận cổng và dịch vụ đang hoạt động."));
        };
        return safeMessage == null || safeMessage.isBlank() ? value
                : new Diagnostic(value.kind(), value.title(), safeMessage, value.remediation());
    }

    private static boolean isTlsFailure(FailureKind kind) {
        return kind == FailureKind.CLIENT_TLS_SERVER_PLAINTEXT
                || kind == FailureKind.CLIENT_PLAINTEXT_SERVER_TLS
                || kind == FailureKind.TLS_HANDSHAKE_FAILED
                || kind == FailureKind.CERTIFICATE_INVALID;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
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
            case NONE -> "Kết nối thành công.";
            case PEER_PORT_UNAVAILABLE ->
                    "Không mở được cổng upload Peer; cổng có thể đang được sử dụng.";
            case DIRECT_PEER_UNREACHABLE ->
                    "Peer nguồn không nhận kết nối trực tiếp hoặc đang ngoại tuyến.";
            case FIREWALL_SUSPECTED ->
                    "Tracker online nhưng kết nối trực tiếp tới Peer bị timeout; có thể firewall/NAT đang chặn.";
            case NAT_PROBE_FAILED ->
                    "NAT probe thất bại; chưa xác định được khả năng kết nối Internet Direct.";
            case RELAY_UNAVAILABLE ->
                    "Relay không khả dụng; kiểm tra Tracker/Rendezvous và kết nối Peer.";
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
