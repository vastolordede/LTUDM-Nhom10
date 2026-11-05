package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.peer.transfer.ConnectionPlanner.ConnectivityStatusModel;
import vn.edu.p2p.peer.network.TrackerConnectionDiagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectivityStatusModelTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void tracksTrackerRecoveryAndDirectToRelayTransition() {
        ConnectivityStatusModel model = new ConnectivityStatusModel();

        model.tracker(ConnectivityStatusModel.TrackerState.ONLINE, "đang hoạt động");
        model.directProbe(false);
        model.relay(ConnectivityStatusModel.RelayState.READY, "Relay đã đăng ký");
        model.directUnavailable("Kết nối trực tiếp thất bại; đang thử đường kết nối thay thế.");
        model.relay(ConnectivityStatusModel.RelayState.CONNECTING, "đang thiết lập kết nối Relay");
        model.directMode(ConnectionPlanner.Mode.RELAY);

        assertEquals(ConnectivityStatusModel.TrackerState.ONLINE, model.snapshot().tracker());
        assertEquals(ConnectivityStatusModel.DirectState.UNAVAILABLE, model.snapshot().direct());
        assertEquals(ConnectivityStatusModel.RelayState.IN_USE, model.snapshot().relay());
        assertTrue(model.snapshot().summary().contains("đang truyền qua Relay"));

        model.tracker(ConnectivityStatusModel.TrackerState.OFFLINE, "mất kết nối; đang thử lại");
        assertEquals(ConnectivityStatusModel.TrackerState.OFFLINE, model.snapshot().tracker());
        model.tracker(ConnectivityStatusModel.TrackerState.ONLINE, "đang hoạt động");
        assertEquals(ConnectivityStatusModel.TrackerState.ONLINE, model.snapshot().tracker());
        model.directMode(ConnectionPlanner.Mode.LAN);
        assertEquals(ConnectivityStatusModel.DirectState.LAN, model.snapshot().direct());
        model.directMode(ConnectionPlanner.Mode.DIRECT);
        assertEquals(ConnectivityStatusModel.DirectState.INTERNET_DIRECT, model.snapshot().direct());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void tlsFailureDoesNotClaimRelayWasUsed() {
        ConnectivityStatusModel model = new ConnectivityStatusModel();
        model.directUnavailable(TrackerConnectionDiagnostics.FailureKind.CERTIFICATE_INVALID,
                "Chứng chỉ TLS không hợp lệ.");

        assertEquals(ConnectivityStatusModel.DirectState.UNAVAILABLE, model.snapshot().direct());
        assertEquals(ConnectivityStatusModel.RelayState.DISABLED, model.snapshot().relay());
        assertTrue(model.snapshot().directReason().contains("TLS"));
        assertFalse(model.snapshot().summary().contains("đang truyền qua Relay"));
        assertTrue(model.snapshot().guidance().contains("truststore"));
        assertFalse(model.snapshot().guidance().contains("Dùng Relay"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void statusGuidanceTracksFirewallAndRelayIssuesWithoutSensitiveDetails() {
        ConnectivityStatusModel model = new ConnectivityStatusModel();
        model.tracker(ConnectivityStatusModel.TrackerState.ONLINE,
                TrackerConnectionDiagnostics.FailureKind.NONE, "đang hoạt động");
        model.directUnavailable(TrackerConnectionDiagnostics.FailureKind.FIREWALL_SUSPECTED,
                "Kết nối trực tiếp có thể bị firewall chặn.");
        model.relay(ConnectivityStatusModel.RelayState.ERROR,
                TrackerConnectionDiagnostics.FailureKind.RELAY_UNAVAILABLE,
                "Relay không khả dụng.");

        String guidance = model.snapshot().guidance();
        assertTrue(guidance.contains("firewall"));
        assertTrue(guidance.contains("Relay không khả dụng"));
        assertFalse(guidance.contains("127.0.0.1"));
        assertFalse(guidance.contains("ticket-secret"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void listenerCanBeUnsubscribedAtLogout() throws Exception {
        ConnectivityStatusModel model = new ConnectivityStatusModel();
        List<ConnectivityStatusModel.Snapshot> received = new ArrayList<>();
        ConnectivityStatusModel.Subscription subscription = model.subscribe(received::add);
        int initialCount = received.size();

        model.tracker(ConnectivityStatusModel.TrackerState.ONLINE, "đang hoạt động");
        assertTrue(received.size() > initialCount);
        int countBeforeLogout = received.size();
        subscription.close();

        model.tracker(ConnectivityStatusModel.TrackerState.LOGGED_OUT, "đã đăng xuất");
        assertEquals(countBeforeLogout, received.size());
        assertEquals(ConnectivityStatusModel.TrackerState.LOGGED_OUT, model.snapshot().tracker());
        assertEquals(ConnectivityStatusModel.RelayState.STOPPED, model.snapshot().relay());
    }
}
