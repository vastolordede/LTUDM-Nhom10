package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My Files screen (T21): lists files this peer is currently sharing and
 * lets the user stop sharing one (T22).
 *
 * T23 (sync with peer_files): this list IS the same ObservableList
 * ShareController appends to right after a successful SHARE_FILE_REQUEST
 * (see ShareController.sharedFiles()), so both screens always show the
 * same state without a second round trip to the Tracker. Stopping a share
 * here removes the entry locally the moment the Tracker confirms it, so
 * Share screen and My Files screen never drift apart.
 */
public final class MyFilesController {

    @FXML
    private ListView<SharedFileEntry> myFilesListView;

    @FXML
    private Button stopSharingButton;

    @FXML
    private Label statusLabel;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "peer-myfiles-io");
        thread.setDaemon(true);
        return thread;
    });

    private ShareFileClientService shareFileClientService;
    private String sessionId;
    private LocalFileRegistry fileRegistry;

    @FXML
    private void initialize() {
        statusLabel.setText("");

        myFilesListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SharedFileEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.fileName());
            }
        });

        stopSharingButton.disableProperty().bind(
                myFilesListView.getSelectionModel().selectedItemProperty().isNull()
        );
    }

    /**
     * @param sharedFiles the SAME observable list ShareController.sharedFiles()
     *                     returns, so this screen stays in sync automatically.
     */
    public void configure(
            TrackerConnection connection,
            String sessionId,
            ObservableList<SharedFileEntry> sharedFiles
    ) {
        this.shareFileClientService = new ShareFileClientService(connection);
        this.sessionId = sessionId;
        myFilesListView.setItems(sharedFiles);
    }

    public void configure(TrackerConnection connection, String sessionId,
                          ObservableList<SharedFileEntry> sharedFiles, LocalFileRegistry registry) {
        configure(connection, sessionId, sharedFiles);
        this.fileRegistry = registry;
    }

    /** T22: stop sharing the selected file. */
    @FXML
    private void onStopSharing() {
        SharedFileEntry selected = myFilesListView.getSelectionModel().getSelectedItem();
        if (selected == null || shareFileClientService == null) {
            return;
        }

        statusLabel.setText("Đang ngừng chia sẻ \"" + selected.fileName() + "\"...");

        ioExecutor.submit(() -> {
            try {
                shareFileClientService.unshare(sessionId, selected.fileId());
                Platform.runLater(() -> onStopSuccess(selected));
            } catch (Exception e) {
                Platform.runLater(() ->
                        statusLabel.setText("Lỗi khi ngừng chia sẻ: " + e.getMessage())
                );
            }
        });
    }

    private void onStopSuccess(SharedFileEntry entry) {
        SharedFilesStore store = SharedFilesStore.current();
        if (fileRegistry != null) {
            if (store != null) {
                fileRegistry.find(entry.fileId()).ifPresent(reg -> {
                    try {
                        store.removeByHash(reg.metadata().fileHash());
                    } catch (java.io.IOException ignored) {
                    }
                });
            }
            fileRegistry.unregisterFile(entry.fileId());
        }
        myFilesListView.getItems().remove(entry);
        statusLabel.setText("Đã ngừng chia sẻ \"" + entry.fileName() + "\".");
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
    }
}
