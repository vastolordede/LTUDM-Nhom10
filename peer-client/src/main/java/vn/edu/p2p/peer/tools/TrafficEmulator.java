package vn.edu.p2p.peer.tools;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.peer.auth.AuthClientService;
import vn.edu.p2p.peer.auth.PeerIdentity;
import vn.edu.p2p.peer.auth.PeerIdentityStore;
import vn.edu.p2p.peer.network.PeerSettings;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.session.ClientSession;
import vn.edu.p2p.peer.share.FileMetadataBuilder;
import vn.edu.p2p.peer.share.FileSourceClientService;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.ShareFileClientService;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;
import vn.edu.p2p.peer.transfer.PeerP2pSocketConfig;
import vn.edu.p2p.peer.transfer.PeerP2pSocketFactory;
import vn.edu.p2p.peer.transfer.PeerUploadServer;
import vn.edu.p2p.peer.transfer.PeerDownloadClient;
import vn.edu.p2p.peer.transfer.MultiSourceDownloadEngine;
import java.util.concurrent.atomic.AtomicBoolean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * B03: giả lập N virtual peer (không GUI): login, share file, heartbeat,
 * thỉnh thoảng logout/login lại (ONLINE/OFFLINE) và phục vụ upload.
 *
 * Tham số: <peerCount> <username> <password> <basePort> <durationSeconds> [download]
 * Optional download workload measures REAL simultaneous transfers, not only heartbeat.
 */
public final class TrafficEmulator {
    private static final AtomicInteger ONLINE = new AtomicInteger();
    private static final AtomicInteger ERRORS = new AtomicInteger();
    private static final AtomicInteger DOWNLOADED = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: <peerCount> <username> <password> <basePort> <durationSeconds> [download]");
            return;
        }
        int count = Integer.parseInt(args[0]);
        String user = args[1];
        String pass = args[2];
        int basePort = Integer.parseInt(args[3]);
        boolean runDownloads = args.length >= 6 && "download".equalsIgnoreCase(args[5]);
        if (count < 1 || count > 100 || basePort < 1024 || basePort + count > 65536) {
            throw new IllegalArgumentException("Invalid emulator peer count or port range");
        }
        long deadline = System.currentTimeMillis() + Long.parseLong(args[4]) * 1000L;

        Path work = Path.of(System.getProperty("user.home"), ".fileshare-p2p", "emulator");
        Files.createDirectories(work);
        Path common = work.resolve("common.bin"); // cùng nội dung ở mọi peer -> multi-source
        if (!Files.exists(common)) {
            byte[] data = new byte[2 * 1024 * 1024];
            new Random(42).nextBytes(data);
            Files.write(common, data);
        }

        PeerSettings settings = PeerSettings.load();
        PeerP2pSocketConfig p2p = PeerP2pSocketConfig.fromEnvironment();

        ExecutorService pool = Executors.newFixedThreadPool(count);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            pool.submit(() -> runPeer(idx, count, runDownloads, basePort + idx, user, pass,
                    settings, p2p, work, common, deadline));
        }
        pool.shutdown();
        while (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
            System.out.printf("[emulator] online=%d downloads=%d errors=%d%n",
                    ONLINE.get(), DOWNLOADED.get(), ERRORS.get());
        }
        System.out.printf("[emulator] DONE downloads=%d errors=%d%n", DOWNLOADED.get(), ERRORS.get());
    }

    private static void runPeer(int idx, int count, boolean runDownloads, int port,
                                String user, String pass, PeerSettings settings,
                                PeerP2pSocketConfig p2p, Path work, Path common, long deadline) {
        String tag = "vpeer-" + idx;
        boolean online = false;
        try (TrackerConnection connection = new TrackerConnection(settings.trackerConnectionConfig())) {
            PeerIdentity identity = new PeerIdentityStore(work.resolve("peer-" + idx + ".properties")).loadOrCreate();
            AuthClientService auth = new AuthClientService(connection, identity);
            LocalFileRegistry registry = new LocalFileRegistry();
            try (PeerUploadServer server = new PeerUploadServer(port, registry, new PeerP2pSocketFactory(p2p))) {
                server.start();
                ClientSession session = auth.login(user, pass, port);
                ONLINE.incrementAndGet();
                online = true;

                ShareFileClientService share = new ShareFileClientService(connection);
                FileMetadataBuilder builder = new FileMetadataBuilder();
                Path own = work.resolve("own-" + idx + ".bin");
                if (!Files.exists(own)) {
                    byte[] data = new byte[512 * 1024];
                    new Random(1000L + idx).nextBytes(data);
                    Files.write(own, data);
                }
                long commonFileId = -1;
                for (Path p : new Path[]{common, own}) {
                    FileMetadata meta = builder.build(p);
                    ShareFileResponse r = share.publish(session.sessionId(), meta);
                    registry.registerCompleteFile(r.fileId(), p, meta);
                    if (p.equals(common)) commonFileId = r.fileId();
                }
                if (runDownloads && count > 1) {
                    // Allow all virtual peers to publish and start serving before the burst.
                    long warmupEnds = Math.min(deadline, System.currentTimeMillis() + 8_000);
                    while (System.currentTimeMillis() < warmupEnds) {
                        Thread.sleep(1_000);
                        auth.heartbeat();
                    }
                    var available = new FileSourceClientService(connection)
                            .findSources(session.sessionId(), commonFileId).sources();
                    if (available.isEmpty()) {
                        throw new java.io.IOException("No online peer source for emulator download");
                    }
                    Path target = work.resolve("received-" + idx + ".bin");
                    Files.deleteIfExists(target);
                    var engine = new MultiSourceDownloadEngine(registry,
                            new PeerDownloadClient(new PeerP2pSocketFactory(p2p)));
                    var result = engine.downloadFile(commonFileId, available, target,
                            null, new AtomicBoolean(false));
                    if (!"COMPLETED".equals(result.status()) || !FileHashUtil.sha256(target)
                            .equalsIgnoreCase(FileHashUtil.sha256(common))) {
                        throw new java.io.IOException("Emulated download failed SHA/transfer check: "
                                + result.failureReason());
                    }
                    DOWNLOADED.incrementAndGet();
                    System.out.println("[" + tag + "] downloaded from "
                            + available.size() + " source peers");
                }

                long nextToggle = System.currentTimeMillis() + 15_000 + ThreadLocalRandom.current().nextLong(25_000);
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(5_000);
                    if (online) auth.heartbeat();
                    if (System.currentTimeMillis() >= nextToggle) {
                        if (online) {
                            auth.logout();
                            ONLINE.decrementAndGet();
                            online = false;
                        } else {
                            auth.login(user, pass, port);
                            ONLINE.incrementAndGet();
                            online = true;
                        }
                        nextToggle = System.currentTimeMillis() + 10_000 + ThreadLocalRandom.current().nextLong(20_000);
                    }
                }
                if (online) {
                    auth.logout();
                    ONLINE.decrementAndGet();
                }
            }
        } catch (Exception e) {
            ERRORS.incrementAndGet();
            System.err.println("[" + tag + "] " + e);
        }
    }
}