package vn.edu.p2p.tracker;

import vn.edu.p2p.common.config.EnvConfig;
import vn.edu.p2p.tracker.auth.BCryptPasswordService;
import vn.edu.p2p.tracker.auth.DefaultAuthService;
import vn.edu.p2p.tracker.catalog.FileCatalogService;
import vn.edu.p2p.tracker.catalog.FileRepository;
import vn.edu.p2p.tracker.catalog.FolderCatalogService;
import vn.edu.p2p.tracker.catalog.JdbcFileRepository;
import vn.edu.p2p.tracker.catalog.JdbcPeerFileRepository;
import vn.edu.p2p.tracker.catalog.JdbcSessionPeerResolver;
import vn.edu.p2p.tracker.catalog.JdbcShareEventRepository;
import vn.edu.p2p.tracker.catalog.PeerFileRepository;
import vn.edu.p2p.tracker.catalog.SessionPeerResolver;
import vn.edu.p2p.tracker.catalog.ShareEventRepository;
import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;
import vn.edu.p2p.tracker.config.TrackerSettings;
import vn.edu.p2p.tracker.logging.TrackerLogging;
import vn.edu.p2p.tracker.network.RendezvousServer;
import vn.edu.p2p.tracker.network.TrackerRequestDispatcher;
import vn.edu.p2p.tracker.network.TrackerServerSocketProvider;
import vn.edu.p2p.tracker.network.TlsTrackerServerSocketProvider;
import vn.edu.p2p.tracker.network.TrackerServer;
import vn.edu.p2p.tracker.peer.HeartbeatMonitor;
import vn.edu.p2p.tracker.peer.PeerSessionService;
import vn.edu.p2p.tracker.repository.FileSourceRepository;
import vn.edu.p2p.tracker.repository.PeerRepository;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;
import vn.edu.p2p.tracker.repository.StatisticsRepository;
import vn.edu.p2p.tracker.repository.UserRepository;
import vn.edu.p2p.tracker.repository.jdbc.JdbcFileSourceRepository;
import vn.edu.p2p.tracker.repository.jdbc.JdbcPeerRepository;
import vn.edu.p2p.tracker.repository.jdbc.JdbcPeerSessionRepository;
import vn.edu.p2p.tracker.repository.jdbc.JdbcStatisticsRepository;
import vn.edu.p2p.tracker.repository.jdbc.JdbcUserRepository;
import vn.edu.p2p.tracker.source.FileSourceService;
import vn.edu.p2p.tracker.statistics.TrackerStatisticsMonitor;
import vn.edu.p2p.tracker.statistics.TrackerStatisticsService;
import vn.edu.p2p.tracker.transfer.JdbcTransferHistoryRepository;
import vn.edu.p2p.tracker.transfer.TransferHistoryService;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.Logger;

public final class TrackerApplication {
    private static final Logger LOG = Logger.getLogger(TrackerApplication.class.getName());

    private TrackerApplication() {
    }

    public static void main(String[] args) throws Exception {
        TrackerSettings settings = TrackerSettings.load();
        TrackerLogging.configure(settings.logLevel());

        DatabaseConnectionFactory connectionFactory =
                new DatabaseConnectionFactory(settings.database());
        connectionFactory.verify();

        UserRepository users = new JdbcUserRepository(connectionFactory);
        PeerRepository peers = new JdbcPeerRepository(connectionFactory);
        PeerSessionRepository sessions =
                new JdbcPeerSessionRepository(connectionFactory);
        StatisticsRepository statistics =
                new JdbcStatisticsRepository(connectionFactory);
        FileSourceRepository fileSources =
                new JdbcFileSourceRepository(connectionFactory);

        // T11-T25: Share / Search / Unshare (File Catalog).
        FileRepository files = new JdbcFileRepository(connectionFactory);
        PeerFileRepository peerFiles = new JdbcPeerFileRepository(connectionFactory);
        ShareEventRepository shareEvents = new JdbcShareEventRepository(connectionFactory);
        SessionPeerResolver sessionPeerResolver = new JdbcSessionPeerResolver(connectionFactory);

        DefaultAuthService authService = new DefaultAuthService(
                users,
                peers,
                sessions,
                new BCryptPasswordService(),
                settings.heartbeatIntervalSeconds()
        );
        PeerSessionService peerSessionService = new PeerSessionService(sessions);

        FileSourceService fileSourceService = new FileSourceService(
                fileSources,
                peerSessionService
        );

        FileCatalogService fileCatalogService = new FileCatalogService(
                sessionPeerResolver,
                files,
                peerFiles,
                shareEvents
        );

        TransferHistoryService transferHistoryService = new TransferHistoryService(
                sessionPeerResolver, new JdbcTransferHistoryRepository(connectionFactory), peerFiles
        );

        // D64, D72, D73: chia sẻ thư mục
        FolderCatalogService folderCatalogService =
                new FolderCatalogService(sessionPeerResolver, connectionFactory);

        TrackerRequestDispatcher dispatcher =
                new TrackerRequestDispatcher(
                        authService,
                        peerSessionService,
                        fileSourceService,
                        fileCatalogService,
                        transferHistoryService
                ).withFolderCatalog(folderCatalogService);

        TrackerServerSocketProvider socketProvider =
                settings.tls().enabled()
                        ? new TlsTrackerServerSocketProvider(settings.tls())
                        : TrackerServerSocketProvider.plain();

        TrackerServer server = new TrackerServer(
                settings.trackerPort(),
                dispatcher,
                settings.workerThreads(),
                settings.workerQueueCapacity(),
                settings.socketReadTimeoutMillis(),
                socketProvider,
                settings.tls().enabled() ? "TLS" : "TCP"
        );

        // D77, D79: cổng Rendezvous (NAT probe + relay), chạy ở thread riêng
        RendezvousServer rendezvous = new RendezvousServer(
                EnvConfig.integer("RENDEZVOUS_PORT", 9001),
                sessionPeerResolver,
                EnvConfig.flag("RELAY_ENABLED", false),
                socketProvider
        );
        Thread rendezvousThread = new Thread(() -> {
            try {
                rendezvous.start();
            } catch (IOException e) {
                LOG.warning("Rendezvous server stopped: " + e.getMessage());
            }
        }, "tracker-rendezvous-main");
        rendezvousThread.setDaemon(true);
        rendezvousThread.start();

        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(
                sessions,
                Duration.ofSeconds(settings.heartbeatTimeoutSeconds()),
                Duration.ofSeconds(settings.heartbeatIntervalSeconds())
        );
        TrackerStatisticsMonitor statisticsMonitor = new TrackerStatisticsMonitor(
                new TrackerStatisticsService(statistics),
                Duration.ofSeconds(settings.statisticsIntervalSeconds())
        );

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    statisticsMonitor.close();
                    heartbeatMonitor.close();
                    rendezvous.close();
                    server.close();
                }, "tracker-shutdown")
        );

        LOG.info(() -> String.format(
                "Tracker ready: port=%d, db=%s, heartbeat=%ds/%ds timeout, "
                        + "statistics=%ds, workers=%d, queue=%d, socketTimeout=%dms, "
                        + "transport=%s",
                settings.trackerPort(),
                settings.database().url(),
                settings.heartbeatIntervalSeconds(),
                settings.heartbeatTimeoutSeconds(),
                settings.statisticsIntervalSeconds(),
                settings.workerThreads(),
                settings.workerQueueCapacity(),
                settings.socketReadTimeoutMillis(),
                settings.tls().enabled() ? "TLS" : "TCP"
        ));

        heartbeatMonitor.start();
        statisticsMonitor.start();
        server.start();
    }
}