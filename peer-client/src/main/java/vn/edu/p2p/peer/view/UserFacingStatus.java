package vn.edu.p2p.peer.view;

import javafx.scene.control.Label;
import vn.edu.p2p.peer.transfer.ConnectionPlanner;

/** Presentation only: never changes authentication, transport or transfer state. */
public final class UserFacingStatus {
    private UserFacingStatus() {}
    public enum Tone { NEUTRAL, SUCCESS, FAILURE }
    public static void set(Label label, String text, Tone tone) {
        label.getStyleClass().removeAll("outcome-success", "outcome-failure");
        if (tone == Tone.SUCCESS) label.getStyleClass().add("outcome-success");
        if (tone == Tone.FAILURE) label.getStyleClass().add("outcome-failure");
        label.setText(text);
    }
    public static String service(ConnectionPlanner.ConnectivityStatusModel.Snapshot s) {
        return switch (s.tracker()) {
            case CHECKING -> "Đang kết nối máy chủ…";
            case ONLINE -> "Đã kết nối máy chủ";
            case OFFLINE -> "Mất kết nối máy chủ · Đang thử lại";
            case LOGGED_OUT -> "Chưa đăng nhập";
        };
    }
    public static String connectivity(ConnectionPlanner.ConnectivityStatusModel.Snapshot s) {
        if (s.tracker() != ConnectionPlanner.ConnectivityStatusModel.TrackerState.ONLINE) return service(s);
        if (s.relay() == ConnectionPlanner.ConnectivityStatusModel.RelayState.IN_USE) return service(s) + " · Đang dùng chuyển tiếp";
        if (s.direct() == ConnectionPlanner.ConnectivityStatusModel.DirectState.LAN || s.direct() == ConnectionPlanner.ConnectivityStatusModel.DirectState.INTERNET_DIRECT) return service(s) + " · Kết nối trực tiếp khả dụng";
        if (s.relay() == ConnectionPlanner.ConnectivityStatusModel.RelayState.READY) return service(s) + " · Chuyển tiếp khả dụng";
        return service(s);
    }
    public static String mode(ConnectionPlanner.Mode mode) {
        if (mode == null) return "Chưa xác định";
        return switch (mode) { case LAN -> "Trực tiếp (LAN)"; case DIRECT -> "Trực tiếp (Internet)"; case RELAY -> "Chuyển tiếp (Relay)"; };
    }
    public static String source(String state) {
        if (state == null) return "Đang kiểm tra";
        return switch (state) {
            case "DISCONNECTED" -> "Mất kết nối nguồn tải";
            case "TICKET_REFRESH_FAILED" -> "Không còn quyền tải từ nguồn này";
            case "METADATA_ERROR" -> "Không xác minh được nguồn tải";
            case "CONNECTED" -> "Đã kết nối";
            case "COMPLETE", "COMPLETED" -> "Hoàn thành";
            case "FAILED" -> "Thất bại";
            default -> "Đang kiểm tra nguồn tải";
        };
    }
    public static String bytes(long n) {
        if(n >= 1048576) return String.format("%.2f MiB", n/1048576.0);
        if(n >= 1024) return String.format("%.1f KiB", n/1024.0);
        return n+" B";
    }
}
