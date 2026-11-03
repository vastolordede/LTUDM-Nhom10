package vn.edu.p2p.peer.transfer;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.stage.Stage;
import javafx.application.Platform;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.share.MyFilesController;
import vn.edu.p2p.peer.share.ExploreController;
import vn.edu.p2p.peer.share.ShareController;
import vn.edu.p2p.peer.share.FolderController;
import vn.edu.p2p.common.config.EnvConfig;
import vn.edu.p2p.peer.network.RendezvousClient;
import vn.edu.p2p.peer.auth.PeerKeyStore;
import vn.edu.p2p.peer.auth.TicketRevocationList;

import java.io.IOException;
import java.security.KeyPair;

/** One user-facing window, one shared file registry and one upload server per session. */
public final class TransferDashboardWindow implements AutoCloseable {
    private static final String VIEW_PATH = "/vn/edu/p2p/peer/view/";

    private final LocalFileRegistry fileRegistry;
    private final PeerP2pSocketFactory socketFactory;
    private final PeerUploadServer uploadServer;
    private ShareController shareController;
    private ExploreController exploreController;
    private MyFilesController myFilesController;
    private DownloadController downloadController;
    private TransferHistoryController historyController;
    private Stage stage;
    private volatile boolean closed;
    private volatile Thread natProbeThread;
    private final boolean p2pTls;
    private RelayAgent relayAgent;
    private volatile RendezvousClient rendezvousClient;
    private FolderController folderController;
    private Runnable onLogoutRequested;
    private Button logoutButton;
    private Label dashboardMessage;
    private StackPane contentPane;
    private Map<String, Parent> views;
    private Map<String, Button> navButtons;

    /** Logout runs through LoginController so heartbeat/session are closed once. */
    public void setOnLogoutRequested(Runnable action) {
        this.onLogoutRequested = action;
    }

    public void setLogoutPending(boolean busy) {
        if (logoutButton != null) logoutButton.setDisable(busy);
    }

    public void showMessage(String message) {
        if (dashboardMessage != null) dashboardMessage.setText(message);
    }


    public TransferDashboardWindow(int listeningPort, PeerP2pSocketConfig p2pConfig) {
        this.p2pTls = p2pConfig.tlsEnabled();
        this.fileRegistry = new LocalFileRegistry();
        this.socketFactory = new PeerP2pSocketFactory(p2pConfig);
        this.uploadServer = new PeerUploadServer(listeningPort, fileRegistry, socketFactory);
    }

    public void show(TrackerConnection connection, String sessionId, String peerId,
                     TicketRevocationList ticketRevocations) throws IOException {
        if (stage != null && !closed) {
            stage.show();
            stage.toFront();
            return;
        }
        if (closed) {
            throw new IllegalStateException("Dashboard was closed; create a new instance");
        }
        uploadServer.setUploaderPeerId(peerId);
        uploadServer.setTicketRevocationList(ticketRevocations);
        KeyPair peerKeys = PeerKeyStore.defaultStore().loadOrCreate(peerId);
        uploadServer.start();
        try {
            FXMLLoader shareLoader = loader("share-view.fxml");
            Parent shareRoot = shareLoader.load();
            shareController = shareLoader.getController();
            shareController.configure(connection, sessionId, fileRegistry);

            FXMLLoader myFilesLoader = loader("my-files-view.fxml");
            Parent myFilesRoot = myFilesLoader.load();
            myFilesController = myFilesLoader.getController();
            myFilesController.configure(connection, sessionId, shareController.sharedFiles(), fileRegistry);

            FXMLLoader downloadLoader = loader("download-view.fxml");
            Parent downloadRoot = downloadLoader.load();
            downloadController = downloadLoader.getController();
            downloadController.configure(connection, sessionId, fileRegistry, socketFactory,
                    uploadServer, peerKeys.getPrivate());

            FXMLLoader folderLoader = loader("folder-view.fxml");
            Parent folderRoot = folderLoader.load();
            folderController = folderLoader.getController();
            folderController.configure(connection, sessionId, fileRegistry, socketFactory,
                    peerKeys.getPrivate());

            FXMLLoader exploreLoader = loader("explore-view.fxml");
            Parent exploreRoot = exploreLoader.load();
            exploreController = exploreLoader.getController();

            FXMLLoader historyLoader = loader("history-view.fxml");
            Parent historyRoot = historyLoader.load();
            historyController = historyLoader.getController();
            historyController.configure(connection, sessionId);

            views = new LinkedHashMap<>();
            views.put("Khám phá", exploreRoot);
            views.put("Chia sẻ", shareRoot);
            views.put("Tệp của tôi", myFilesRoot);
            views.put("Đang tải", downloadRoot);
            views.put("Chi tiết thư mục", folderRoot);
            views.put("Lịch sử", historyRoot);
            contentPane = new StackPane();
            contentPane.getStyleClass().add("content-pane");
            contentPane.getChildren().addAll(views.values());
            navButtons = new LinkedHashMap<>();
            VBox sidebar = new VBox(6);
            sidebar.getStyleClass().add("app-sidebar");
            Label sidebarBrand = new Label("FILESHARE");
            sidebarBrand.getStyleClass().add("sidebar-brand");
            Label sidebarTag = new Label("P2P  •  DESKTOP");
            sidebarTag.getStyleClass().add("sidebar-tagline");
            sidebar.getChildren().addAll(sidebarBrand, sidebarTag);
            for (String name : views.keySet()) {
                Button nav = new Button(name);
                nav.getStyleClass().add("nav-button");
                nav.setMaxWidth(Double.MAX_VALUE);
                nav.setOnAction(event -> showSection(name));
                sidebar.getChildren().add(nav);
                navButtons.put(name, nav);
            }
            Region sideSpacer = new Region();
            VBox.setVgrow(sideSpacer, Priority.ALWAYS);
            sidebar.getChildren().add(sideSpacer);
            Label sidebarFooter = new Label("Tracker  •  Session online");
            sidebarFooter.getStyleClass().add("sidebar-footer");
            sidebar.getChildren().add(sidebarFooter);
            HBox body = new HBox(sidebar, contentPane);
            HBox.setHgrow(contentPane, Priority.ALWAYS);

            exploreController.configure(connection, sessionId,
                    item -> {
                        downloadController.prefillFromSearchResult(item);
                        showSection("Đang tải");
                    }, folder -> {
                        folderController.prefillFromExplore(folder);
                        showSection("Chi tiết thư mục");
                    });
            showSection("Khám phá");

            logoutButton = new Button("Đăng xuất");
            logoutButton.getStyleClass().add("danger-button");
            logoutButton.setOnAction(event -> {
                if (onLogoutRequested != null) onLogoutRequested.run();
            });
            dashboardMessage = new Label("Tracker: phiên đăng nhập đang hoạt động");
            dashboardMessage.getStyleClass().add("connection-label");
            HBox toolbar = new HBox(12);
            toolbar.setAlignment(Pos.CENTER_LEFT);
            toolbar.setPadding(new Insets(12, 18, 12, 18));
            toolbar.getStyleClass().add("app-toolbar");
            Label heading = new Label("FILESHARE  /  PEER CENTER");
            heading.getStyleClass().add("toolbar-title");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            toolbar.getChildren().addAll(heading, spacer, dashboardMessage, logoutButton);
            BorderPane root = new BorderPane();
            root.getStyleClass().add("app-root");
            root.setTop(toolbar);
            root.setCenter(body);

            stage = new Stage();
            stage.setTitle("FileShare-P2P - Peer Center (Port: " + uploadServer.listeningPort() + ")");
            Scene scene = new Scene(root, 1024, 700);
            var css = TransferDashboardWindow.class.getResource(VIEW_PATH + "styles.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
            stage.setScene(scene);
            stage.setMinWidth(850);
            stage.setMinHeight(560);
            // Hide keeps the peer available for uploads; logout closes all background work.
            stage.setOnCloseRequest(event -> {
                event.consume();
                stage.hide();
            });
            stage.show();
            configureConnectivity(connection, sessionId);
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private static FXMLLoader loader(String viewName) throws IOException {
        var url = TransferDashboardWindow.class.getResource(VIEW_PATH + viewName);
        if (url == null) throw new IOException("Missing JavaFX resource: " + viewName);
        return new FXMLLoader(url);
    }

    private void showSection(String name) {
        if (views == null) return;
        views.forEach((key, node) -> {
            boolean active = key.equals(name);
            node.setVisible(active);
            node.setManaged(active);
        });
        navButtons.forEach((key, button) -> {
            button.getStyleClass().remove("nav-selected");
            if (key.equals(name)) button.getStyleClass().add("nav-selected");
        });
        if ("Lịch sử".equals(name) && historyController != null) {
            historyController.onRefreshHistory();
        }
    }

    /** D77-D80: kiểm tra NAT, bật relay, gắn planner cho các màn hình tải. */
    private void configureConnectivity(TrackerConnection connection, String sessionId) {
        try {
            RendezvousClient rendezvous = new RendezvousClient(
                    connection.configuration(), EnvConfig.integer("RENDEZVOUS_PORT", 9001));
            rendezvousClient = rendezvous;
            boolean relayWanted = EnvConfig.flag("RELAY_ENABLED", false);
            boolean relayAllowed = relayWanted;
            ConnectionPlanner planner = new ConnectionPlanner(
                    socketFactory, relayAllowed ? rendezvous : null, sessionId);
            downloadController.setConnectionPlanner(planner);
            folderController.setConnectionPlanner(planner);
            if (relayAllowed) {
                relayAgent = new RelayAgent(rendezvous, sessionId, uploadServer);
                relayAgent.start();
            }
            String baseTitle = stage.getTitle();
            String relayNote = relayAllowed
                    ? (p2pTls ? " | Relay dang ky (P2P TLS)" : " | Relay dang ky (TCP)") : "";
            Thread probe = new Thread(() -> {
                String status;
                try {
                    RendezvousClient.Probe p = rendezvous.probe(sessionId, uploadServer.listeningPort());
                    status = p.reachable()
                            ? "Truy cap duoc tu ngoai (" + p.observedIp() + ")"
                            : "Khong truy cap duoc tu ngoai (NAT/firewall)";
                } catch (IOException e) {
                    status = "Chua kiem tra duoc ket noi ngoai";
                }
                String title = baseTitle + " | " + status + relayNote;
                final String display = status + (relayAllowed
                        ? " | Relay: requested, check Tracker connection" : " | Relay: disabled");
                Platform.runLater(() -> {
                    if (stage != null && !closed) {
                        stage.setTitle(title);
                        if (dashboardMessage != null) dashboardMessage.setText(display);
                    }
                });
            }, "peer-nat-probe");
            probe.setDaemon(true);
            natProbeThread = probe;
            probe.start();
        } catch (RuntimeException e) {
            if (dashboardMessage != null) {
                dashboardMessage.setText("Cannot configure Direct/Relay: " + e.getMessage());
            }
        }
    }

    public LocalFileRegistry fileRegistry() { return fileRegistry; }
    public DownloadController downloadController() { return downloadController; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Thread probe = natProbeThread;
        if (probe != null) probe.interrupt();
        RendezvousClient rendezvous = rendezvousClient;
        if (rendezvous != null) rendezvous.close();
        if (stage != null) {
            stage.setOnCloseRequest(null);
            stage.close();
        }
        FolderController folder = folderController;
        DownloadController download = downloadController;
        RelayAgent relay = relayAgent;
        ShareController share = shareController;
        ExploreController explore = exploreController;
        MyFilesController myFiles = myFilesController;
        TransferHistoryController history = historyController;
        Thread teardown = new Thread(() -> {
            if (download != null && folder != null) {
                Thread downloadShutdown = new Thread(download::shutdown, "peer-download-shutdown");
                downloadShutdown.start();
                folder.shutdown();
                joinShutdownThread(downloadShutdown);
            } else {
                if (download != null) download.shutdown();
                if (folder != null) folder.shutdown();
            }
            if (relay != null) relay.close();
            uploadServer.close();
            if (share != null) share.shutdown();
            if (explore != null) explore.shutdown();
            if (myFiles != null) myFiles.shutdown();
            if (history != null) history.shutdown();
        }, "peer-dashboard-teardown");
        teardown.setDaemon(false);
        teardown.start();
        try {
            teardown.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logoutButton = null;
        dashboardMessage = null;
        contentPane = null;
        views = null;
        navButtons = null;
    }

    private static void joinShutdownThread(Thread thread) {
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
