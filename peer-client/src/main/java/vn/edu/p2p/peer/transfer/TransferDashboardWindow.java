package vn.edu.p2p.peer.transfer;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.application.Platform;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.share.MyFilesController;
import vn.edu.p2p.peer.share.SearchController;
import vn.edu.p2p.peer.share.ShareController;
import vn.edu.p2p.peer.share.FolderController;
import vn.edu.p2p.common.config.EnvConfig;
import vn.edu.p2p.peer.network.PeerSettings;
import vn.edu.p2p.peer.network.RendezvousClient;

import java.io.IOException;

/** One user-facing window, one shared file registry and one upload server per session. */
public final class TransferDashboardWindow implements AutoCloseable {
    private static final String VIEW_PATH = "/vn/edu/p2p/peer/view/";

    private final LocalFileRegistry fileRegistry;
    private final PeerP2pSocketFactory socketFactory;
    private final PeerUploadServer uploadServer;
    private ShareController shareController;
    private SearchController searchController;
    private MyFilesController myFilesController;
    private DownloadController downloadController;
    private TransferHistoryController historyController;
    private Stage stage;
    private boolean closed;
    private final boolean p2pTls;
    private RelayAgent relayAgent;
    private FolderController folderController;

    public TransferDashboardWindow(int listeningPort, PeerP2pSocketConfig p2pConfig) {
        this.p2pTls = p2pConfig.tlsEnabled();
        this.fileRegistry = new LocalFileRegistry();
        this.socketFactory = new PeerP2pSocketFactory(p2pConfig);
        this.uploadServer = new PeerUploadServer(listeningPort, fileRegistry, socketFactory);
    }

    public void show(TrackerConnection connection, String sessionId) throws IOException {
        if (stage != null && !closed) {
            stage.show();
            stage.toFront();
            return;
        }
        if (closed) {
            throw new IllegalStateException("Dashboard was closed; create a new instance");
        }
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
            downloadController.configure(connection, sessionId, fileRegistry, socketFactory, uploadServer);

            FXMLLoader folderLoader = loader("folder-view.fxml");
            Parent folderRoot = folderLoader.load();
            folderController = folderLoader.getController();
            folderController.configure(connection, sessionId, fileRegistry, socketFactory);

            FXMLLoader searchLoader = loader("search-view.fxml");
            Parent searchRoot = searchLoader.load();
            searchController = searchLoader.getController();

            FXMLLoader historyLoader = loader("history-view.fxml");
            Parent historyRoot = historyLoader.load();
            historyController = historyLoader.getController();
            historyController.configure(connection, sessionId);

            Tab shareTab = tab("Chia se", shareRoot);
            Tab myFilesTab = tab("File cua toi", myFilesRoot);
            Tab searchTab = tab("Tim kiem", searchRoot);
            Tab downloadTab = tab("Tai file", downloadRoot);
            Tab historyTab = tab("Lich su", historyRoot);
            Tab folderTab = tab("Thu muc", folderRoot);
            historyTab.setOnSelectionChanged(event -> {
                if (historyTab.isSelected()) historyController.onRefreshHistory();
            });

            TabPane tabs = new TabPane(shareTab, myFilesTab, searchTab, folderTab, downloadTab, historyTab);
            searchController.configure(connection, sessionId, item -> {
                downloadController.prefillFromSearchResult(item);
                tabs.getSelectionModel().select(downloadTab);
            });

            stage = new Stage();
            stage.setTitle("FileShare-P2P - Peer Center (Port: " + uploadServer.listeningPort() + ")");
            stage.setScene(new Scene(tabs, 880, 600));
            // Hide keeps the peer available for uploads; logout closes all background work.
            stage.setOnCloseRequest(event -> {
                event.consume();
                stage.hide();
            });
            stage.show();
            configureConnectivity(sessionId);
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

    private static Tab tab(String name, Parent root) {
        Tab result = new Tab(name, root);
        result.setClosable(false);
        return result;
    }

    /** D77-D80: kiểm tra NAT, bật relay, gắn planner cho các màn hình tải. */
    private void configureConnectivity(String sessionId) {
        try {
            PeerSettings settings = PeerSettings.load();
            RendezvousClient rendezvous = new RendezvousClient(
                    settings, EnvConfig.integer("RENDEZVOUS_PORT", 9001));
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
            String relayNote = relayWanted && p2pTls ? " | Relay tat (P2P TLS dang bat)"
                    : relayAllowed ? " | Relay san sang" : "";
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
                Platform.runLater(() -> {
                    if (stage != null && !closed) {
                        stage.setTitle(title);
                    }
                });
            }, "peer-nat-probe");
            probe.setDaemon(true);
            probe.start();
        } catch (RuntimeException e) {
            System.err.println("Connectivity setup skipped: " + e.getMessage());
        }
    }

    public LocalFileRegistry fileRegistry() { return fileRegistry; }
    public DownloadController downloadController() { return downloadController; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (stage != null) {
            stage.setOnCloseRequest(null);
            stage.close();
        }
        if (shareController != null) shareController.shutdown();
        if (searchController != null) searchController.shutdown();
        if (myFilesController != null) myFilesController.shutdown();
        if (downloadController != null) downloadController.shutdown();
        if (historyController != null) historyController.shutdown();
        if (relayAgent != null) relayAgent.close();
        uploadServer.close();
    }
}
