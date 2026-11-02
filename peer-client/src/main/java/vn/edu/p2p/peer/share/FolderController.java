package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.FolderDetailResponse;
import vn.edu.p2p.common.dto.FolderFileInfo;
import vn.edu.p2p.common.dto.FolderSearchItem;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.transfer.DownloadPreflight;
import vn.edu.p2p.peer.transfer.DownloadSessionResult;
import vn.edu.p2p.peer.transfer.FolderPathResolver;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;
import vn.edu.p2p.peer.transfer.MultiSourceDownloadEngine;
import vn.edu.p2p.peer.transfer.PeerDownloadClient;
import vn.edu.p2p.peer.transfer.PeerP2pSocketFactory;
import vn.edu.p2p.peer.transfer.PieceScheduler;
import vn.edu.p2p.peer.transfer.SafeFileName;
import vn.edu.p2p.peer.transfer.TransferHistoryClientService;
import vn.edu.p2p.peer.transfer.ConnectionPlanner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** D65, D73: tìm thư mục chia sẻ, tải cả thư mục hoặc chọn từng file, giữ cấu trúc thư mục. */
public final class FolderController {

    @FXML private TextField queryField;
    @FXML private Button searchButton;
    @FXML private ListView<FolderSearchItem> groupsListView;
    @FXML private ListView<FolderFileInfo> filesListView;
    @FXML private TextField outputDirField;
    @FXML private Button chooseDirButton;
    @FXML private Button downloadAllButton;
    @FXML private Button downloadSelectedButton;
    @FXML private Button cancelButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "peer-folder-io");
        t.setDaemon(true);
        return t;
    });

    private FolderClientService folderClient;
    private FileSourceClientService sourceClient;
    private TransferHistoryClientService historyClient;
    private LocalFileRegistry fileRegistry;
    private PeerP2pSocketFactory socketFactory;
    private String sessionId;

    private Path outputDir = Path.of(System.getProperty("user.home"), "Downloads");
    private String currentGroupName;
    private long currentGroupId;
    private List<String> currentDirectories = List.of();
    private volatile AtomicBoolean cancelSignal = new AtomicBoolean(false);
    private volatile ConnectionPlanner planner;

    @FXML
    private void initialize() {
        statusLabel.setText("");
        outputDirField.setText(outputDir.toString());
        filesListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        groupsListView.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(FolderSearchItem i, boolean empty) {
                super.updateItem(i, empty);
                setText(empty || i == null ? null : i.groupName() + "  (" + i.fileCount() + " file, "
                        + i.totalSize() + " bytes, " + i.sourceCount() + " nguồn đủ)");
            }
        });
        filesListView.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(FolderFileInfo i, boolean empty) {
                super.updateItem(i, empty);
                setText(empty || i == null ? null : i.relativePath() + "  (" + i.fileSize() + " bytes)");
            }
        });
        groupsListView.getSelectionModel().selectedItemProperty()
                .addListener((o, old, sel) -> loadDetail(sel));
        downloadSelectedButton.disableProperty()
                .bind(filesListView.getSelectionModel().selectedItemProperty().isNull());
    }

    public void configure(TrackerConnection connection, String sessionId,
                          LocalFileRegistry registry, PeerP2pSocketFactory socketFactory) {
        this.folderClient = new FolderClientService(connection);
        this.sourceClient = new FileSourceClientService(connection);
        this.historyClient = new TransferHistoryClientService(connection);
        this.sessionId = sessionId;
        this.fileRegistry = registry;
        this.socketFactory = socketFactory;
    }

    public void setConnectionPlanner(ConnectionPlanner planner) {
        this.planner = planner;
    }

    /** Explore selection opens the detail/download view without typing Group ID. */
    public void prefillFromExplore(FolderSearchItem item) {
        if (item == null) return;
        queryField.setText(item.groupName());
        groupsListView.getItems().setAll(item);
        filesListView.getItems().clear();
        currentGroupName = null;
        currentGroupId = 0;
        currentDirectories = List.of();
        groupsListView.getSelectionModel().select(item);
        statusLabel.setText("Đang tải chi tiết thư mục " + item.groupName() + "...");
    }

    @FXML
    private void onSearch() {
        String q = queryField.getText();
        if (q == null || q.isBlank() || folderClient == null) {
            statusLabel.setText("Nhập tên thư mục cần tìm.");
            return;
        }
        searchButton.setDisable(true);
        executor.submit(() -> {
            try {
                var res = folderClient.search(sessionId, q.trim());
                Platform.runLater(() -> {
                    groupsListView.getItems().setAll(res.groups());
                    filesListView.getItems().clear();
                    currentGroupId = 0;
                    currentGroupName = null;
                    currentDirectories = List.of();
                    downloadAllButton.setDisable(true);
                    statusLabel.setText(res.groups().isEmpty() ? "Không tìm thấy thư mục nào."
                            : "Tìm thấy " + res.groups().size() + " thư mục.");
                    searchButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Lỗi tìm thư mục: " + e.getMessage());
                    searchButton.setDisable(false);
                });
            }
        });
    }

    private void loadDetail(FolderSearchItem item) {
        if (item == null) {
            return;
        }
        executor.submit(() -> {
            try {
                FolderDetailResponse d = folderClient.detail(sessionId, item.groupId());
                Platform.runLater(() -> {
                    currentGroupName = d.groupName();
                    currentGroupId = d.groupId();
                    filesListView.getItems().setAll(d.files());
                    currentDirectories = d.directories();
                    downloadAllButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Lỗi lấy danh sách file: " + e.getMessage()));
            }
        });
    }

    @FXML
    private void onChooseDirectory() {
        File chosen = new DirectoryChooser().showDialog(chooseDirButton.getScene().getWindow());
        if (chosen != null) {
            outputDir = chosen.toPath();
            outputDirField.setText(outputDir.toString());
        }
    }

    @FXML
    private void onDownloadAll() {
        startDownload(new ArrayList<>(filesListView.getItems()), true);
    }

    @FXML
    private void onDownloadSelected() {
        startDownload(new ArrayList<>(filesListView.getSelectionModel().getSelectedItems()), false);
    }

    @FXML
    private void onCancel() {
        cancelSignal.set(true);
        statusLabel.setText("Đang hủy...");
    }

    private void startDownload(List<FolderFileInfo> files, boolean fullFolder) {
        if ((files.isEmpty() && !fullFolder) || currentGroupName == null
                || currentGroupId <= 0 || sessionId == null) {
            return;
        }
        String group = SafeFileName.validate(currentGroupName) == null
                ? currentGroupName : SafeFileName.sanitize(currentGroupName);
        Path root = outputDir.resolve(group);
        final long targetGroupId = currentGroupId;
        final UUID groupTransferId = UUID.randomUUID();
        final List<String> selectedDirectories = fullFolder ? List.copyOf(currentDirectories) : List.of();
        cancelSignal = new AtomicBoolean(false);
        AtomicBoolean cancel = cancelSignal;
        setBusy(true);

        executor.submit(() -> {
            int ok = 0;
            int failed = 0;
            StringBuilder errors = new StringBuilder();
            List<String> completedEvidence = new ArrayList<>();
            try {
                Files.createDirectories(root);
                for (String dir : selectedDirectories) {
                    Files.createDirectories(FolderPathResolver.resolve(root, dir));
                }
            } catch (Exception directoryFailure) {
                Platform.runLater(() -> {
                    statusLabel.setText("Cannot create folder structure: " + directoryFailure.getMessage());
                    setBusy(false);
                });
                return;
            }
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    fileRegistry, new PeerDownloadClient(socketFactory).withPlanner(planner),
                    MultiSourceDownloadEngine.DEFAULT_WORKER_THREADS, PieceScheduler.Strategy.RANKED);

            for (int i = 0; i < files.size() && !cancel.get(); i++) {
                FolderFileInfo f = files.get(i);
                final int index = i;
                Platform.runLater(() -> {
                    progressBar.setProgress((double) index / files.size());
                    statusLabel.setText("Đang tải " + (index + 1) + "/" + files.size() + ": " + f.relativePath());
                });
                try {
                    Path target = FolderPathResolver.resolve(root, f.relativePath());
                    Files.createDirectories(target.getParent());

                    if (Files.isRegularFile(target) && Files.size(target) == f.fileSize()
                            && FileHashUtil.sha256(target).equalsIgnoreCase(f.fileHash())) {
                        // R67: even a resume hit needs a new, hash-verified COMPLETED history row.
                        UUID cachedTransfer = UUID.randomUUID();
                        java.time.Instant verifiedAt = java.time.Instant.now();
                        try {
                            historyClient.reportTransferStart(sessionId, cachedTransfer, f.fileId());
                            historyClient.reportTransferResult(sessionId,
                                    new DownloadSessionResult(cachedTransfer, f.fileId(), target,
                                            "COMPLETED", f.fileSize(), verifiedAt, verifiedAt,
                                            null, List.of()));
                            completedEvidence.add(cachedTransfer.toString());
                            ok++;
                        } catch (Exception historyError) {
                            failed++;
                            errors.append("\n- History: ").append(f.relativePath())
                                    .append(": ").append(historyError.getMessage());
                        }
                        continue;
                    }
                    DownloadPreflight.check(target, f.fileSize());

                    var sourceResponse = sourceClient.findSources(sessionId, f.fileId());
                    if (sourceResponse.fileHash() == null
                            || !sourceResponse.fileHash().equalsIgnoreCase(f.fileHash())) {
                        throw new IllegalStateException("Folder child hash differs from Tracker catalog");
                    }
                    List<FileSourceInfo> sources = sourceResponse.sources();
                    if (planner != null) {
                        planner.remember(sources);
                    }
                    UUID transferId = UUID.randomUUID();
                    try {
                        historyClient.reportTransferStart(sessionId, transferId, f.fileId());
                    } catch (Exception ignored) {
                    }
                    DownloadSessionResult r = engine.downloadFile(
                            f.fileId(), sources, target, null, cancel, transferId, f.fileHash());
                    boolean historySaved = false;
                    try {
                        historyClient.reportTransferResult(sessionId, r);
                        historySaved = true;
                    } catch (Exception historyError) {
                        errors.append("\n- Tracker history not saved: ")
                                .append(f.relativePath()).append(": ").append(historyError.getMessage());
                    }
                    if ("COMPLETED".equals(r.status()) && historySaved) {
                        ok++;
                        completedEvidence.add(transferId.toString());
                    } else if ("COMPLETED".equals(r.status())) {
                        failed++;
                    } else if (!"CANCELLED".equals(r.status())) {
                        failed++;
                        errors.append("\n- ").append(f.relativePath()).append(": ").append(r.failureReason());
                    }
                } catch (Exception e) {
                    failed++;
                    errors.append("\n- ").append(f.relativePath()).append(": ").append(e.getMessage());
                }
            }

            if (fullFolder && !cancel.get() && failed == 0 && ok == files.size()) {
                try {
                    folderClient.reportCompletedDownload(sessionId, groupTransferId,
                            targetGroupId, completedEvidence);
                } catch (Exception e) {
                    errors.append("\n- Không thể ghi lượt tải thư mục: ").append(e.getMessage());
                }
            }
            final int okCount = ok;
            final int failCount = failed;
            final String detail = errors.toString();
            Platform.runLater(() -> {
                progressBar.setProgress(cancel.get() ? progressBar.getProgress() : 1.0);
                statusLabel.setText((cancel.get() ? "Đã hủy. " : "Xong. ") + okCount + " file thành công, "
                        + failCount + " lỗi. Lưu tại: " + root + detail);
                setBusy(false);
            });
        });
    }

    private void setBusy(boolean busy) {
        downloadAllButton.setDisable(busy || currentGroupId <= 0);
        searchButton.setDisable(busy);
        cancelButton.setDisable(!busy);
    }

    public void shutdown() {
        cancelSignal.set(true);
        executor.shutdownNow();
    }
}