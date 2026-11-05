package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.peer.network.RendezvousClient;
import vn.edu.p2p.peer.network.TrackerConnectionDiagnostics;
import vn.edu.p2p.peer.transfer.ConnectionPlanner.ConnectivityStatusModel;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** D79: giữ kênh điều khiển tới Tracker, khi có người muốn tải thì mở kết nối dữ liệu ra ngoài. */
public final class RelayAgent implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RelayAgent.class.getName());
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 500;

    private final RendezvousClient client;
    private final String sessionId;
    private final PeerUploadServer uploadServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private final ExecutorService acceptors = new ThreadPoolExecutor(
            0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread t = new Thread(runnable, "peer-relay-accept");
                t.setDaemon(true);
                return t;
            });

    private volatile Socket control;
    private volatile Thread thread;
    private volatile BiConsumer<ConnectivityStatusModel.RelayState, String> statusListener;

    public RelayAgent(RendezvousClient client, String sessionId, PeerUploadServer uploadServer) {
        this.client = client;
        this.sessionId = sessionId;
        this.uploadServer = uploadServer;
    }

    public void setStatusListener(
            BiConsumer<ConnectivityStatusModel.RelayState, String> listener) {
        statusListener = listener;
    }

    public synchronized void start() {
        if (closed.get()) return;
        if (!running.compareAndSet(false, true)) {
            return;
        }
        report(ConnectivityStatusModel.RelayState.CONNECTING, "đang kết nối tới Tracker");
        thread = new Thread(this::runLoop, "peer-relay-agent");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        int delay = 3_000;
        while (running.get()) {
            try (Socket channel = client.register(sessionId)) {
                synchronized (lifecycleLock) {
                    if (closed.get()) return;
                    control = channel;
                }
                delay = 3_000;
                report(ConnectivityStatusModel.RelayState.READY, "Relay đã đăng ký với Tracker");
                LOG.info("Relay channel registered with Tracker");
                DataInputStream in = new DataInputStream(channel.getInputStream());
                while (running.get()) {
                    String command = in.readUTF();
                    if ("INCOMING".equals(command)) {
                        String token = in.readUTF();
                        try {
                            if (!closed.get()) acceptors.execute(() -> accept(token));
                        } catch (RejectedExecutionException e) {
                            // Saturated acceptor pool: reject this incoming request but keep
                            // the control channel alive for future downloads.
                            LOG.warning("Relay incoming connection rejected: acceptor workers busy");
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get() && !closed.get()) {
                    report(ConnectivityStatusModel.RelayState.ERROR,
                            TrackerConnectionDiagnostics.explain(e));
                    LOG.log(Level.INFO, "Relay channel lost, retry in " + delay + " ms: " + e.getMessage());
                }
            }
            if (closed.get()) return;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = Math.min(delay * 2, 30_000);
        }
    }

    private void accept(String token) {
        if (closed.get()) return;
        Socket data = null;
        try {
            data = client.accept(sessionId, token);
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    data.close();
                    data = null;
                    return;
                }
                uploadServer.serveRelayedSocket(data); // upload server owns socket after handoff
                data = null;
                report(ConnectivityStatusModel.RelayState.READY,
                        "Relay đã chuyển kết nối tới Peer upload");
            }
        } catch (IOException | RuntimeException e) {
            if (data != null) {
                try { data.close(); } catch (IOException ignored) { }
            }
            LOG.log(Level.FINE, "Relay accept failed: " + e.getMessage(), e);
        }
    }

    private void report(ConnectivityStatusModel.RelayState state, String reason) {
        BiConsumer<ConnectivityStatusModel.RelayState, String> listener = statusListener;
        if (listener != null) listener.accept(state, reason);
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            running.set(false);
            report(ConnectivityStatusModel.RelayState.STOPPED, "Relay đã dừng");
            client.close();
            Socket c = control;
            control = null;
            if (c != null) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        acceptors.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MILLIS);
        Thread loop = thread;
        if (loop != null && loop != Thread.currentThread()) {
            loop.interrupt();
            joinUntil(loop, deadline);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            try {
                acceptors.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void joinUntil(Thread thread, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        try {
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
            thread.join(millis, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean acceptorsTerminated() { return acceptors.isTerminated(); }
    boolean loopTerminated() {
        Thread current = thread;
        return current == null || !current.isAlive();
    }
}