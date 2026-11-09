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
import vn.edu.p2p.peer.auth.AuthClientService;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.transfer.DownloadPreflight;
import vn.edu.p2p.peer.transfer.DownloadSessionResult;
import vn.edu.p2p.peer.transfer.FolderDownloadPlan;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;
import vn.edu.p2p.peer.transfer.MultiSourceDownloadEngine;
import vn.edu.p2p.peer.transfer.PeerDownloadClient;
import vn.edu.p2p.peer.transfer.PeerP2pSocketFactory;
import vn.edu.p2p.peer.transfer.PieceScheduler;
import vn.edu.p2p.peer.transfer.SafeFileName;
import vn.edu.p2p.peer.transfer.TransferHistoryClientService;
import vn.edu.p2p.peer.transfer.ConnectionPlanner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    @FXML private Button unshareFolderButton;
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
    private PrivateKey requesterPrivateKey;

    private Path outputDir = Path.of(System.getProperty("user.home"), "Downloads");
    private String currentGroupName;
    private long currentGroupId;
    private boolean currentGroupComplete;
    private List<String> currentDirectories = List.of();
    private volatile AtomicBoolean cancelSignal = new AtomicBoolean(false);
    private volatile ConnectionPlanner planner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile PeerDownloadClient activeDownloadClient;

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
                          LocalFileRegistry registry, PeerP2pSocketFactory socketFactory,
                          PrivateKey requesterPrivateKey) {
        this.folderClient = new FolderClientService(connection);
        this.sourceClient = new FileSourceClientService(connection);
        this.historyClient = new TransferHistoryClientService(connection);
        this.sessionId = sessionId;
        this.fileRegistry = registry;
        this.socketFactory = socketFactory;
        this.requesterPrivateKey = requesterPrivateKey;
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
        currentGroupComplete = false;
        currentDirectories = List.of();
        groupsListView.getSelectionModel().select(item);
        statusLabel.setText("Đang tải chi tiết thư mục " + item.groupName() + "...");
    }

    @FXML
    private void onSearch() {
        if (closed.get()) return;
        String q = queryField.getText();
        if (q == null || q.isBlank() || folderClient == null) {
            statusLabel.setText("Nhập tên thư mục cần tìm.");
            return;
        }
        long generation = AuthClientService.sessionGeneration();
        searchButton.setDisable(true);
        executor.submit(() -> {
            try {
                var res = folderClient.search(sessionId, q.trim());
                postToUi(generation, () -> {
                    groupsListView.getItems().setAll(res.groups());
                    filesListView.getItems().clear();
                    currentGroupId = 0;
                    currentGroupComplete = false;
                    currentGroupName = null;
                    currentDirectories = List.of();
                    downloadAllButton.setDisable(true);
                    statusLabel.setText(res.groups().isEmpty() ? "Không tìm thấy thư mục nào."
                            : "Tìm thấy " + res.groups().size() + " thư mục.");
                    searchButton.setDisable(false);
                });
            } catch (Exception e) {
                postToUi(generation, () -> {
                    statusLabel.setText("Lỗi tìm thư mục: " + e.getMessage());
                    searchButton.setDisable(false);
                });
            }
        });
    }

    private void loadDetail(FolderSearchItem item) {
        if (item == null || closed.get()) {
            return;
        }
        long generation = AuthClientService.sessionGeneration();
        executor.submit(() -> {
            try {
                FolderDetailResponse d = folderClient.detail(sessionId, item.groupId());
                postToUi(generation, () -> {
                    currentGroupName = d.groupName();
                    currentGroupId = d.groupId();
                    filesListView.getItems().setAll(d.files());
                    currentDirectories = d.directories();
                    currentGroupComplete = d.complete();
                    downloadAllButton.setDisable(!d.complete());
                    statusLabel.setText(d.complete() ? "Thư mục đã có đủ file nguồn."
                            : "Thư mục chưa đủ nguồn: chỉ " + d.files().size() + "/"
                               + d.totalFileCount() + " file khả dụng. Không thể tải toàn bộ.");
                });
            } catch (Exception e) {
                postToUi(generation,
                        () -> statusLabel.setText("Lỗi lấy danh sách file: " + e.getMessage()));
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

    /** Owner-only on Tracker: another Peer cannot delete someone else's shared group. */
    @FXML
    private void onUnshareFolder() {
        if (closed.get() || currentGroupId <= 0 || folderClient == null) return;
        long id = currentGroupId;
        long generation = AuthClientService.sessionGeneration();
        unshareFolderButton.setDisable(true);
        executor.submit(() -> {
            try {
                var result = folderClient.unshareFolder(sessionId, id);
                postToUi(generation, () -> {
                    if (result.stopped()) {
                        groupsListView.getItems().removeIf(g -> g.groupId() == id);
                        filesListView.getItems().clear();
                        currentGroupId = 0;
                        currentGroupComplete = false;
                        currentDirectories = List.of();
                        statusLabel.setText("Đã ngừng chia sẻ thư mục. File chia sẻ riêng vẫn được giữ.");
                    } else statusLabel.setText("Thư mục đã ngừng chia sẻ trước đó.");
                    unshareFolderButton.setDisable(false);
                });
            } catch (Exception ex) {
                postToUi(generation, () -> {
                    statusLabel.setText("Không thể ngừng chia sẻ thư mục: " + ex.getMessage());
                    unshareFolderButton.setDisable(false);
                });
            }
        });
    }

    @FXML
    private void onDownloadAll() {
        if (!currentGroupComplete) {
            statusLabel.setText("Không thể tải toàn bộ thư mục vì thiếu file nguồn.");
            return;
        }
        startDownload(new ArrayList<>(filesListView.getItems()), true);
    }

    @FXML
    private void onDownloadSelected() {
        startDownload(new ArrayList<>(filesListView.getSelectionModel().getSelectedItems()), false);
    }

    @FXML
    private void onCancel() {
        cancelSignal.set(true);
        PeerDownloadClient client = activeDownloadClient;
        if (client != null) client.cancelActiveRequests();
        statusLabel.setText("Đang hủy...");
    }

    private void startDownload(List<FolderFileInfo> files, boolean fullFolder) {
        if (closed.get() || (files.isEmpty() && !fullFolder) || currentGroupName == null
                || currentGroupId <= 0 || sessionId == null) {
            return;
        }
        long generation = AuthClientService.sessionGeneration();
        String group = SafeFileName.validate(currentGroupName) == null
                ? currentGroupName : SafeFileName.sanitize(currentGroupName);
        Path root = outputDir.resolve(group);
        List<FolderFileInfo> filesToDownload = List.copyOf(files);
        List<String> directoriesToCreate = fullFolder ? List.copyOf(currentDirectories) : List.of();
        final long targetGroupId = currentGroupId;
        final UUID groupTransferId = UUID.randomUUID();
        cancelSignal = new AtomicBoolean(false);
        AtomicBoolean cancel = cancelSignal;
        setBusy(true);

        executor.submit(() -> {
            int ok = 0;
            int failed = 0;
            StringBuilder errors = new StringBuilder();
            List<String> completedEvidence = new ArrayList<>();
            List<String> renamedFiles = new ArrayList<>();
            final FolderDownloadPlan plan;
            try {
                plan = fullFolder
                        ? FolderDownloadPlan.full(root, filesToDownload, directoriesToCreate)
                        : FolderDownloadPlan.selected(root, filesToDownload);
            } catch (IOException invalidPath) {
                postToUi(generation, () -> {
                    statusLabel.setText("Invalid folder path: " + invalidPath.getMessage());
                    setBusy(false);
                });
                return;
            }
            try {
                Files.createDirectories(plan.root());
                for (Path dir : plan.directories()) Files.createDirectories(dir);
            } catch (Exception directoryFailure) {
                postToUi(generation, () -> {
                    statusLabel.setText("Cannot create folder structure: " + directoryFailure.getMessage());
                    setBusy(false);
                });
                return;
            }
            PeerDownloadClient peerDownloadClient =
                    new PeerDownloadClient(socketFactory, requesterPrivateKey).withPlanner(planner);
            activeDownloadClient = peerDownloadClient;
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    fileRegistry, peerDownloadClient,
                    MultiSourceDownloadEngine.DEFAULT_WORKER_THREADS, PieceScheduler.Strategy.RANKED,
                    (refreshFileId, source) -> sourceClient.refreshTicket(
                            sessionId, refreshFileId, source.peerId()));

            for (int i = 0; i < plan.files().size() && !cancel.get(); i++) {
                FolderDownloadPlan.FileTarget planned = plan.files().get(i);
                FolderFileInfo f = planned.file();
                Path target = planned.target();
                final int index = i;
                postToUi(generation, () -> {
                    progressBar.setProgress((double) index / plan.files().size());
                    statusLabel.setText("Đang tải " + (index + 1) + "/" + plan.files().size() + ": " + f.relativePath());
                });
                try {
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
                    if (!Files.exists(target)) {
                        DownloadPreflight.check(target, f.fileSize());
                    }

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
                    if (!r.outputFile().equals(target)) {
                        renamedFiles.add(f.relativePath() + " -> "
                                + r.outputFile().getFileName());
                    }
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
                    if (!cancel.get()) {
                        failed++;
                        errors.append("\n- ").append(f.relativePath()).append(": ").append(e.getMessage());
                    }
                }
            }
            activeDownloadClient = null;

            if (fullFolder && !cancel.get() && failed == 0 && ok == filesToDownload.size()) {
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
            final String renameNotice = renamedFiles.isEmpty() ? ""
                    : "\nĐã đổi tên file trùng: " + String.join(", ", renamedFiles);
            postToUi(generation, () -> {
                progressBar.setProgress(cancel.get() ? progressBar.getProgress() : 1.0);
                statusLabel.setText((cancel.get() ? "Đã hủy. " : "Xong. ") + okCount + " file thành công, "
                        + failCount + " lỗi. Lưu tại: " + root + detail + renameNotice);
                setBusy(false);
            });
        });
    }

    private void setBusy(boolean busy) {
        downloadAllButton.setDisable(busy || currentGroupId <= 0 || !currentGroupComplete);
        searchButton.setDisable(busy);
        cancelButton.setDisable(!busy);
        unshareFolderButton.setDisable(busy || currentGroupId <= 0);
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        cancelSignal.set(true);
        PeerDownloadClient client = activeDownloadClient;
        if (client != null) client.cancelActiveRequests();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(150, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    boolean workerTerminated() {
        return executor.isTerminated();
    }

    private void postToUi(long generation, Runnable action) {
        if (closed.get() || !AuthClientService.isCurrentGeneration(generation)) return;
        Platform.runLater(() -> {
            if (!closed.get() && AuthClientService.isCurrentGeneration(generation)) {
                action.run();
            }
        });
    }
}