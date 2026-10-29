package vn.edu.p2p.tracker.network;

import vn.edu.p2p.tracker.catalog.SessionPeerResolver;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * D77, D79, D81: NAT discovery (PROBE) và relay.
 *
 * Giao thức (DataInput/OutputStream, chuỗi UTF), mọi lệnh đều mang sessionId:
 *  - PROBE    <session> <port>          -> OK <observedIp> <reachable>
 *  - REGISTER <session>                 -> OK, rồi Tracker gửi PING / INCOMING <token>
 *  - CONNECT  <session> <targetPeerId>  -> OK rồi nối thẳng sang peer đích
 *  - ACCEPT   <session> <token>         -> OK rồi nối thẳng sang downloader
 * Lỗi: ERR <message>.
 *
 * Cổng này dùng cùng socketProvider với Tracker chính (TLS nếu Tracker TLS bật).
 */
public final class RendezvousServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RendezvousServer.class.getName());
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int PROBE_TIMEOUT_MS = 3_000;
    private static final int PAIR_TIMEOUT_SECONDS = 8;
    private static final int IDLE_TIMEOUT_MS = 60_000;
    private static final long MAX_BYTES_PER_PIPE = 64L * 1024 * 1024;

    private record Control(Socket socket, DataOutputStream out) {}

    private record Pending(UUID target, CompletableFuture<Socket> future) {}

    private final int port;
    private final SessionPeerResolver resolver;
    private final boolean relayEnabled;
    private final TrackerServerSocketProvider socketProvider;
    private final ExecutorService pool = new ThreadPoolExecutor(
            0, 256, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread t = new Thread(runnable, "tracker-rendezvous");
                t.setDaemon(true);
                return t;
            });
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread t = new Thread(runnable, "tracker-rendezvous-ping");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, Control> controls = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastProbe = new ConcurrentHashMap<>();
    private final Semaphore relaySlots = new Semaphore(64);

    private volatile boolean running;
    private volatile ServerSocket server;

    public RendezvousServer(int port, SessionPeerResolver resolver, boolean relayEnabled) {
        this(port, resolver, relayEnabled, TrackerServerSocketProvider.plain());
    }

    public RendezvousServer(int port, SessionPeerResolver resolver, boolean relayEnabled,
                            TrackerServerSocketProvider socketProvider) {
        this.port = port;
        this.resolver = resolver;
        this.relayEnabled = relayEnabled;
        this.socketProvider = Objects.requireNonNull(socketProvider, "socketProvider");
    }

    /** Chặn đến khi close(). Chạy trong một thread riêng. */
    public void start() throws IOException {
        running = true;
        pinger.scheduleAtFixedRate(this::pingControls, 20, 20, TimeUnit.SECONDS);
        try (ServerSocket ss = socketProvider.open(port)) {
            server = ss;
            LOG.info("Rendezvous server on port " + port + " (relay " + (relayEnabled ? "ON" : "OFF") + ")");
            while (running) {
                try {
                    Socket client = ss.accept();
                    try {
                        pool.execute(() -> handle(client));
                    } catch (RejectedExecutionException e) {
                        closeQuietly(client);
                    }
                } catch (SocketException e) {
                    if (running) {
                        throw e;
                    }
                }
            }
        } finally {
            running = false;
        }
    }

    private void handle(Socket socket) {
        boolean keepOpen = false;
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            String command = in.readUTF();
            keepOpen = switch (command) {
                case "PROBE" -> probe(socket, in, out);
                case "REGISTER" -> register(socket, in, out);
                case "CONNECT" -> connect(socket, in, out);
                case "ACCEPT" -> accept(socket, in, out);
                default -> reply(out, "ERR", "Unknown command");
            };
        } catch (Exception e) {
            LOG.log(Level.FINE, "Rendezvous request failed: " + e.getMessage(), e);
        } finally {
            if (!keepOpen) {
                closeQuietly(socket);
            }
        }
    }

    /** D77: cho peer biết IP Tracker thấy và cổng upload có kết nối ngược được không. */
    private boolean probe(Socket socket, DataInputStream in, DataOutputStream out) throws Exception {
        UUID peerId = authenticate(in.readUTF());
        int listeningPort = in.readInt();
        if (listeningPort < 1024 || listeningPort > 65535) {
            return reply(out, "ERR", "Invalid port");
        }
        long now = System.currentTimeMillis();
        Long previous = lastProbe.put(peerId, now);
        if (previous != null && now - previous < 3_000) {
            return reply(out, "ERR", "Probe too frequent");
        }
        boolean reachable;
        try (Socket target = new Socket()) {
            target.connect(new InetSocketAddress(socket.getInetAddress(), listeningPort), PROBE_TIMEOUT_MS);
            reachable = true;
        } catch (IOException e) {
            reachable = false;
        }
        out.writeUTF("OK");
        out.writeUTF(socket.getInetAddress().getHostAddress());
        out.writeBoolean(reachable);
        out.flush();
        return false;
    }

    /** Peer nguồn mở kênh điều khiển ra ngoài để Tracker báo có người muốn kết nối. */
    private boolean register(Socket socket, DataInputStream in, DataOutputStream out) throws Exception {
        UUID peerId = authenticate(in.readUTF());
        if (!relayEnabled) {
            return reply(out, "ERR", "Relay is disabled on this Tracker");
        }
        Control control = new Control(socket, out);
        Control old = controls.put(peerId, control);
        if (old != null) {
            closeQuietly(old.socket());
        }
        synchronized (out) {
            out.writeUTF("OK");
            out.flush();
        }
        socket.setSoTimeout(0);
        return true;
    }

    /** Downloader xin relay tới peer đích. */
    private boolean connect(Socket socket, DataInputStream in, DataOutputStream out) throws Exception {
        authenticate(in.readUTF());
        UUID target = UUID.fromString(in.readUTF());
        if (!relayEnabled) {
            return reply(out, "ERR", "Relay is disabled on this Tracker");
        }
        Control control = controls.get(target);
        if (control == null) {
            return reply(out, "ERR", "Target peer is not registered for relay");
        }
        if (!relaySlots.tryAcquire()) {
            return reply(out, "ERR", "Relay is busy");
        }
        String token = UUID.randomUUID().toString();
        CompletableFuture<Socket> waiting = new CompletableFuture<>();
        pending.put(token, new Pending(target, waiting));
        Socket source = null;
        try {
            try {
                synchronized (control.out()) {
                    control.out().writeUTF("INCOMING");
                    control.out().writeUTF(token);
                    control.out().flush();
                }
            } catch (IOException e) {
                controls.remove(target, control);
                closeQuietly(control.socket());
                return reply(out, "ERR", "Target peer relay channel is down");
            }
            try {
                source = waiting.get(PAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                return reply(out, "ERR", "Target peer did not answer in time");
            }
            out.writeUTF("OK");
            out.flush();
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            source.setSoTimeout(IDLE_TIMEOUT_MS);
            pipe(socket, source);
            return false;
        } finally {
            pending.remove(token);
            relaySlots.release();
            if (source == null && !waiting.cancel(false)) {
                closeQuietly(waiting.getNow(null)); // peer nguồn tới trễ
            }
        }
    }

    /** Peer nguồn mở kết nối dữ liệu theo token. */
    private boolean accept(Socket socket, DataInputStream in, DataOutputStream out) throws Exception {
        UUID peerId = authenticate(in.readUTF());
        String token = in.readUTF();
        Pending p = pending.get(token);
        if (p == null || !p.target().equals(peerId)) {
            return reply(out, "ERR", "Unknown relay token");
        }
        out.writeUTF("OK");
        out.flush();
        return p.future().complete(socket);
    }

    private void pipe(Socket a, Socket b) {
        AtomicLong budget = new AtomicLong(MAX_BYTES_PER_PIPE);
        try {
            pool.execute(() -> copy(b, a, budget));
        } catch (RejectedExecutionException e) {
            closeQuietly(a);
            closeQuietly(b);
            return;
        }
        copy(a, b, budget);
    }

    private static void copy(Socket from, Socket to, AtomicLong budget) {
        byte[] buffer = new byte[16 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (budget.addAndGet(-n) < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // một phía đóng hoặc idle timeout
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void pingControls() {
        for (Map.Entry<UUID, Control> e : controls.entrySet()) {
            Control c = e.getValue();
            try {
                synchronized (c.out()) {
                    c.out().writeUTF("PING");
                    c.out().flush();
                }
            } catch (IOException ex) {
                controls.remove(e.getKey(), c);
                closeQuietly(c.socket());
            }
        }
    }

    private UUID authenticate(String sessionId) throws Exception {
        return resolver.resolvePeerId(UUID.fromString(sessionId));
    }

    private static boolean reply(DataOutputStream out, String status, String message) throws IOException {
        out.writeUTF(status);
        if (message != null) {
            out.writeUTF(message);
        }
        out.flush();
        return false;
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Override
    public void close() {
        running = false;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        for (Control c : controls.values()) {
            closeQuietly(c.socket());
        }
        pinger.shutdownNow();
        pool.shutdownNow();
    }
}