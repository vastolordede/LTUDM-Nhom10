package vn.edu.p2p.peer.transfer;

import javafx.application.Platform;
import vn.edu.p2p.peer.view.UserFacingStatus;
import static vn.edu.p2p.peer.view.UserFacingStatus.Tone.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.network.TrackerConnectionDiagnostics;
import vn.edu.p2p.peer.auth.AuthClientService;
import vn.edu.p2p.peer.share.FileSourceClientService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S15, S16, S39, S40:
 * JavaFX Controller cho màn hình Download đa nguồn (hiển thị Progress %, số piece hoàn thành,
 * tốc độ Download thực tế và tốc độ Upload thực tế của Peer).
 */
public final class DownloadController {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DownloadController.class.getName());

    /** Selected from Explore/Search, never entered as a raw ID. */
    private long selectedFileId;
    private long selectedFileSize = -1L;
    private String selectedFileHash;

    @FXML
    private TextField fileNameField;

    @FXML
    private TextField outputDirField;

    @FXML
    private Button chooseDirButton;

    @FXML
    private Button startDownloadButton;

    @FXML
    private Button cancelDownloadButton;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Label progressPercentLabel;

    @FXML
    private Label downloadSpeedLabel;

    @FXML
    private Label uploadSpeedLabel;

    @FXML
    private ListView<String> sourcesStatusListView;

    @FXML
    private Label statusLabel;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "peer-download-ui-runner");
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledExecutorService uploadSpeedPoller =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "peer-upload-speed-poller");
                thread.setDaemon(true);
                return thread;
            });

    private final ObservableList<String> sourceLogs = FXCollections.observableArrayList();

    private FileSourceClientService fileSourceClientService;
    private TransferHistoryClientService transferHistoryClientService;
    private LocalFileRegistry fileRegistry;
    private PeerP2pSocketFactory p2pSocketFactory;
    private PeerUploadServer uploadServer;
    private String sessionId;
    private PrivateKey requesterPrivateKey;
    private ConnectionPlanner planner;

    private Path selectedOutputDir;
    private AtomicBoolean activeCancelSignal;
    private boolean downloadBusy;
    private volatile PeerDownloadClient activeDownloadClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long lastUploadedBytes = 0L;
    private long lastUploadCheckNano = System.nanoTime();

    @FXML
    private void initialize() {
        sourcesStatusListView.setItems(sourceLogs);

        selectedOutputDir = Path.of(System.getProperty("user.home"), "Downloads");
        fileNameField.setEditable(false);
        startDownloadButton.setDisable(true);
        outputDirField.setText(selectedOutputDir.toString());

        // S40: Định kỳ mỗi giây tính tốc độ Upload (KB/s hoặc MB/s) từ PeerUploadServer
        uploadSpeedPoller.scheduleAtFixedRate(this::refreshUploadSpeed, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Cấu hình kết nối Tracker, kho file nội bộ và Upload Server ngay sau khi Login.
     */
    public void configure(
            TrackerConnection connection,
            String sessionId,
            LocalFileRegistry fileRegistry,
            PeerP2pSocketFactory p2pSocketFactory,
            PeerUploadServer uploadServer,
            PrivateKey requesterPrivateKey
    ) {
        this.fileSourceClientService = new FileSourceClientService(connection);
        this.transferHistoryClientService = new TransferHistoryClientService(connection);
        this.sessionId = sessionId;
        this.fileRegistry = fileRegistry;
        this.p2pSocketFactory = p2pSocketFactory;
        this.uploadServer = uploadServer;
        this.requesterPrivateKey = requesterPrivateKey;
    }

    public void setConnectionPlanner(ConnectionPlanner planner) {
        this.planner = planner;
    }

    /**
     * Nhận thông tin file được chọn trực tiếp từ màn hình Search (S42 tích hợp Search -> Download).
     */
    public void prefillFromSearchResult(SearchResultItem item) {
        if (item == null || downloadBusy) {
            return;
        }
        progressBar.setProgress(0);
        progressPercentLabel.setText("Tiến độ: 0%");
        sourceLogs.clear();
        selectedFileId = item.fileId();
        startDownloadButton.setDisable(false);
        selectedFileSize = item.fileSize();
        selectedFileHash = item.fileHash();
        fileNameField.setText(item.fileName());
        UserFacingStatus.set(statusLabel, "Đã chọn " + item.fileName() + " (" + UserFacingStatus.bytes(item.fileSize()) + ").", NEUTRAL);
    }

    @FXML
    private void onChooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục lưu file tải về");
        File initial = selectedOutputDir != null ? selectedOutputDir.toFile() : null;
        if (initial != null && initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File chosen = chooser.showDialog(chooseDirButton.getScene().getWindow());
        if (chosen != null) {
            selectedOutputDir = chosen.toPath();
            outputDirField.setText(selectedOutputDir.toString());
        }
    }

    @FXML
    private void onStartDownload() {
        if (closed.get() || downloadBusy) return;
        if (fileSourceClientService == null || sessionId == null) {
            UserFacingStatus.set(statusLabel, "Vui lòng đăng nhập lại để tải tệp.", FAILURE);
            return;
        }

        if (selectedFileId <= 0 || selectedFileSize < 0) {
            UserFacingStatus.set(statusLabel, "Hãy chọn tệp từ Khám phá trước khi tải.", NEUTRAL);
            return;
        }
        final long fileId = selectedFileId;
        final String trustedSearchHash = selectedFileHash;

        String targetFileName = fileNameField.getText() == null || fileNameField.getText().isBlank()
                ? ("downloaded-file-" + fileId + ".bin")
                : fileNameField.getText().trim();

        // Keep an untrusted UI name inside the chosen folder. No absolute paths or '..'.
        String nameError = SafeFileName.validate(targetFileName);
        if (nameError != null) { UserFacingStatus.set(statusLabel, "Tên tệp không hợp lệ. Hãy chọn tệp khác.", FAILURE); return; }
        Path targetFilePath = selectedOutputDir.resolve(targetFileName).normalize();
        if (!targetFilePath.startsWith(selectedOutputDir.toAbsolutePath().normalize())) {
            UserFacingStatus.set(statusLabel, "Nơi lưu không hợp lệ. Hãy chọn lại thư mục.", FAILURE); return;
        }
        if (!targetFilePath.getParent().equals(selectedOutputDir.toAbsolutePath().normalize())
                && !targetFilePath.getParent().toAbsolutePath().normalize()
                        .equals(selectedOutputDir.toAbsolutePath().normalize())) {
            UserFacingStatus.set(statusLabel, "Nơi lưu không hợp lệ. Hãy chọn lại thư mục.", FAILURE);
            return;
        }
        if (Files.isDirectory(targetFilePath)) {
            UserFacingStatus.set(statusLabel, "Tên tệp trùng với thư mục có sẵn. Hãy chọn nơi lưu khác.", FAILURE);
            return;
        }
        if (!Files.exists(targetFilePath)) {
            try {
                DownloadPreflight.check(targetFilePath, selectedFileSize);
            } catch (DownloadPreflight.PreflightException ex) {
                LOG.warning("Download preflight failed: " + ex.getClass().getSimpleName()); UserFacingStatus.set(statusLabel, "Không thể lưu tệp. Kiểm tra dung lượng và quyền ghi thư mục.", FAILURE); return;
            }
        }
        if (Files.exists(targetFilePath)) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "File da ton tai. Tiep tuc se resume cac piece khop SHA-256; neu khong phai ban tai do dang hop le, file moi se duoc luu bang ten khac. Tiep tuc?",
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText("Xac nhan file da ton tai");
            if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        }
        PieceScheduler.Strategy strategy = PieceScheduler.Strategy.AUTO;

        downloadBusy = true;
        activeCancelSignal = new AtomicBoolean(false);
        long generation = AuthClientService.sessionGeneration();
        startDownloadButton.setDisable(true);
        cancelDownloadButton.setDisable(false);
        sourceLogs.clear();
        progressBar.setProgress(0.0);
        UserFacingStatus.set(statusLabel, "Đang tìm nguồn tải…", NEUTRAL);

        downloadExecutor.submit(() -> executeDownloadTask(fileId, targetFilePath, strategy,
                trustedSearchHash, generation));
    }

    @FXML
    private void onCancelDownload() {
        if (activeCancelSignal != null) {
            activeCancelSignal.set(true);
            PeerDownloadClient client = activeDownloadClient;
            if (client != null) client.cancelActiveRequests();
            UserFacingStatus.set(statusLabel, "Đang tạm dừng. Bạn có thể tải tiếp sau.", NEUTRAL);
        }
    }

    private void executeDownloadTask(
            long fileId,
            Path targetFilePath,
            PieceScheduler.Strategy strategy,
            String trustedSearchHash,
            long generation
    ) {
        try {
            FileSourcesResponse sourcesResponse = fileSourceClientService.findSources(sessionId, fileId);
            String catalogHash = sourcesResponse.fileHash();
            if (catalogHash == null || catalogHash.isBlank()) {
                throw new IllegalStateException("Tracker did not provide a trusted catalog file hash");
            }
            if (trustedSearchHash != null && !trustedSearchHash.equalsIgnoreCase(catalogHash)) {
                throw new IllegalStateException("Selected file hash differs from Tracker catalog; retry Search");
            }
            List<FileSourceInfo> sources = sourcesResponse.sources();

            postToUi(generation, () -> {
                for (FileSourceInfo src : sources) {
                    sourceLogs.add("Thiết bị " + shortPeer(src.peerId()) + " · " + ("COMPLETE".equals(src.availabilityStatus()) ? "Có đủ tệp" : "Có một phần tệp"));
                }
            });

            if (sources.isEmpty()) {
                postToUi(generation, () -> {
                    UserFacingStatus.set(statusLabel, "Chưa có nguồn tải khả dụng. Hãy thử lại sau.", FAILURE);
                    resetButtons();
                });
                return;
            }

            // S35: Báo lên Tracker bắt đầu phiên tải (DOWNLOADING)
            UUID initialTransferId = UUID.randomUUID();
            try {
                transferHistoryClientService.reportTransferStart(sessionId, initialTransferId, fileId);
            } catch (Exception ignored) {
                // Vẫn tiếp tục tải P2P kể cả khi ghi log bước đầu gặp lỗi mạng tạm thời
            }

            PeerDownloadClient downloadClient = new PeerDownloadClient(
                    p2pSocketFactory, requesterPrivateKey);
            activeDownloadClient = downloadClient;
            if (planner != null) {
                planner.remember(sources);
                planner.setListener((peerId, mode) -> postToUi(generation,
                        () -> sourceLogs.add("Thiết bị " + shortPeer(peerId) + " · " + UserFacingStatus.mode(mode))));
                downloadClient.withPlanner(planner);
            }
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    fileRegistry,
                    downloadClient,
                    MultiSourceDownloadEngine.DEFAULT_WORKER_THREADS,
                    strategy,
                    (refreshFileId, source) -> fileSourceClientService.refreshTicket(
                            sessionId, refreshFileId, source.peerId())
            );

            DownloadProgressListener uiListener = new DownloadProgressListener() {
                @Override
                public void onProgress(
                        long id,
                        int completedPieces,
                        int totalPieces,
                        long bytesReceived,
                        long totalBytes,
                        double downloadSpeedBytesPerSec
                ) {
                    double fraction = totalPieces == 0 ? 1.0 : (double) completedPieces / (double) totalPieces;
                    double percent = fraction * 100.0;
                    postToUi(generation, () -> {
                        progressBar.setProgress(fraction);
                        progressPercentLabel.setText(String.format(
                                "Tiến độ: %.1f%%", percent
                        ));
                        downloadSpeedLabel.setText("Tốc độ tải xuống: " + formatSpeed(downloadSpeedBytesPerSec));
                    });
                }

                @Override
                public void onSourceStatusChanged(String peerId, String status, String message) {
                    LOG.fine(() -> "Source status: " + status);
                    postToUi(generation, () -> sourceLogs.add("Thiết bị " + shortPeer(peerId) + " · " + UserFacingStatus.source(status)));
                }
            };

            DownloadSessionResult current = engine.downloadFile(
                    fileId, sources, targetFilePath, uiListener, activeCancelSignal,
                    initialTransferId, catalogHash);
            targetFilePath = current.outputFile();

            // D59: nguồn rớt giữa chừng -> hỏi lại Tracker lấy nguồn mới, tải tiếp phần còn thiếu (Resume)
            for (int attempt = 1; attempt <= 3
                    && "FAILED".equals(current.status())
                    && current.failureReason() != null
                    && current.failureReason().startsWith("Download incomplete")
                    && !current.failureReason().toLowerCase(java.util.Locale.ROOT)
                            .contains("ticket refresh failed")
                    && !activeCancelSignal.get(); attempt++) {
                Thread.sleep(2000);
                postToUi(generation,
                        () -> sourceLogs.add("Mất kết nối nguồn tải, đang tìm nguồn khác…"));
                List<FileSourceInfo> fresh = fileSourceClientService.findSources(sessionId, fileId).sources();
                if (fresh.isEmpty()) {
                    break;
                }
                if (planner != null) {
                    planner.remember(fresh);
                }
                current = engine.downloadFile(
                        fileId, fresh, targetFilePath, uiListener, activeCancelSignal,
                        initialTransferId, catalogHash);
                targetFilePath = current.outputFile();
            }
            boolean noSources = false;
            if ("FAILED".equals(current.status()) && !activeCancelSignal.get()) {
                try { noSources = fileSourceClientService.findSources(sessionId, fileId).sources().isEmpty(); }
                catch (Exception ignored) { /* keep original error if Tracker unreachable */ }
            }
            final boolean sourceUnavailable = noSources;
            final DownloadSessionResult result = current;
            final Path finalTargetFilePath = result.outputFile();

            // S36-S37: Lưu kết quả phiên tải (COMPLETED / FAILED / CANCELLED) và danh sách nguồn lên Tracker DB
            try {
                transferHistoryClientService.reportTransferResult(sessionId, result);
            } catch (Exception e) {
                postToUi(generation, () -> sourceLogs.add("Không lưu được lịch sử truyền tệp."));
            }

            postToUi(generation, () -> {
                if ("COMPLETED".equals(result.status())) {
                    progressBar.setProgress(1.0);
                    UserFacingStatus.set(statusLabel, "Tải thành công. Tệp đã được lưu tại: " + finalTargetFilePath, SUCCESS);
                } else if (sourceUnavailable) {
                    UserFacingStatus.set(statusLabel, "Không còn nguồn chia sẻ. Bạn có thể tải tiếp khi có nguồn mới.", FAILURE);
                } else {
                    boolean cancelled = "CANCELLED".equals(result.status());
                    LOG.fine(() -> "Download result: " + result.status());
                    UserFacingStatus.set(statusLabel, cancelled ? "Đã dừng tải. Bạn có thể tải tiếp sau." : "Tải thất bại. Hãy kiểm tra kết nối và thử lại.", cancelled ? NEUTRAL : FAILURE);
                }
                downloadSpeedLabel.setText("Tốc độ tải xuống: 0.0 KB/s");
                resetButtons();
            });
        } catch (Exception e) {
            postToUi(generation, () -> {
                LOG.warning("Download failed: " + TrackerConnectionDiagnostics.classify(e));
                UserFacingStatus.set(statusLabel, "Không thể tải tệp. Kiểm tra kết nối, nguồn tải và thử lại.", FAILURE);
                resetButtons();
            });
        }

        activeDownloadClient = null;
    }

    static String safeDownloadFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause.getClass().getSimpleName().equals("TicketRefreshException")
                    || message != null && (message.toLowerCase(java.util.Locale.ROOT)
                    .contains("ticket") || message.contains("ACCESS_DENIED"))) {
                return "Ticket truy cập không hợp lệ hoặc hết hạn. "
                        + "Hãy đăng nhập lại, xác nhận file còn được chia sẻ rồi thử lại.";
            }
        }

        TrackerConnectionDiagnostics.FailureKind kind =
                TrackerConnectionDiagnostics.classify(failure);
        if (kind == TrackerConnectionDiagnostics.FailureKind.TLS_HANDSHAKE_FAILED
                || kind == TrackerConnectionDiagnostics.FailureKind.CERTIFICATE_INVALID
                || kind == TrackerConnectionDiagnostics.FailureKind.CLIENT_TLS_SERVER_PLAINTEXT
                || kind == TrackerConnectionDiagnostics.FailureKind.CLIENT_PLAINTEXT_SERVER_TLS) {
            return TrackerConnectionDiagnostics.diagnostic(kind).message();
        }
        if (kind == TrackerConnectionDiagnostics.FailureKind.CONNECT_TIMEOUT
                || kind == TrackerConnectionDiagnostics.FailureKind.READ_TIMEOUT) {
            return "Peer nguồn hoặc Tracker không phản hồi đúng hạn. "
                    + "Kiểm tra kết nối mạng và trạng thái dịch vụ.";
        }
        if (kind == TrackerConnectionDiagnostics.FailureKind.CONNECTION_REFUSED
                || kind == TrackerConnectionDiagnostics.FailureKind.NO_ROUTE
                || kind == TrackerConnectionDiagnostics.FailureKind.HOST_UNRESOLVED) {
            return "Không kết nối được Peer nguồn hoặc Tracker. "
                    + "Kiểm tra trạng thái online, host/cổng và cấu hình mạng.";
        }
        return "Không thể hoàn tất tải. Kiểm tra Tracker, Peer nguồn và thử lại.";
    }

    /** S40: Tính toán tốc độ Upload hiện tại dựa trên số byte PeerUploadServer đã gửi. */
    private void refreshUploadSpeed() {
        if (uploadServer == null) {
            return;
        }
        long nowNano = System.nanoTime();
        long currentUploaded = uploadServer.totalBytesUploaded();
        long deltaBytes = Math.max(0L, currentUploaded - lastUploadedBytes);
        double elapsedSec = Math.max(0.001, (nowNano - lastUploadCheckNano) / 1_000_000_000.0);
        double speed = deltaBytes / elapsedSec;

        lastUploadedBytes = currentUploaded;
        lastUploadCheckNano = nowNano;

        long generation = AuthClientService.sessionGeneration();
        postToUi(generation, () -> {
            if (uploadSpeedLabel != null) {
                uploadSpeedLabel.setText("Tốc độ tải lên: " + formatSpeed(speed));
            }
        });
    }

    private void resetButtons() {
        downloadBusy = false;
        startDownloadButton.setDisable(false);
        cancelDownloadButton.setDisable(true);
    }

    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024.0 * 1024.0) {
            return String.format("%.2f MB/s", bytesPerSec / (1024.0 * 1024.0));
        }
        return String.format("%.1f KB/s", bytesPerSec / 1024.0);
    }

    private static String shortPeer(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        if (activeCancelSignal != null) {
            activeCancelSignal.set(true);
        }
        PeerDownloadClient client = activeDownloadClient;
        if (client != null) client.cancelActiveRequests();
        uploadSpeedPoller.shutdown();
        downloadExecutor.shutdown();
        if (!awaitTermination(uploadSpeedPoller, 100)) uploadSpeedPoller.shutdownNow();
        if (!awaitTermination(downloadExecutor, 100)) downloadExecutor.shutdownNow();
        awaitTermination(uploadSpeedPoller, 100);
        awaitTermination(downloadExecutor, 100);
    }

    boolean workersTerminated() {
        return downloadExecutor.isTerminated() && uploadSpeedPoller.isTerminated();
    }

    private void postToUi(long generation, Runnable action) {
        if (closed.get() || !AuthClientService.isCurrentGeneration(generation)) return;
        Platform.runLater(() -> {
            if (!closed.get() && AuthClientService.isCurrentGeneration(generation)) {
                action.run();
            }
        });
    }

    private static boolean awaitTermination(
            java.util.concurrent.ExecutorService executor, long timeoutMillis) {
        try {
            return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}