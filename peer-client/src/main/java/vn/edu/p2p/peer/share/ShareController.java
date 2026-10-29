package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.stage.FileChooser;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;
import vn.edu.p2p.common.dto.FolderEntryDto;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import javafx.stage.DirectoryChooser;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the Share File screen (T01-T15).
 *
 * Same pattern as LoginController: FXML-injected fields, a single-thread
 * background executor for blocking I/O, and Platform.runLater to push
 * results back to the UI.
 *
 * sharedFiles() exposes the ObservableList backing sharedFilesListView so
 * MyFilesController can bind to the exact same list (T23: both screens
 * always agree on what is being shared, no separate query needed).
 *
 * IMPORTANT: after switching scenes from Login to Share (post-login),
 * the caller MUST call configure(trackerConnection, sessionId) once.
 */
public final class ShareController {

    @FXML
    private Button chooseFileButton;

    @FXML
    private Button shareButton;

    @FXML
    private Label selectedFileLabel;

    @FXML
    private ListView<SharedFileEntry> sharedFilesListView;

    @FXML
    private Label statusLabel;
   
    @FXML
    private Button chooseFolderButton;

    private FolderClientService folderClientService;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "peer-share-io");
        thread.setDaemon(true);
        return thread;
    });

    private final FileMetadataBuilder metadataBuilder = new FileMetadataBuilder();
    private final ObservableList<SharedFileEntry> sharedFiles = FXCollections.observableArrayList();

    private ShareFileClientService shareFileClientService;
    private String sessionId;
    private LocalFileRegistry fileRegistry;

    private Path selectedFile;

    @FXML
    private void initialize() {
        statusLabel.setText("");
        sharedFilesListView.setItems(sharedFiles);
        sharedFilesListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SharedFileEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.fileName());
            }
        });
    }

    /**
     * Wires this controller to the live Tracker connection and current
     * session, right after login succeeds.
     */
    public void configure(TrackerConnection connection, String sessionId) {
        configure(connection, sessionId, null);
    }

    public void configure(TrackerConnection connection, String sessionId, LocalFileRegistry registry) {
        this.shareFileClientService = new ShareFileClientService(connection);
        this.sessionId = sessionId;
        this.fileRegistry = registry;
        this.folderClientService = new FolderClientService(connection);
        restoreSharedFiles();
    }

    /** D70: publish lại các file đã Share ở lần chạy trước (còn tồn tại, đúng kích thước). */
    private void restoreSharedFiles() {
        SharedFilesStore store = SharedFilesStore.current();
        if (store == null || fileRegistry == null) {
            return;
        }
        ShareFileClientService service = shareFileClientService;
        String session = sessionId;
        ioExecutor.submit(() -> {
            for (SharedFilesStore.Entry saved : store.loadValid()) {
                try {
                    Path path = Path.of(saved.path());
                    FileMetadata metadata = metadataBuilder.build(path);
                    ShareFileResponse r = service.publish(session, metadata);
                    fileRegistry.registerCompleteFile(r.fileId(), path, metadata);
                    if (!metadata.fileHash().equalsIgnoreCase(saved.fileHash())) {
                        store.removeByHash(saved.fileHash());
                        store.add(new SharedFilesStore.Entry(saved.path(), metadata.fileName(),
                                metadata.fileSize(), metadata.fileHash()));
                    }
                    Platform.runLater(() -> {
                        sharedFiles.removeIf(x -> x.fileId() == r.fileId());
                        sharedFiles.add(new SharedFileEntry(r.fileId(), metadata.fileName()));
                    });
                } catch (Exception ignored) {
                    // bỏ qua file lỗi, các file còn lại vẫn khôi phục
                }
            }
        });
    }

    /** Same backing list as sharedFilesListView - pass this into MyFilesController.configure(...). */
    public ObservableList<SharedFileEntry> sharedFiles() {
        return sharedFiles;
    }

    /** T02: choose a file with FileChooser. */
    @FXML
    private void onChooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn file để chia sẻ");

        File file = chooser.showOpenDialog(chooseFileButton.getScene().getWindow());
        if (file == null) {
            return;
        }

        selectedFile = file.toPath();
        selectedFileLabel.setText(file.getName() + "  (" + file.length() + " bytes)");
        shareButton.setDisable(false);
        statusLabel.setText("");
    }

    /** T03-T09: build FileMetadata, then T11: publish it to the Tracker. */
    @FXML
    private void onShareFile() {
        if (selectedFile == null) {
            return;
        }

        if (shareFileClientService == null || sessionId == null) {
            statusLabel.setText("Chưa cấu hình phiên đăng nhập cho màn hình Share.");
            return;
        }

        shareButton.setDisable(true);
        statusLabel.setText("Đang đọc, băm file và gửi lên Tracker...");
        Path sharedPath = selectedFile;

        ioExecutor.submit(() -> {
            try {
                FileMetadata metadata = metadataBuilder.build(sharedPath);
                ShareFileResponse response = shareFileClientService.publish(sessionId, metadata);
                Platform.runLater(() -> onShareSuccess(metadata, response, sharedPath));
            } catch (Exception e) {
                Platform.runLater(() -> onError("Lỗi khi chia sẻ file: " + e.getMessage()));
            }
        });
    }

    private void onShareSuccess(FileMetadata metadata, ShareFileResponse response, Path sharedPath) {
        if (fileRegistry != null) {
            fileRegistry.registerCompleteFile(response.fileId(), sharedPath, metadata);
        }

        SharedFilesStore store = SharedFilesStore.current();
        if (store != null) {
            try {
                store.add(new SharedFilesStore.Entry(sharedPath.toAbsolutePath().toString(),
                        metadata.fileName(), metadata.fileSize(), metadata.fileHash()));
            } catch (IOException ignored) {
            }
        }

        String note = response.isNewFile()
                ? "File mới trong catalog"
                : "File đã tồn tại trên Tracker (trùng SHA-256), dùng lại file_id="
                        + response.fileId();

        statusLabel.setText(
                "Chia sẻ thành công \"" + metadata.fileName() + "\" - "
                        + metadata.pieceCount() + " piece. " + note
        );

        sharedFiles.removeIf(entry -> entry.fileId() == response.fileId());
        sharedFiles.add(new SharedFileEntry(response.fileId(), metadata.fileName()));
        shareButton.setDisable(false);
    }

    private void onError(String message) {
        statusLabel.setText(message);
        shareButton.setDisable(false);
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
    }

    /** D64: chọn thư mục, Share từng file rồi đăng ký nhóm lên Tracker. */
    @FXML
    private void onShareFolder() {
        if (shareFileClientService == null || folderClientService == null || sessionId == null) {
            statusLabel.setText("Chưa cấu hình phiên đăng nhập cho màn hình Share.");
            return;
        }
        File dir = new DirectoryChooser().showDialog(chooseFolderButton.getScene().getWindow());
        if (dir == null) {
            return;
        }
        chooseFolderButton.setDisable(true);
        statusLabel.setText("Đang quét thư mục...");
        SharedFilesStore store = SharedFilesStore.current();

        ioExecutor.submit(() -> {
            try {
                List<FolderScanner.Entry> entries = FolderScanner.scan(dir.toPath());
                List<FolderEntryDto> dto = new ArrayList<>();
                int done = 0;
                for (FolderScanner.Entry entry : entries) {
                    if (entry.size() == 0) {
                        continue; // bỏ qua file rỗng
                    }
                    final int n = ++done;
                    Platform.runLater(() -> statusLabel.setText(
                            "Đang chia sẻ " + n + "/" + entries.size() + ": " + entry.relativePath()));
                    FileMetadata metadata = metadataBuilder.build(entry.absolutePath());
                    ShareFileResponse r = shareFileClientService.publish(sessionId, metadata);
                    if (fileRegistry != null) {
                        fileRegistry.registerCompleteFile(r.fileId(), entry.absolutePath(), metadata);
                    }
                    if (store != null) {
                        store.add(new SharedFilesStore.Entry(entry.absolutePath().toAbsolutePath().toString(),
                                metadata.fileName(), metadata.fileSize(), metadata.fileHash()));
                    }
                    dto.add(new FolderEntryDto(r.fileId(), entry.relativePath()));
                    Platform.runLater(() -> {
                        sharedFiles.removeIf(x -> x.fileId() == r.fileId());
                        sharedFiles.add(new SharedFileEntry(r.fileId(), entry.relativePath()));
                    });
                }
                if (dto.isEmpty()) {
                    throw new IOException("Thư mục không có file hợp lệ để chia sẻ.");
                }
                var res = folderClientService.share(sessionId, dir.getName(), dto);
                Platform.runLater(() -> {
                    statusLabel.setText("Đã chia sẻ thư mục \"" + dir.getName() + "\" (" + res.fileCount() + " file).");
                    chooseFolderButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Lỗi chia sẻ thư mục: " + e.getMessage());
                    chooseFolderButton.setDisable(false);
                });
            }
        });
    }
}
