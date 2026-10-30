package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.peer.network.RendezvousClient;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** D79: giữ kênh điều khiển tới Tracker, khi có người muốn tải thì mở kết nối dữ liệu ra ngoài. */
public final class RelayAgent implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RelayAgent.class.getName());

    private final RendezvousClient client;
    private final String sessionId;
    private final PeerUploadServer uploadServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService acceptors = new ThreadPoolExecutor(
            0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread t = new Thread(runnable, "peer-relay-accept");
                t.setDaemon(true);
                return t;
            });

    private volatile Socket control;
    private Thread thread;

    public RelayAgent(RendezvousClient client, String sessionId, PeerUploadServer uploadServer) {
        this.client = client;
        this.sessionId = sessionId;
        this.uploadServer = uploadServer;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::runLoop, "peer-relay-agent");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop() {
        int delay = 3_000;
        while (running.get()) {
            try (Socket channel = client.register(sessionId)) {
                control = channel;
                delay = 3_000;
                LOG.info("Relay channel registered with Tracker");
                DataInputStream in = new DataInputStream(channel.getInputStream());
                while (running.get()) {
                    String command = in.readUTF();
                    if ("INCOMING".equals(command)) {
                        String token = in.readUTF();
                        try {
                            acceptors.execute(() -> accept(token));
                        } catch (RejectedExecutionException e) {
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.INFO, "Relay channel lost, retry in " + delay + " ms: " + e.getMessage());
                }
            }
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
        try {
            Socket data = client.accept(sessionId, token);
            uploadServer.serveRelayedSocket(data);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Relay accept failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Socket c = control;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        acceptors.shutdownNow();
        if (thread != null) {
            thread.interrupt();
        }
    }
}