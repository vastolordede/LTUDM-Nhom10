package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.auth.AuthClientService;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    @FXML private Button previousSharePage, nextSharePage, stopFolderButton;
    @FXML private Label sharePageLabel;
    @FXML private javafx.scene.layout.HBox sharePageNumbers;
    private ObservableList<SharedFileEntry> sharedFiles;
    private FolderClientService folderClientService;
    private boolean busy;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "peer-myfiles-io");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object executorLock = new Object();

    private ShareFileClientService shareFileClientService;
    private String sessionId;
    private LocalFileRegistry fileRegistry;

    @FXML
    private void initialize() {
        statusLabel.setText("");

        myFilesListView.getSelectionModel().selectedItemProperty().addListener((o,a,b)->updateButtons());
        updateButtons();
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
        this.sharedFiles=sharedFiles;
        this.folderClientService=new FolderClientService(connection);
        new ShareListPager(myFilesListView,sharedFiles,previousSharePage,nextSharePage,sharePageLabel,sharePageNumbers);
    }

    public void configure(TrackerConnection connection, String sessionId,
                          ObservableList<SharedFileEntry> sharedFiles, LocalFileRegistry registry) {
        configure(connection, sessionId, sharedFiles);
        this.fileRegistry = registry;
    }

    /** T22: stop sharing the selected file. */
    @FXML
    private void onStopSharing() {
        if (closed.get() || busy) return;
        SharedFileEntry selected = myFilesListView.getSelectionModel().getSelectedItem();
        if (selected == null || shareFileClientService == null) {
            return;
        }

        busy=true; updateButtons();
        statusLabel.setText("Đang ngừng chia sẻ \"" + selected.fileName() + "\"…");

        long generation = AuthClientService.sessionGeneration();
        submitIfOpen(() -> {
            try {
                synchronized (fileRegistry == null ? this : fileRegistry.shareMutationLock()) {
                    if (fileRegistry != null) fileRegistry.disableSharing(selected.fileId());
                    shareFileClientService.unshare(sessionId, selected.fileId());
                    String note=ShareState.stopFile(fileRegistry,SharedFilesStore.current(),selected.fileId());
                    postToCurrentSession(generation, () -> {
                        sharedFiles.removeIf(x->x.fileId()==selected.fileId());
                        statusLabel.setText("Đã ngừng chia sẻ tệp " + selected.fileName()+"."+note);
                        busy=false; updateButtons();
                    });
                }
            } catch (Exception e) {
                postToCurrentSession(generation, () -> {
                    statusLabel.setText("Đã dừng tải lên trên thiết bị này. Máy chủ chưa xác nhận; hãy thử lại.");
                    busy=false; updateButtons();
                });
            }
        });
    }

    private void submitIfOpen(Runnable task) {
        synchronized (executorLock) {
            if (!closed.get()) ioExecutor.submit(task);
        }
    }

    private void postToCurrentSession(long generation, Runnable action) {
        if (closed.get() || !AuthClientService.isCurrentGeneration(generation)) return;
        Platform.runLater(() -> {
            if (!closed.get() && AuthClientService.isCurrentGeneration(generation)) action.run();
        });
    }

    private void updateButtons() {
        var selected=myFilesListView.getSelectionModel().getSelectedItem();
        stopSharingButton.setDisable(busy||selected==null);
        stopFolderButton.setDisable(busy||selected==null||selected.groups().isEmpty());
    }

    @FXML private void onStopFolder() {
        var row=myFilesListView.getSelectionModel().getSelectedItem();
        if(closed.get()||busy||row==null||row.groups().isEmpty())return;
        var choice=new javafx.scene.control.ChoiceDialog<>(row.groups().getFirst(),row.groups());
        choice.setTitle("Ngừng chia sẻ thư mục"); choice.setHeaderText("Chọn lượt chia sẻ cần ngừng");
        var selected=choice.showAndWait(); if(selected.isEmpty())return;
        busy=true; updateButtons(); long generation=AuthClientService.sessionGeneration();
        submitIfOpen(()->{
            try { synchronized(fileRegistry==null?this:fileRegistry.shareMutationLock()) {
                var result=folderClientService.unshareFolder(sessionId,selected.get().groupId());
                String note=ShareState.stopFolder(fileRegistry,SharedFilesStore.current(),result);
                postToCurrentSession(generation,()->{
                    ShareState.updateFolderRows(sharedFiles,result);
                    statusLabel.setText("Đã ngừng chia sẻ thư mục. Tệp chia sẻ riêng hoặc thuộc thư mục khác vẫn được giữ."+note);
                    busy=false;updateButtons();
                });
            }}catch(Exception e){postToCurrentSession(generation,()->{statusLabel.setText("Không thể ngừng chia sẻ thư mục.");busy=false;updateButtons();});}
        });
    }

    public void shutdown() {
        synchronized (executorLock) {
            if (!closed.compareAndSet(false, true)) return;
            ioExecutor.shutdownNow();
        }
        try {
            ioExecutor.awaitTermination(250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean executorTerminated() { return ioExecutor.isTerminated(); }
}
