package vn.edu.p2p.peer.session;

import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.peer.auth.AuthClientException;
import vn.edu.p2p.peer.auth.TicketRevocationList;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class HeartbeatCoordinator implements AutoCloseable {
    private final HeartbeatSender sender;
    private final HeartbeatListener listener;
    private final TicketRevocationList ticketRevocations;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> scheduledTask;

    public HeartbeatCoordinator(
            HeartbeatSender sender,
            HeartbeatListener listener
    ) {
        this(sender, listener, null);
    }

    public HeartbeatCoordinator(
            HeartbeatSender sender,
            HeartbeatListener listener,
            TicketRevocationList ticketRevocations
    ) {
        this.sender = sender;
        this.listener = listener;
        this.ticketRevocations = ticketRevocations;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                new HeartbeatThreadFactory()
        );
    }

    public synchronized void start(ClientSession session) {
        stop();

        int interval = Math.max(1, session.heartbeatIntervalSeconds());
        listener.onStateChanged(
                PeerConnectionState.ONLINE,
                "Heartbeat đang hoạt động mỗi " + interval + " giây."
        );

        scheduledTask = scheduler.scheduleWithFixedDelay(
                this::heartbeatOnce,
                interval,
                interval,
                TimeUnit.SECONDS
        );
    }

    public synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
    }

    private void heartbeatOnce() {
        try {
            HeartbeatResponse response = sender.send();
            if (ticketRevocations != null) ticketRevocations.apply(response);
            listener.onHeartbeat(response);
            listener.onStateChanged(
                    PeerConnectionState.ONLINE,
                    "Tracker ONLINE"
            );
        } catch (AuthClientException e) {
            if ("INVALID_SESSION".equals(e.errorCode())
                    || "SESSION_NOT_ONLINE".equals(e.errorCode())) {
                stop();
                listener.onStateChanged(
                        PeerConnectionState.SESSION_EXPIRED,
                        "Session không còn hợp lệ. Hãy đăng nhập lại."
                );
                return;
            }

            listener.onStateChanged(
                    PeerConnectionState.CONNECTION_LOST,
                    "Mất kết nối Tracker; Peer sẽ thử heartbeat lại."
            );
        } catch (RuntimeException e) {
            listener.onStateChanged(
                    PeerConnectionState.CONNECTION_LOST,
                    "Heartbeat lỗi: " + e.getMessage()
            );
        }
    }

    @Override
    public synchronized void close() {
        stop();
        scheduler.shutdownNow();
    }

    private static final class HeartbeatThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "peer-heartbeat");
            thread.setDaemon(true);
            return thread;
        }
    }
}
