package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.peer.network.RendezvousClient;
import vn.edu.p2p.peer.network.TrackerConnectionDiagnostics;

import java.io.IOException;
import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** D78-D80: thử kết nối trực tiếp, thất bại thì chuyển qua relay, báo chế độ kết nối. */
public final class ConnectionPlanner {
    public enum Mode { LAN, DIRECT, RELAY }

    /** Shared, observable connectivity state for one login session. */
    public static final class ConnectivityStatusModel {
        public enum TrackerState { CHECKING, ONLINE, OFFLINE, LOGGED_OUT }
        public enum DirectState { CHECKING, LAN, INTERNET_DIRECT, UNAVAILABLE }
        public enum RelayState { DISABLED, CONNECTING, READY, IN_USE, ERROR, STOPPED }

        @FunctionalInterface
        public interface Subscription extends AutoCloseable {
            @Override
            void close();
        }

        public record Snapshot(
                TrackerState tracker,
                DirectState direct,
                RelayState relay,
                String trackerReason,
                String directReason,
                String relayReason,
                TrackerConnectionDiagnostics.FailureKind trackerIssue,
                TrackerConnectionDiagnostics.FailureKind directIssue,
                TrackerConnectionDiagnostics.FailureKind relayIssue
        ) {
            public String summary() {
                return "Tracker: " + trackerReason + " | LAN/Internet Direct: " + directReason
                        + " | Relay: " + relayReason;
            }

            public String trackerSummary() {
                return "Tracker: " + trackerReason;
            }

            public String guidance() {
                return java.util.stream.Stream.of(trackerIssue, directIssue, relayIssue)
                        .filter(Objects::nonNull)
                        .filter(kind -> kind != TrackerConnectionDiagnostics.FailureKind.NONE)
                        .distinct()
                        .map(kind -> TrackerConnectionDiagnostics.diagnostic(kind).guidance())
                        .collect(java.util.stream.Collectors.joining("\n\n"));
            }
        }

        private final java.util.concurrent.CopyOnWriteArrayList<Consumer<Snapshot>> listeners =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile Snapshot current = new Snapshot(TrackerState.CHECKING,
                DirectState.CHECKING, RelayState.DISABLED, "đang kiểm tra",
                "đang kiểm tra", "đã tắt", null, null, null);

        public Snapshot snapshot() {
            return current;
        }

        public synchronized Subscription subscribe(Consumer<Snapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            listeners.add(listener);
            listener.accept(current);
            return () -> listeners.remove(listener);
        }

        public void tracker(TrackerState state, String reason) {
            tracker(state, state == TrackerState.OFFLINE
                    ? TrackerConnectionDiagnostics.FailureKind.OTHER
                    : TrackerConnectionDiagnostics.FailureKind.NONE, reason);
        }

        public void tracker(TrackerState state, TrackerConnectionDiagnostics.FailureKind issue,
                            String reason) {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(issue, "issue");
            if (state == TrackerState.LOGGED_OUT) {
                update(previous -> new Snapshot(state, DirectState.CHECKING, RelayState.STOPPED,
                        safeReason(reason), "chưa đăng nhập", "đã dừng",
                        TrackerConnectionDiagnostics.FailureKind.NONE,
                        TrackerConnectionDiagnostics.FailureKind.NONE,
                        TrackerConnectionDiagnostics.FailureKind.NONE));
                return;
            }
            update(previous -> new Snapshot(state, previous.direct(), previous.relay(),
                    safeReason(reason), previous.directReason(), previous.relayReason(),
                    issue == TrackerConnectionDiagnostics.FailureKind.NONE ? null : issue,
                    previous.directIssue(), previous.relayIssue()));
        }

        public void directProbe(boolean reachable) {
            update(previous -> new Snapshot(previous.tracker(),
                    reachable ? DirectState.INTERNET_DIRECT : DirectState.UNAVAILABLE,
                    previous.relay(), previous.trackerReason(),
                    reachable ? "địa chỉ công khai có thể truy cập"
                            : "NAT/firewall chưa cho phép kết nối trực tiếp",
                    previous.relayReason(), previous.trackerIssue(),
                    reachable ? null : TrackerConnectionDiagnostics.FailureKind.NAT_PROBE_FAILED,
                    previous.relayIssue()));
        }

        public void directChecking() {
            update(previous -> new Snapshot(previous.tracker(), DirectState.CHECKING,
                    previous.relay(), previous.trackerReason(), "đang kiểm tra",
                    previous.relayReason(), previous.trackerIssue(), null, previous.relayIssue()));
        }

        public void directMode(Mode mode) {
            Objects.requireNonNull(mode, "mode");
            update(previous -> {
                DirectState state = switch (mode) {
                    case LAN -> DirectState.LAN;
                    case DIRECT -> DirectState.INTERNET_DIRECT;
                    case RELAY -> previous.direct() == DirectState.CHECKING
                            ? DirectState.UNAVAILABLE : previous.direct();
                };
                String reason = switch (mode) {
                    case LAN -> "đã kết nối trong mạng LAN";
                    case DIRECT -> "đã kết nối Internet Direct";
                    case RELAY -> previous.directReason();
                };
                RelayState relay = mode == Mode.RELAY ? RelayState.IN_USE : previous.relay();
                String relayReason = mode == Mode.RELAY
                        ? "đang truyền qua Relay" : previous.relayReason();
                return new Snapshot(previous.tracker(), state, relay,
                        previous.trackerReason(), reason, relayReason, previous.trackerIssue(),
                        mode == Mode.RELAY ? previous.directIssue() : null,
                        mode == Mode.RELAY ? null : previous.relayIssue());
            });
        }

        public void directUnavailable(String reason) {
            directUnavailable(TrackerConnectionDiagnostics.FailureKind.OTHER, reason);
        }

        public void directUnavailable(TrackerConnectionDiagnostics.FailureKind issue,
                                      String reason) {
            update(previous -> new Snapshot(previous.tracker(), DirectState.UNAVAILABLE,
                    previous.relay(), previous.trackerReason(), safeReason(reason),
                    previous.relayReason(), previous.trackerIssue(),
                    Objects.requireNonNull(issue), previous.relayIssue()));
        }

        public void relay(RelayState state, String reason) {
            relay(state, state == RelayState.ERROR
                    ? TrackerConnectionDiagnostics.FailureKind.RELAY_UNAVAILABLE
                    : TrackerConnectionDiagnostics.FailureKind.NONE, reason);
        }

        public void relay(RelayState state, TrackerConnectionDiagnostics.FailureKind issue,
                          String reason) {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(issue, "issue");
            update(previous -> new Snapshot(previous.tracker(), previous.direct(), state,
                    previous.trackerReason(), previous.directReason(), safeReason(reason),
                    previous.trackerIssue(), previous.directIssue(),
                    issue == TrackerConnectionDiagnostics.FailureKind.NONE ? null : issue));
        }

        private synchronized void update(UnaryOperator<Snapshot> transition) {
            current = transition.apply(current);
            for (Consumer<Snapshot> listener : listeners) {
                listener.accept(current);
            }
        }

        private static String safeReason(String reason) {
            if (reason == null || reason.isBlank()) return "đang cập nhật";
            return reason.length() <= 180 ? reason : reason.substring(0, 180);
        }
    }

    private static final long RELAY_PREFERENCE_MS = 60_000;

    private final PeerP2pSocketFactory direct;
    private final RendezvousClient rendezvous; // null = relay tắt
    private final String sessionId;
    private final Map<String, String> peerByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, Long> relayUntil = new ConcurrentHashMap<>();
    private final Map<String, Mode> lastMode = new ConcurrentHashMap<>();
    private final Set<Socket> activeRelaySockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeRelayCount = new AtomicInteger();
    private volatile BiConsumer<String, Mode> listener;
    private volatile ConnectivityStatusModel connectivityStatus;

    public ConnectionPlanner(PeerP2pSocketFactory direct, RendezvousClient rendezvous, String sessionId) {
        this.direct = direct;
        this.rendezvous = rendezvous;
        this.sessionId = sessionId;
    }

    public void setListener(BiConsumer<String, Mode> listener) {
        this.listener = listener;
    }

    public void setConnectivityStatus(ConnectivityStatusModel connectivityStatus) {
        this.connectivityStatus = connectivityStatus;
    }

    public void relayStatus(ConnectivityStatusModel.RelayState state,
                            TrackerConnectionDiagnostics.Diagnostic diagnostic) {
        ConnectivityStatusModel status = connectivityStatus;
        if (status == null) return;
        if (state == ConnectivityStatusModel.RelayState.READY && activeRelayCount.get() > 0) {
            status.relay(ConnectivityStatusModel.RelayState.IN_USE,
                    TrackerConnectionDiagnostics.FailureKind.NONE, "đang truyền qua Relay");
        } else {
            status.relay(state, diagnostic.kind(), diagnostic.message());
        }
    }

    public void connectionClosed(Socket socket) {
        if (activeRelaySockets.remove(socket) && activeRelayCount.decrementAndGet() == 0) {
            ConnectivityStatusModel status = connectivityStatus;
            if (status != null
                    && status.snapshot().relay() != ConnectivityStatusModel.RelayState.STOPPED
                    && status.snapshot().relay() != ConnectivityStatusModel.RelayState.ERROR) {
                status.relay(ConnectivityStatusModel.RelayState.READY,
                        TrackerConnectionDiagnostics.FailureKind.NONE, "Relay đã sẵn sàng");
            }
        }
    }

    /** Ghi nhớ peerId của từng endpoint để biết relay tới ai. */
    public void remember(List<FileSourceInfo> sources) {
        for (FileSourceInfo s : sources) {
            peerByEndpoint.put(s.ipAddress() + ":" + s.listeningPort(), s.peerId());
        }
    }

    public Socket open(String host, int port) throws IOException {
        String key = host + ":" + port;
        Long until = relayUntil.get(key);
        boolean skipDirect = until != null && until > System.currentTimeMillis();
        IOException failure = null;

        if (!skipDirect) {
            try {
                Socket socket = direct.openClientSocket(host, port);
                report(key, isPrivateAddress(host) ? Mode.LAN : Mode.DIRECT);
                return socket;
            } catch (IOException e) {
                // TLS certificate/identity failure is NOT a connectivity failure;
                // trying Relay must never hide a failed authenticated handshake.
                TrackerConnectionDiagnostics.FailureKind issue =
                        TrackerConnectionDiagnostics.classifyDirectPeer(e,
                                connectivityStatus != null && connectivityStatus.snapshot().tracker()
                                        == ConnectivityStatusModel.TrackerState.ONLINE);
                updateDirectFailure(issue);
                if (!mayRetryViaRelay(e)) {
                    throw e;
                }
                failure = e;
            }
        }

        String peerId = peerByEndpoint.get(key);
        if (rendezvous != null && peerId != null) {
            ConnectivityStatusModel status = connectivityStatus;
            if (status != null) {
                status.relay(ConnectivityStatusModel.RelayState.CONNECTING,
                        TrackerConnectionDiagnostics.FailureKind.NONE,
                        "đang thiết lập kết nối Relay");
            }
            try {
                Socket relay = rendezvous.connectViaRelay(sessionId, peerId, direct.readTimeoutMillis());
                Socket socket;
                try {
                    socket = direct.wrapClient(relay, host, port);
                } catch (IOException tlsFail) {
                    relay.close();
                    throw tlsFail;
                }
                relayUntil.put(key, System.currentTimeMillis() + RELAY_PREFERENCE_MS);
                activeRelaySockets.add(socket);
                activeRelayCount.incrementAndGet();
                report(key, Mode.RELAY);
                return socket;
            } catch (IOException e) {
                relayUntil.remove(key); // lần sau thử direct lại
                if (status != null) {
                    var issue = TrackerConnectionDiagnostics.classifyRelay(e);
                    status.relay(ConnectivityStatusModel.RelayState.ERROR,
                            issue, TrackerConnectionDiagnostics.diagnostic(issue).message());
                }
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        throw failure != null ? failure : new IOException("No route to " + key);
    }

    /** Only transport failures may use Relay. TLS identity failures are terminal. */
    static boolean mayRetryViaRelay(IOException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SSLException) return false;
            cause = cause.getCause();
        }
        return true;
    }

    private void report(String key, Mode mode) {
        Mode previous = lastMode.put(key, mode);
        BiConsumer<String, Mode> l = listener;
        if (l != null && previous != mode) {
            String peerId = peerByEndpoint.get(key);
            l.accept(peerId != null ? peerId : key, mode);
        }
        ConnectivityStatusModel status = connectivityStatus;
        if (status != null) status.directMode(mode);
    }

    private void updateDirectFailure(TrackerConnectionDiagnostics.FailureKind issue) {
        ConnectivityStatusModel status = connectivityStatus;
        if (status != null) {
            status.directUnavailable(issue, TrackerConnectionDiagnostics.diagnostic(issue).message());
        }
    }

    public static boolean isPrivateAddress(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
