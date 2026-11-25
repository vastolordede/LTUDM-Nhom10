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
import vn.edu.p2p.peer.auth.AuthClientService;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private Label selectedFileLabel;

    @FXML
    private ListView<SharedFileEntry> sharedFilesListView;

    @FXML
    private Label statusLabel;
    @FXML private Button previousSharePage, nextSharePage;
    @FXML private Label sharePageLabel;
    @FXML private javafx.scene.layout.HBox sharePageNumbers, historyPageNumbers;
    @FXML private javafx.scene.control.TreeView<String> shareHistoryView;
    @FXML private javafx.scene.layout.VBox shareHistoryFilterHost;
    @FXML private Button previousHistoryPage, nextHistoryPage;
    @FXML private Label historyPageLabel;
    private ShareHistoryPager historyPager;
   
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
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object executorLock = new Object();

    private ShareFileClientService shareFileClientService;
    private String sessionId;
    private LocalFileRegistry fileRegistry;

    private Path selectedFile;

    @FXML
    private void initialize() {
        statusLabel.setText("");
        new ShareListPager(sharedFilesListView, sharedFiles, previousSharePage, nextSharePage, sharePageLabel, sharePageNumbers);
        historyPager=new ShareHistoryPager(shareHistoryView,historyPageNumbers,historyPageLabel,previousHistoryPage,nextHistoryPage,shareHistoryFilterHost);
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
        loadShareHistory();
        restoreSharedFiles();
    }

    private void loadShareHistory() {
        var store=SharedFilesStore.current(); if(store==null)return;
        long generation=AuthClientService.sessionGeneration();
        submitIfOpen(()->{try{var rows=store.history().load();postToCurrentSession(generation,()->historyPager.update(rows));}
            catch(Exception e){postToCurrentSession(generation,()->statusLabel.setText("Không đọc được lịch sử chia sẻ cục bộ."));}});
    }
    private String recordHistory(SharedFilesStore store,long time,String title,List<ShareHistoryStore.Item> items,long generation) {
        if(store==null)return "";
        try { store.history().append(time,title,items);var batches=store.history().load();postToCurrentSession(generation,()->historyPager.update(batches));return ""; }
        catch(Exception e){return " Không lưu được lượt chia sẻ vào lịch sử cục bộ.";}
    }

    /** D70: publish lại các file đã Share ở lần chạy trước (còn tồn tại, đúng kích thước). */
    private void restoreSharedFiles() {
        SharedFilesStore store = SharedFilesStore.current();
        if (store == null || fileRegistry == null) {
            return;
        }
        ShareFileClientService service = shareFileClientService;
        String session = sessionId;
        long generation = AuthClientService.sessionGeneration();
        submitIfOpen(() -> {
            List<SharedFilesStore.Entry> entries;
            try {
                entries = store.loadValid();
                int skipped = store.load().size() - entries.size();
                if (skipped > 0) {
                    postToCurrentSession(generation, () -> statusLabel.setText(
                            skipped + " file cũ không thể khôi phục (đã xóa/đổi nội dung)."));
                }
            } catch (RuntimeException readError) {
                postToCurrentSession(generation, () -> statusLabel.setText(
                        "Không đọc được danh sách chia sẻ. Tệp gốc và backup vẫn được giữ."));
                return;
            }
            for (SharedFilesStore.Entry saved : entries) {
                if (!isCurrentSession(generation)) return;
                try { synchronized (fileRegistry.shareMutationLock()) {
                    Path path = Path.of(saved.path());
                    FileMetadata metadata = metadataBuilder.build(path);
                    ShareFileResponse r = service.publish(session, metadata, Boolean.FALSE.equals(saved.independent()), true);
                    if (!isCurrentSession(generation)) return;
                    fileRegistry.registerCompleteFile(r.fileId(), path, metadata);
                    if (!metadata.fileHash().equalsIgnoreCase(saved.fileHash())) {
                        store.removeByHash(saved.fileHash());
                        store.add(new SharedFilesStore.Entry(saved.path(), metadata.fileName(),
                                metadata.fileSize(), metadata.fileHash(), saved.independent(), saved.directAt(), saved.groups()));
                    }
                    var row = SharedFileEntry.describe(r.fileId(), metadata.fileName(), metadata.fileHash(), metadata.fileSize(), store.load());
                    postToCurrentSession(generation, () -> {
                        sharedFiles.removeIf(x -> x.fileId() == r.fileId());
                        sharedFiles.add(row);
                    });
                }} catch (Exception restoreError) {
                    postToCurrentSession(generation, () -> statusLabel.setText(
                            "Không thể khôi phục một file chia sẻ; kiểm tra file nguồn và Tracker."));
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
        selectedFileLabel.setText(file.getName() + "  (" + vn.edu.p2p.peer.view.UserFacingStatus.bytes(file.length()) + ")");
        statusLabel.setText("");
        onShareFile();
    }

    /** T03-T09: build FileMetadata, then T11: publish it to the Tracker. */
    @FXML
    private void onShareFile() {
        if (closed.get()) return;
        if (selectedFile == null) {
            return;
        }

        if (shareFileClientService == null || sessionId == null) {
            statusLabel.setText("Vui lòng đăng nhập lại để chia sẻ.");
            return;
        }

        setShareBusy(true);
        statusLabel.setText("Đang chuẩn bị tệp chia sẻ…");
        Path sharedPath = selectedFile;
        long generation = AuthClientService.sessionGeneration();

        submitIfOpen(() -> {
            try {
                FileMetadata metadata = metadataBuilder.build(sharedPath);
                synchronized (mutationLock()) {
                    if (!isCurrentSession(generation)) return;
                    ShareFileResponse response = shareFileClientService.publish(sessionId, metadata);
                    if (!isCurrentSession(generation)) return;
                    if (fileRegistry != null) fileRegistry.registerCompleteFile(response.fileId(), sharedPath, metadata);
                    SharedFilesStore store=SharedFilesStore.current();
                    var entry=SharedFilesStore.Entry.direct(sharedPath.toAbsolutePath().toString(),metadata.fileName(),metadata.fileSize(),metadata.fileHash(),System.currentTimeMillis());
                    List<SharedFilesStore.Entry> ledger=List.of(entry); String warning="";
                    try { if(store!=null){store.add(entry);ledger=store.load();} } catch(Exception ex){warning=" Không lưu được lịch sử chia sẻ cục bộ.";}
                    var row=SharedFileEntry.describe(response.fileId(),metadata.fileName(),metadata.fileHash(),metadata.fileSize(),ledger);
                    String message=warning+recordHistory(store,entry.directAt(),"Tệp riêng",List.of(new ShareHistoryStore.Item(metadata.fileName(),metadata.fileSize())),generation);
                    postToCurrentSession(generation, () -> onShareSuccess(row, message));
                }
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(ShareController.class.getName()).warning("Share failed: " + e.getClass().getSimpleName());
                postToCurrentSession(generation, () -> onError("Không thể chia sẻ tệp. Kiểm tra tệp và kết nối, sau đó thử lại."));
            }
        });
    }

    private void setShareBusy(boolean busy) { chooseFileButton.setDisable(busy); chooseFolderButton.setDisable(busy); }

    private Object mutationLock() { return fileRegistry == null ? this : fileRegistry.shareMutationLock(); }

    private void onShareSuccess(SharedFileEntry row, String warning) {
        statusLabel.setText("Đã chia sẻ tệp \"" + row.fileName() + "\"." + warning);
        sharedFiles.removeIf(entry -> entry.fileId() == row.fileId());
        sharedFiles.add(row);
        setShareBusy(false);
    }

    private void onError(String message) {
        statusLabel.setText(message);
        setShareBusy(false);
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

    private boolean isCurrentSession(long generation) {
        return !closed.get() && AuthClientService.isCurrentGeneration(generation);
    }

    private void submitIfOpen(Runnable task) {
        synchronized (executorLock) {
            if (!closed.get()) ioExecutor.submit(task);
        }
    }

    private void postToCurrentSession(long generation, Runnable action) {
        if (!isCurrentSession(generation)) return;
        Platform.runLater(() -> {
            if (isCurrentSession(generation)) action.run();
        });
    }

    boolean executorTerminated() { return ioExecutor.isTerminated(); }

    /** D64: chọn thư mục, Share từng file rồi đăng ký nhóm lên Tracker. */
    @FXML
    private void onShareFolder() {
        if (closed.get()) return;
        if (shareFileClientService == null || folderClientService == null || sessionId == null) {
            statusLabel.setText("Vui lòng đăng nhập lại để chia sẻ.");
            return;
        }
        File dir = new DirectoryChooser().showDialog(chooseFolderButton.getScene().getWindow());
        if (dir == null) {
            return;
        }
        setShareBusy(true);
        statusLabel.setText("Đang quét thư mục…");
        SharedFilesStore store = SharedFilesStore.current();
        long generation = AuthClientService.sessionGeneration();

        submitIfOpen(() -> {
            record Pending(long id, Path path, FileMetadata metadata, String relative) {}
            List<Pending> staged = new ArrayList<>();
            synchronized (mutationLock()) {
                boolean folderCommitted=false;
                try {
                    List<FolderScanner.Entry> entries = FolderScanner.scan(dir.toPath());
                    List<String> directories = FolderScanner.scanDirectories(dir.toPath());
                    List<FolderEntryDto> dto = new ArrayList<>();
                    int done=0;
                    for (FolderScanner.Entry entry:entries) {
                        if (!isCurrentSession(generation)) throw new IOException("Phiên đã kết thúc");
                        final int n=++done;
                        postToCurrentSession(generation,()->statusLabel.setText("Đang chia sẻ "+n+"/"+entries.size()+": "+entry.relativePath()));
                        FileMetadata metadata=metadataBuilder.build(entry.absolutePath());
                        ShareFileResponse res=shareFileClientService.publish(sessionId,metadata,true,false);
                        staged.add(new Pending(res.fileId(),entry.absolutePath(),metadata,entry.relativePath()));
                        dto.add(new FolderEntryDto(res.fileId(),entry.relativePath()));
                    }
                    if(!isCurrentSession(generation)) throw new IOException("Phiên đã kết thúc");
                    var res=folderClientService.share(sessionId,dir.getName(),dto,directories);
                    folderCommitted=true;
                    var group=new SharedFilesStore.Group(res.groupId(),dir.getName(),System.currentTimeMillis());
                    List<SharedFilesStore.Entry> newEntries=new ArrayList<>();
                    for(var x:staged) {
                        if(fileRegistry!=null)fileRegistry.registerCompleteFile(x.id(),x.path(),x.metadata());
                        newEntries.add(SharedFilesStore.Entry.folder(x.path().toAbsolutePath().toString(),x.metadata().fileName(),x.metadata().fileSize(),x.metadata().fileHash(),group));
                    }
                    List<SharedFilesStore.Entry> ledger=newEntries; String warning="";
                    try { if(store!=null){store.addAll(newEntries);ledger=store.load();} }catch(Exception ex){warning=" Không lưu được lịch sử chia sẻ cục bộ.";}
                    List<SharedFileEntry> rows=new ArrayList<>();
                    for(var x:staged) rows.add(SharedFileEntry.describe(x.id(),x.relative(),x.metadata().fileHash(),x.metadata().fileSize(),ledger));
                    List<ShareHistoryStore.Item> historyItems=new ArrayList<>(staged.stream().map(x->new ShareHistoryStore.Item(x.relative(),x.metadata().fileSize())).toList());
                    var emptyDirectories=ShareHistoryPaths.emptyLeafDirectories(directories,staged.stream().map(Pending::relative).toList());
                    for(String directory:emptyDirectories) historyItems.add(new ShareHistoryStore.Item(directory+"/",-1));
                    String note=warning+recordHistory(store,group.sharedAt(),"Thư mục "+dir.getName(),historyItems,generation);
                    postToCurrentSession(generation,()->{
                        var merged=new java.util.LinkedHashMap<Long,SharedFileEntry>();
                        for(var x:sharedFiles)merged.put(x.fileId(),x);
                        for(var x:rows)merged.put(x.fileId(),x);
                        sharedFiles.setAll(merged.values());
                        statusLabel.setText("Đã chia sẻ thư mục "+dir.getName()+" ("+res.fileCount()+" tệp)."+note);
                        setShareBusy(false);
                    });
                } catch(Exception e) {
                    // A failed batch must not revoke an independent share or a file used by another folder.
                    if(!folderCommitted) for(var x:staged) {
                        try { var stopped=shareFileClientService.unshare(sessionId,x.id(),true);
                            if(stopped.stopped()) ShareState.stopFile(fileRegistry,store,x.id());
                        } catch(Exception ignored) { /* Tracker may be offline; no local file is enabled for this batch. */ }
                    }
                    postToCurrentSession(generation,()->{
                        statusLabel.setText("Không hoàn tất chia sẻ thư mục. Kiểm tra kết nối và thử lại.");
                        setShareBusy(false);
                    });
                }
            }
        });
    }
}
