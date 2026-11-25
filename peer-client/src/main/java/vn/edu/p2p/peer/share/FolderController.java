package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import vn.edu.p2p.peer.view.UserFacingStatus;
import static vn.edu.p2p.peer.view.UserFacingStatus.Tone.*;
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
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(FolderController.class.getName());

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
    @FXML private ListView<String> sourceStatusListView;
    @FXML private Button previousFilesPage, nextFilesPage;
    @FXML private Label filesPageLabel;
    @FXML private javafx.scene.layout.HBox filesPageNumbers;
    private List<FolderFileInfo> allFiles = List.of();
    private int filesPage;
    private boolean busy;
    private boolean downloading;
    private int detailGeneration;
    private javafx.collections.ObservableList<SharedFileEntry> sharedFiles;

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
                setText(empty || i == null ? null : i.relativePath() + "  (" + UserFacingStatus.bytes(i.fileSize()) + ")");
            }
        });
        groupsListView.getSelectionModel().selectedItemProperty()
                .addListener((o, old, sel) -> loadDetail(sel));
        filesListView.getSelectionModel().selectedItemProperty().addListener((o,a,b)->updateButtons());
        renderFilesPage(); updateButtons();
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

    public void setSharedFiles(javafx.collections.ObservableList<SharedFileEntry> sharedFiles) { this.sharedFiles=sharedFiles; }

    public void setConnectionPlanner(ConnectionPlanner planner) {
        this.planner = planner;
    }

    /** Explore selection opens the detail/download view without typing Group ID. */
    public void prefillFromExplore(FolderSearchItem item) {
        if (item == null) return;
        if (busy) { statusLabel.setText("Đang tải thư mục. Hãy hoàn tất hoặc hủy trước khi chọn mục khác."); return; }
        detailGeneration++;
        queryField.setText(item.groupName());
        groupsListView.getSelectionModel().clearSelection();
        allFiles=List.of(); filesPage=0; renderFilesPage();
        currentGroupName=null; currentGroupId=0; currentGroupComplete=false; currentDirectories=List.of();
        progressBar.setProgress(0); sourceStatusListView.getItems().clear(); updateButtons();
        groupsListView.getItems().setAll(item);
        groupsListView.getSelectionModel().select(item);
        UserFacingStatus.set(statusLabel, "Đang mở thư mục " + item.groupName() + "…", NEUTRAL);
    }

    @FXML
    private void onSearch() {
        if (closed.get()) return;
        String q = queryField.getText();
        if (q == null || q.isBlank() || folderClient == null) {
            UserFacingStatus.set(statusLabel, "Nhập tên thư mục cần tìm.", NEUTRAL);
            return;
        }
        long generation = AuthClientService.sessionGeneration();
        searchButton.setDisable(true);
        executor.submit(() -> {
            try {
                var res = folderClient.search(sessionId, q.trim());
                postToUi(generation, () -> {
                    groupsListView.getItems().setAll(res.groups());
                    allFiles=List.of(); filesPage=0; renderFilesPage();
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
                    UserFacingStatus.set(statusLabel, "Không tìm được thư mục. Hãy thử lại.", FAILURE);
                    searchButton.setDisable(false);
                });
            }
        });
    }

    private void loadDetail(FolderSearchItem item) {
        int requestDetail = ++detailGeneration;
        if (item == null || closed.get() || busy) return;
        currentGroupId=0; currentGroupComplete=false; allFiles=List.of(); filesPage=0; renderFilesPage(); updateButtons();
        long generation = AuthClientService.sessionGeneration();
        executor.submit(() -> {
            try {
                FolderDetailResponse d = folderClient.detail(sessionId, item.groupId());
                postToUi(generation, () -> {
                    if (requestDetail != detailGeneration) return;
                    currentGroupName = d.groupName();
                    currentGroupId = d.groupId();
                    allFiles=List.copyOf(d.files()); filesPage=0; renderFilesPage();
                    currentDirectories = d.directories();
                    currentGroupComplete = d.complete();
                    updateButtons();
                    UserFacingStatus.set(statusLabel, d.complete() ? "Thư mục có thể tải xuống."
                            : "Chỉ có " + d.files().size() + "/" + d.totalFileCount() + " tệp khả dụng. Chưa thể tải toàn bộ.", NEUTRAL);
                });
            } catch (Exception e) {
                postToUi(generation,
                        () -> UserFacingStatus.set(statusLabel, "Không mở được thư mục. Hãy thử lại.", FAILURE));
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
        if (closed.get() || busy || currentGroupId <= 0 || folderClient == null) return;
        long id=currentGroupId; long generation=AuthClientService.sessionGeneration();
        busy=true; updateButtons();
        executor.submit(()->{
            try { synchronized(fileRegistry==null?this:fileRegistry.shareMutationLock()) {
                var result=folderClient.unshareFolder(sessionId,id);
                String note=ShareState.stopFolder(fileRegistry,SharedFilesStore.current(),result);
                postToUi(generation,()->{
                    ShareState.updateFolderRows(sharedFiles,result);
                    detailGeneration++;
                    groupsListView.getItems().removeIf(g->g.groupId()==id);
                    currentGroupId=0; currentGroupName=null; currentGroupComplete=false; currentDirectories=List.of();
                    allFiles=List.of(); filesPage=0; renderFilesPage();
                    progressBar.setProgress(0); sourceStatusListView.getItems().clear();
                    busy=false; updateButtons();
                    UserFacingStatus.set(statusLabel, "Đã ngừng chia sẻ thư mục. Tệp chia sẻ riêng hoặc thuộc nhóm khác vẫn được giữ; tệp cũ chưa rõ nguồn chia sẻ cần kiểm tra riêng."+note, NEUTRAL);
                });
            }} catch(Exception ex) {postToUi(generation,()->{busy=false; updateButtons();UserFacingStatus.set(statusLabel, "Không thể ngừng chia sẻ thư mục. Hãy kiểm tra kết nối và quyền chia sẻ.", NEUTRAL);});}
        });
    }

    @FXML
    private void onDownloadAll() {
        if (!currentGroupComplete) {
            UserFacingStatus.set(statusLabel, "Chưa có đủ nguồn tải cho thư mục.", FAILURE);
            return;
        }
        startDownload(new ArrayList<>(allFiles), true);
    }

    @FXML
    private void onDownloadSelected() {
        startDownload(new ArrayList<>(filesListView.getSelectionModel().getSelectedItems()), false);
    }

    @FXML
    private void onCancel() {
        cancelSignal.set(true);
        updateButtons();
        PeerDownloadClient client = activeDownloadClient;
        if (client != null) client.cancelActiveRequests();
        UserFacingStatus.set(statusLabel, "Đang dừng tải…", NEUTRAL);
    }

    private void startDownload(List<FolderFileInfo> files, boolean fullFolder) {
        if (closed.get() || busy || (files.isEmpty() && !fullFolder) || currentGroupName == null
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
        sourceStatusListView.getItems().clear(); progressBar.setProgress(0);
        UserFacingStatus.set(statusLabel, "Đang chuẩn bị tải…", NEUTRAL);
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
                    LOG.warning("Invalid folder path: " + invalidPath.getClass().getSimpleName());
                    UserFacingStatus.set(statusLabel, "Đường dẫn thư mục không hợp lệ.", FAILURE);
                    setBusy(false);
                });
                return;
            }
            try {
                Files.createDirectories(plan.root());
                for (Path dir : plan.directories()) Files.createDirectories(dir);
            } catch (Exception directoryFailure) {
                postToUi(generation, () -> {
                    LOG.warning("Cannot create folder: " + directoryFailure.getClass().getSimpleName());
                    UserFacingStatus.set(statusLabel, "Không tạo được thư mục lưu. Hãy kiểm tra quyền ghi.", FAILURE);
                    setBusy(false);
                });
                return;
            }
            PeerDownloadClient peerDownloadClient =
                    new PeerDownloadClient(socketFactory, requesterPrivateKey).withPlanner(planner);
            activeDownloadClient = peerDownloadClient;
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    fileRegistry, peerDownloadClient,
                    MultiSourceDownloadEngine.DEFAULT_WORKER_THREADS, PieceScheduler.Strategy.AUTO,
                    (refreshFileId, source) -> sourceClient.refreshTicket(
                            sessionId, refreshFileId, source.peerId()));

            for (int i = 0; i < plan.files().size() && !cancel.get(); i++) {
                FolderDownloadPlan.FileTarget planned = plan.files().get(i);
                FolderFileInfo f = planned.file();
                Path target = planned.target();
                final int index = i;
                postToUi(generation, () -> {
                    progressBar.setProgress((double) index / plan.files().size());
                    UserFacingStatus.set(statusLabel, "Đang tải " + (index + 1) + "/" + plan.files().size() + ": " + f.relativePath(), NEUTRAL);
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
                    postToUi(generation,()-> {
                        for(var src:sources) appendSource(f.relativePath()+" · Thiết bị "+shortPeer(src.peerId())+" · Nguồn tải");
                        if(sources.isEmpty()) appendSource(f.relativePath()+" · Không còn nguồn khả dụng");
                    });
                    var listener=new vn.edu.p2p.peer.transfer.DownloadProgressListener() {
                        @Override public void onProgress(long id,int pieces,int total,long bytes,long totalBytes,double speed) {
                            postToUi(generation,()->progressBar.setProgress((index+(total==0?1.0:(double)pieces/total))/Math.max(1,plan.files().size())));
                        }
                        @Override public void onSourceStatusChanged(String peerId,String state,String message) {
                            postToUi(generation,()->appendSource(f.relativePath()+" · Thiết bị "+shortPeer(peerId)+" · "+UserFacingStatus.source(state)));
                        }
                    };
                    DownloadSessionResult r = engine.downloadFile(f.fileId(), sources, target, listener, cancel, transferId, f.fileHash());
                    for(int attempt=0;attempt<3&&!cancel.get()&&"FAILED".equals(r.status())&&r.failureReason()!=null&&r.failureReason().startsWith("Download incomplete")&&!r.failureReason().contains("ticket refresh failed");attempt++) {
                        var fresh=sourceClient.findSources(sessionId,f.fileId());
                        if(!f.fileHash().equalsIgnoreCase(fresh.fileHash())||fresh.sources().isEmpty())break;
                        if(planner!=null)planner.remember(fresh.sources());
                        r=engine.downloadFile(f.fileId(),fresh.sources(),r.outputFile(),listener,cancel,transferId,f.fileHash());
                    }
                    var resultSources=r.sourceStats();
                    postToUi(generation,()->{
                        for(var src:resultSources) {
                            var mode=planner==null?null:planner.lastModeForPeer(src.sourcePeerId());
                            appendSource(f.relativePath()+" · Thiết bị "+shortPeer(src.sourcePeerId())+" · "+UserFacingStatus.bytes(src.bytesReceived())+(mode==null||src.bytesReceived()==0?"":" · "+UserFacingStatus.mode(mode)));
                        }
                    });
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
                if(!cancel.get() && failCount==0 && okCount==filesToDownload.size()) progressBar.setProgress(1.0);
                else progressBar.setProgress(filesToDownload.isEmpty()?0:(double)okCount/filesToDownload.size());
                if (!detail.isBlank()) LOG.warning("Folder download completed with errors or history warnings");
                boolean complete = !cancel.get() && failCount==0 && okCount==filesToDownload.size();
                String summary = cancel.get() ? "Đã dừng tải. " : complete ? "Tải thư mục thành công. " : "Chưa tải xong thư mục. ";
                UserFacingStatus.set(statusLabel, summary + okCount + " tệp hoàn thành, " + failCount + " tệp thất bại. Lưu tại: " + root
                        + (detail.isBlank() ? "" : " · Có lỗi tải hoặc ghi lịch sử; hãy kiểm tra và thử lại nếu cần.") + renameNotice,
                        cancel.get() ? NEUTRAL : complete && detail.isBlank() ? SUCCESS : FAILURE);
                setBusy(false);
            });
        });
    }

    private static String shortPeer(String id){return id==null?"?":id.substring(0,Math.min(8,id.length()));}
    private void appendSource(String text) { if(sourceStatusListView.getItems().size()>=1000)sourceStatusListView.getItems().removeFirst();sourceStatusListView.getItems().add(text); }
    @FXML private void onPreviousFilesPage(){if(!busy&&filesPage>0){filesPage--;renderFilesPage();}}
    @FXML private void onNextFilesPage(){if(!busy&&(filesPage+1)*100<allFiles.size()){filesPage++;renderFilesPage();}}
    private void renderFilesPage(){
        int from=filesPage*100;
        filesListView.getSelectionModel().clearSelection();
        filesListView.getItems().setAll(allFiles.subList(from,Math.min(from+100,allFiles.size())));
        filesPageLabel.setText("Trang "+(filesPage+1)+" / "+Math.max(1,(allFiles.size()+99)/100)+" · "+allFiles.size()+" tệp");
        updateButtons();
    }
    private void setBusy(boolean value) {busy=value;downloading=value;updateButtons();}
    private void updateButtons(){
        downloadAllButton.setDisable(busy || currentGroupId<=0 || !currentGroupComplete);
        downloadSelectedButton.setDisable(busy || currentGroupId<=0 || filesListView.getSelectionModel().getSelectedItem()==null);
        searchButton.setDisable(busy); groupsListView.setDisable(busy); chooseDirButton.setDisable(busy);
        cancelButton.setDisable(!downloading || cancelSignal.get());
        unshareFolderButton.setDisable(busy || currentGroupId<=0);
        previousFilesPage.setDisable(busy||filesPage==0);nextFilesPage.setDisable(busy||(filesPage+1)*100>=allFiles.size());
        vn.edu.p2p.peer.view.PageNumbers.render(filesPageNumbers,filesPage,Math.max(1,(allFiles.size()+99)/100),busy,index->{filesPage=index;renderFilesPage();});
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