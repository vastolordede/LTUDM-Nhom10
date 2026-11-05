package vn.edu.p2p.tracker;

import vn.edu.p2p.common.config.EnvConfig;
import vn.edu.p2p.tracker.auth.BCryptPasswordService;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.SecurityProfile;
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
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;

import java.io.IOException;
import java.net.BindException;
import java.time.Duration;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class TrackerApplication {
    private static final Logger LOG = Logger.getLogger(TrackerApplication.class.getName());

    private TrackerApplication() {
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Exception e) {
            System.err.println(startupFailureMessage(e));
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        TrackerSettings settings = TrackerSettings.load();
        SecurityProfile.requireTracker(
                SecurityProfile.parse(EnvConfig.get("P2P_PROFILE", "development")),
                settings.tls().enabled(), PeerAccessTickets.localInsecureDemoEnabled());
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
        JdbcFileRepository jdbcFiles = new JdbcFileRepository(connectionFactory);
        FileRepository files = jdbcFiles;
        PeerFileRepository peerFiles = new JdbcPeerFileRepository(connectionFactory);
        ShareEventRepository shareEvents = new JdbcShareEventRepository(connectionFactory);
        SessionPeerResolver sessionPeerResolver = new JdbcSessionPeerResolver(connectionFactory);
        TicketRevocationRegistry revocationRegistry = new TicketRevocationRegistry();
        String trackerInstanceId = java.util.UUID.randomUUID().toString();

        DefaultAuthService authService = new DefaultAuthService(
                users,
                peers,
                sessions,
                new BCryptPasswordService(),
                settings.heartbeatIntervalSeconds(),
                revocationRegistry,
                java.time.Clock.systemUTC()
        );
        PeerSessionService peerSessionService =
                new PeerSessionService(sessions, revocationRegistry, trackerInstanceId);

        FileSourceService fileSourceService = new FileSourceService(
                fileSources,
                peerSessionService,
                peers,
                PeerAccessTickets.localInsecureDemoEnabled() ? null : PeerAccessTickets.requiredTrackerKey()
        );

        FileCatalogService fileCatalogService = new FileCatalogService(
                sessionPeerResolver,
                files,
                peerFiles,
                shareEvents,
                revocationRegistry
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
                LOG.warning("Rendezvous server stopped because of a network/configuration error.");
            }
        }, "tracker-rendezvous-main");
        rendezvousThread.setDaemon(true);
        rendezvousThread.start();

        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(
                sessions,
                Duration.ofSeconds(settings.heartbeatTimeoutSeconds()),
                Duration.ofSeconds(settings.heartbeatIntervalSeconds()),
                revocationRegistry
        );
        TrackerStatisticsMonitor statisticsMonitor = new TrackerStatisticsMonitor(
                new TrackerStatisticsService(statistics),
                Duration.ofSeconds(settings.statisticsIntervalSeconds())
        );

        // R05: repair READY after interrupted publish, preserve active partial metadata.
        ScheduledExecutorService metadataMaintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread worker = new Thread(r, "tracker-metadata-maintenance");
            worker.setDaemon(true);
            return worker;
        });
        metadataMaintenance.scheduleWithFixedDelay(() -> {
            try {
                int recovered = jdbcFiles.reconcileInterruptedPublications();
                if (recovered > 0) LOG.info("Recovered READY metadata: " + recovered);
            } catch (SQLException e) {
                LOG.warning("Metadata reconcile failed: " + e.getMessage());
            }
        }, 0, 60, TimeUnit.MINUTES);

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    metadataMaintenance.shutdownNow();
                    statisticsMonitor.close();
                    heartbeatMonitor.close();
                    rendezvous.close();
                    server.close();
                }, "tracker-shutdown")
        );

        LOG.info(() -> String.format(
                "Tracker ready: port=%d, database=verified, heartbeat=%ds/%ds timeout, "
                        + "statistics=%ds, workers=%d, queue=%d, socketTimeout=%dms, "
                        + "transport=%s",
                settings.trackerPort(),
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

    static String startupFailureMessage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindException) {
                return "Tracker không khởi động: cổng mạng đang được sử dụng. "
                        + "Kiểm tra TRACKER_PORT và RENDEZVOUS_PORT.";
            }
            if (cause instanceof SQLException sqlException) {
                String message = sqlException.getMessage();
                String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("schema") || normalized.contains("thiếu quan hệ")
                        || normalized.contains("thiếu cột")) {
                    return "Tracker không khởi động: schema DB chưa được xác minh. "
                            + "Áp dụng migration theo thứ tự rồi chạy verify_schema.sql.";
                }
                return "Tracker không khởi động: không kết nối hoặc xác minh được PostgreSQL. "
                        + "Kiểm tra dịch vụ DB, DB_URL, DB_USER, DB_PASSWORD và quyền truy cập.";
            }
            if (cause instanceof IllegalStateException state) {
                String message = state.getMessage();
                if (message != null && message.contains("Database password is empty")) {
                    return "Tracker không khởi động: thiếu mật khẩu PostgreSQL. "
                            + "Quản trị viên cần cấu hình DB_PASSWORD ngoài ứng dụng.";
                }
                if (message != null && message.contains("signing key missing")) {
                    return "Tracker không khởi động: thiếu private key ký ticket. "
                            + "Quản trị viên cần tạo hoặc cấu hình khóa trên máy Tracker.";
                }
                if (message != null && message.contains("does not match configured public key")) {
                    return "Tracker không khởi động: cặp khóa ký ticket không khớp. "
                            + "Quản trị viên cần xác minh khóa private/public trên Tracker.";
                }
                if (message != null && (message.contains("TLS context")
                        || message.toLowerCase(java.util.Locale.ROOT).contains("keystore"))) {
                    return "Tracker không khởi động: không nạp được TLS keystore. "
                            + "Kiểm tra tệp, định dạng và mật khẩu keystore.";
                }
            }
            if (cause instanceof IllegalArgumentException argumentException) {
                String message = argumentException.getMessage();
                if (message != null && (message.contains("PKCS8 Ed25519")
                        || message.contains("signing key"))) {
                    return "Tracker không khởi động: private key ký ticket không hợp lệ. "
                            + "Quản trị viên cần kiểm tra khóa trên máy Tracker.";
                }
                if (message != null && message.toLowerCase(java.util.Locale.ROOT)
                        .contains("keystore")) {
                    return "Tracker không khởi động: cấu hình TLS keystore chưa đầy đủ. "
                            + "Kiểm tra tệp, định dạng và mật khẩu keystore.";
                }
            }
        }
        return "Tracker không khởi động do cấu hình hoặc lỗi mạng. "
                + "Kiểm tra cấu hình quản trị và log hệ thống đã được bảo vệ.";
    }
}