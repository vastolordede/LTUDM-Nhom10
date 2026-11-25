package vn.edu.p2p.peer.view;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.peer.transfer.ConnectionPlanner;
import vn.edu.p2p.peer.transfer.ConnectionPlanner.ConnectivityStatusModel.*;
import static org.junit.jupiter.api.Assertions.*;

class UserFacingStatusTest {
    @Test void onlineRelayIsReadableWithoutTechnicalReasons() {
        var snapshot = new Snapshot(TrackerState.ONLINE, DirectState.UNAVAILABLE,
                RelayState.READY, "technical", "socket timeout", "registered", null, null, null);
        assertEquals("Đã kết nối máy chủ · Chuyển tiếp khả dụng", UserFacingStatus.connectivity(snapshot));
    }
    @Test void offlineDoesNotClaimRelayAvailable() {
        var snapshot = new Snapshot(TrackerState.OFFLINE, DirectState.LAN,
                RelayState.READY, "technical", "direct", "ready", null, null, null);
        assertEquals("Mất kết nối máy chủ · Đang thử lại", UserFacingStatus.connectivity(snapshot));
    }
    @Test void sourceAndModeAreLocalized() {
        assertEquals("Chuyển tiếp (Relay)", UserFacingStatus.mode(ConnectionPlanner.Mode.RELAY));
        assertEquals("Không xác minh được nguồn tải", UserFacingStatus.source("METADATA_ERROR"));
        assertEquals("Đang kiểm tra nguồn tải", UserFacingStatus.source("UNEXPECTED"));
    }
}
