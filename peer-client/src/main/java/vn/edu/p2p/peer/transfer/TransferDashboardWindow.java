package vn.edu.p2p.peer.transfer;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.share.MyFilesController;
import vn.edu.p2p.peer.share.SearchController;
import vn.edu.p2p.peer.share.ShareController;

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

    public TransferDashboardWindow(int listeningPort, PeerP2pSocketConfig p2pConfig) {
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
            historyTab.setOnSelectionChanged(event -> {
                if (historyTab.isSelected()) historyController.onRefreshHistory();
            });

            TabPane tabs = new TabPane(shareTab, myFilesTab, searchTab, downloadTab, historyTab);
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
        uploadServer.close();
    }
}
