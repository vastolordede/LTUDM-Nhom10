package vn.edu.p2p.peer.transfer;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
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

    /** Selected from Explore/Search, never entered as a raw ID. */
    private long selectedFileId;
    private long selectedFileSize = -1L;
    private String selectedFileHash;

    @FXML
    private TextField fileNameField;

    @FXML
    private ComboBox<PieceScheduler.Strategy> strategyComboBox;

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
    private volatile PeerDownloadClient activeDownloadClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private long lastUploadedBytes = 0L;
    private long lastUploadCheckNano = System.nanoTime();

    @FXML
    private void initialize() {
        sourcesStatusListView.setItems(sourceLogs);
        strategyComboBox.setItems(FXCollections.observableArrayList(PieceScheduler.Strategy.values()));
        strategyComboBox.getSelectionModel().select(PieceScheduler.Strategy.RAREST_FIRST);

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
        if (item == null) {
            return;
        }
        selectedFileId = item.fileId();
        startDownloadButton.setDisable(false);
        selectedFileSize = item.fileSize();
        selectedFileHash = item.fileHash();
        fileNameField.setText(item.fileName());
        statusLabel.setText("Đã chọn: " + item.fileName() + " (" + item.fileSize() + " bytes). Nhấn Bắt đầu tải.");
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
        if (closed.get()) return;
        if (fileSourceClientService == null || sessionId == null) {
            statusLabel.setText("Chưa cấu hình phiên đăng nhập cho màn hình Download.");
            return;
        }

        if (selectedFileId <= 0 || selectedFileSize < 0) {
            statusLabel.setText("Hãy chọn file từ tab Khám phá trước khi tải.");
            return;
        }
        final long fileId = selectedFileId;
        final String trustedSearchHash = selectedFileHash;

        String targetFileName = fileNameField.getText() == null || fileNameField.getText().isBlank()
                ? ("downloaded-file-" + fileId + ".bin")
                : fileNameField.getText().trim();

        // Keep an untrusted UI name inside the chosen folder. No absolute paths or '..'.
        String nameError = SafeFileName.validate(targetFileName);
        if (nameError != null) { statusLabel.setText(nameError); return; }
        Path targetFilePath = selectedOutputDir.resolve(targetFileName).normalize();
        if (!targetFilePath.startsWith(selectedOutputDir.toAbsolutePath().normalize())) {
            statusLabel.setText("Duong dan dich nam ngoai thu muc da chon."); return;
        }
        if (!targetFilePath.getParent().equals(selectedOutputDir.toAbsolutePath().normalize())
                && !targetFilePath.getParent().toAbsolutePath().normalize()
                        .equals(selectedOutputDir.toAbsolutePath().normalize())) {
            statusLabel.setText("File phai nam trong thu muc da chon.");
            return;
        }
        if (Files.isDirectory(targetFilePath)) {
            statusLabel.setText("Duong dan dich la thu muc, khong phai file.");
            return;
        }
        if (!Files.exists(targetFilePath)) {
            try {
                DownloadPreflight.check(targetFilePath, selectedFileSize);
            } catch (DownloadPreflight.PreflightException ex) {
                statusLabel.setText(ex.getMessage()); return;
            }
        }
        if (Files.exists(targetFilePath)) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "File da ton tai. Tiep tuc se resume cac piece khop SHA-256; neu khong phai ban tai do dang hop le, file moi se duoc luu bang ten khac. Tiep tuc?",
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText("Xac nhan file da ton tai");
            if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        }
        PieceScheduler.Strategy strategy = strategyComboBox.getValue() != null
                ? strategyComboBox.getValue()
                : PieceScheduler.Strategy.ROUND_ROBIN;

        activeCancelSignal = new AtomicBoolean(false);
        long generation = AuthClientService.sessionGeneration();
        startDownloadButton.setDisable(true);
        cancelDownloadButton.setDisable(false);
        sourceLogs.clear();
        progressBar.setProgress(0.0);
        statusLabel.setText("Đang truy vấn danh sách Peer nguồn từ Tracker...");

        downloadExecutor.submit(() -> executeDownloadTask(fileId, targetFilePath, strategy,
                trustedSearchHash, generation));
    }

    @FXML
    private void onCancelDownload() {
        if (activeCancelSignal != null) {
            activeCancelSignal.set(true);
            PeerDownloadClient client = activeDownloadClient;
            if (client != null) client.cancelActiveRequests();
            statusLabel.setText("Đang tạm dừng phiên tải (có thể bấm Bắt đầu tải để Resume sau)...");
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
                    sourceLogs.add("Nguồn: " + src.ipAddress() + ":" + src.listeningPort()
                            + " [" + src.availabilityStatus() + "] (Peer=" + shortPeer(src.peerId()) + ")");
                }
            });

            if (sources.isEmpty()) {
                postToUi(generation, () -> {
                    statusLabel.setText("Không có Peer nào đang online giữ file này.");
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
                        () -> sourceLogs.add("Peer " + shortPeer(peerId) + " -> kết nối " + mode)));
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
                                "Tiến độ: %.1f%% (%d / %d pieces)",
                                percent, completedPieces, totalPieces
                        ));
                        downloadSpeedLabel.setText("Tốc độ tải (DL): " + formatSpeed(downloadSpeedBytesPerSec));
                    });
                }

                @Override
                public void onSourceStatusChanged(String peerId, String status, String message) {
                    postToUi(generation, () -> sourceLogs.add(
                            "Peer " + shortPeer(peerId) + " -> " + status + ": " + message
                    ));
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
                final int n = attempt;
                Thread.sleep(2000);
                postToUi(generation,
                        () -> sourceLogs.add("Nguon bi roi, hoi lai Tracker (lan " + n + "/3)..."));
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
                    statusLabel.setText("Tải thành công & khớp SHA-256! File lưu tại: "
                            + finalTargetFilePath);
                } else {
                    statusLabel.setText("Phiên tải kết thúc (" + result.status() + "): "
                            + (result.failureReason() != null
                            ? safeDownloadFailure(new IOException(result.failureReason())) : "")
                            + " File dở dang (nếu có) được giữ tại: " + finalTargetFilePath);
                }
                downloadSpeedLabel.setText("Tốc độ tải (DL): 0.0 KB/s");
                resetButtons();
            });
        } catch (Exception e) {
            postToUi(generation, () -> {
                statusLabel.setText("Lỗi khi tải file: " + safeDownloadFailure(e));
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
                uploadSpeedLabel.setText("Tốc độ phát (UL): " + formatSpeed(speed));
            }
        });
    }

    private void resetButtons() {
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