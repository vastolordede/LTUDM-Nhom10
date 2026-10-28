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
import vn.edu.p2p.peer.share.FileSourceClientService;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;
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

    @FXML
    private TextField fileIdField;

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

    private Path selectedOutputDir;
    private AtomicBoolean activeCancelSignal;
    private long lastUploadedBytes = 0L;
    private long lastUploadCheckNano = System.nanoTime();

    @FXML
    private void initialize() {
        sourcesStatusListView.setItems(sourceLogs);
        strategyComboBox.setItems(FXCollections.observableArrayList(PieceScheduler.Strategy.values()));
        strategyComboBox.getSelectionModel().select(PieceScheduler.Strategy.RAREST_FIRST);

        selectedOutputDir = Path.of(System.getProperty("user.home"), "Downloads");
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
            PeerUploadServer uploadServer
    ) {
        this.fileSourceClientService = new FileSourceClientService(connection);
        this.transferHistoryClientService = new TransferHistoryClientService(connection);
        this.sessionId = sessionId;
        this.fileRegistry = fileRegistry;
        this.p2pSocketFactory = p2pSocketFactory;
        this.uploadServer = uploadServer;
    }

    /**
     * Nhận thông tin file được chọn trực tiếp từ màn hình Search (S42 tích hợp Search -> Download).
     */
    public void prefillFromSearchResult(SearchResultItem item) {
        if (item == null) {
            return;
        }
        fileIdField.setText(Long.toString(item.fileId()));
        fileNameField.setText(item.fileName());
        statusLabel.setText("Đã chọn file \"" + item.fileName() + "\" (ID=" + item.fileId() + "). Nhấn Bắt đầu tải.");
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
        if (fileSourceClientService == null || sessionId == null) {
            statusLabel.setText("Chưa cấu hình phiên đăng nhập cho màn hình Download.");
            return;
        }

        final long fileId;
        try {
            fileId = Long.parseLong(fileIdField.getText().trim());
            if (fileId <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            statusLabel.setText("File ID phải là số nguyên dương hợp lệ.");
            return;
        }

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
        try {
            DownloadPreflight.check(targetFilePath, fileSize);
        } catch (DownloadPreflight.PreflightException ex) {
            statusLabel.setText(ex.getMessage()); return;
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
        if (Files.exists(targetFilePath)) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "File da ton tai. Tiep tuc se kiem tra piece de Resume, nhung piece sai hash co the bi ghi de. Tiep tuc?",
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText("Xac nhan file da ton tai");
            if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        }
        PieceScheduler.Strategy strategy = strategyComboBox.getValue() != null
                ? strategyComboBox.getValue()
                : PieceScheduler.Strategy.ROUND_ROBIN;

        activeCancelSignal = new AtomicBoolean(false);
        startDownloadButton.setDisable(true);
        cancelDownloadButton.setDisable(false);
        sourceLogs.clear();
        progressBar.setProgress(0.0);
        statusLabel.setText("Đang truy vấn danh sách Peer nguồn từ Tracker...");

        downloadExecutor.submit(() -> executeDownloadTask(fileId, targetFilePath, strategy));
    }

    @FXML
    private void onCancelDownload() {
        if (activeCancelSignal != null) {
            activeCancelSignal.set(true);
            statusLabel.setText("Đang tạm dừng phiên tải (có thể bấm Bắt đầu tải để Resume sau)...");
        }
    }

    private void executeDownloadTask(
            long fileId,
            Path targetFilePath,
            PieceScheduler.Strategy strategy
    ) {
        try {
            FileSourcesResponse sourcesResponse = fileSourceClientService.findSources(sessionId, fileId);
            List<FileSourceInfo> sources = sourcesResponse.sources();

            Platform.runLater(() -> {
                for (FileSourceInfo src : sources) {
                    sourceLogs.add("Nguồn: " + src.ipAddress() + ":" + src.listeningPort()
                            + " [" + src.availabilityStatus() + "] (Peer=" + shortPeer(src.peerId()) + ")");
                }
            });

            if (sources.isEmpty()) {
                Platform.runLater(() -> {
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

            PeerDownloadClient downloadClient = new PeerDownloadClient(p2pSocketFactory);
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    fileRegistry,
                    downloadClient,
                    MultiSourceDownloadEngine.DEFAULT_WORKER_THREADS,
                    strategy
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
                    Platform.runLater(() -> {
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
                    Platform.runLater(() -> sourceLogs.add(
                            "Peer " + shortPeer(peerId) + " -> " + status + ": " + message
                    ));
                }
            };

            DownloadSessionResult result = engine.downloadFile(
                    fileId,
                    sources,
                    targetFilePath,
                    uiListener,
                    activeCancelSignal,
                    initialTransferId
            );

            // S36-S37: Lưu kết quả phiên tải (COMPLETED / FAILED / CANCELLED) và danh sách nguồn lên Tracker DB
            try {
                transferHistoryClientService.reportTransferResult(sessionId, result);
            } catch (Exception e) {
                Platform.runLater(() -> sourceLogs.add("Lưu lịch sử cảnh báo: " + e.getMessage()));
            }

            Platform.runLater(() -> {
                if ("COMPLETED".equals(result.status())) {
                    progressBar.setProgress(1.0);
                    statusLabel.setText("Tải thành công & khớp SHA-256! File lưu tại: " + targetFilePath);
                } else {
                    statusLabel.setText("Phiên tải kết thúc (" + result.status() + "): "
                            + (result.failureReason() != null ? result.failureReason() : ""));
                }
                downloadSpeedLabel.setText("Tốc độ tải (DL): 0.0 KB/s");
                resetButtons();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                statusLabel.setText("Lỗi khi tải file: " + e.getMessage());
                resetButtons();
            });
        }
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

        Platform.runLater(() -> {
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
        if (activeCancelSignal != null) {
            activeCancelSignal.set(true);
        }
        uploadSpeedPoller.shutdownNow();
        downloadExecutor.shutdownNow();
    }
}