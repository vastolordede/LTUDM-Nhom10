package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.FolderSearchItem;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.peer.network.TrackerConnection;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** R64-R66: one Explore screen, no manual file/folder identifiers. */
public final class ExploreController {
    private static final int PAGE_SIZE = 50;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    @FXML private TextField queryField;
    @FXML private ComboBox<String> typeFilter;
    @FXML private ComboBox<DiscoverySort> sortCombo;
    @FXML private TableView<DiscoveryEntry> resultsTable;
    @FXML private TableColumn<DiscoveryEntry, String> typeColumn;
    @FXML private TableColumn<DiscoveryEntry, String> nameColumn;
    @FXML private TableColumn<DiscoveryEntry, String> sizeColumn;
    @FXML private TableColumn<DiscoveryEntry, String> dateColumn;
    @FXML private TableColumn<DiscoveryEntry, String> downloadsColumn;
    @FXML private TableColumn<DiscoveryEntry, String> sourceColumn;
    @FXML private Button searchButton;
    @FXML private Button loadMoreButton;
    @FXML private Button downloadSelectedButton;
    @FXML private Label detailsLabel;
    @FXML private Label statusLabel;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "peer-explore-io");
        thread.setDaemon(true);
        return thread;
    });
    private final List<DiscoveryEntry> loaded = new ArrayList<>();
    private SearchClientService files;
    private FolderClientService folders;
    private Consumer<SearchResultItem> onFile;
    private Consumer<FolderSearchItem> onFolder;
    private String sessionId;
    private String query = "";
    private int nextFilePage;
    private int nextFolderPage;
    private int generation;
    private boolean moreFiles = true;
    private boolean moreFolders = true;
    private boolean busy;

    @FXML
    private void initialize() {
        typeFilter.setItems(FXCollections.observableArrayList("Tất cả", "Tệp", "Thư mục"));
        typeFilter.getSelectionModel().selectFirst();
        sortCombo.setItems(FXCollections.observableArrayList(DiscoverySort.values()));
        sortCombo.setValue(DiscoverySort.POPULARITY);
        typeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().type()));
        nameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatBytes(cell.getValue().size())));
        dateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatDate(cell.getValue().createdAtEpochMillis())));
        downloadsColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                Long.toString(cell.getValue().completedDownloads())));
        sourceColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                Integer.toString(cell.getValue().sourceCount())));
        resultsTable.setPlaceholder(new Label("Chưa có kết quả. Hãy thử từ khóa khác."));
        resultsTable.setRowFactory(table -> {
            TableRow<DiscoveryEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) onDownloadSelected();
            });
            return row;
        });
        resultsTable.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, selected) -> updateSelection(selected));
        typeFilter.valueProperty().addListener((observable, before, after) -> applyFilter());
        sortCombo.valueProperty().addListener((observable, before, after) -> {
            if (sessionId != null) onSearch();
        });
        downloadSelectedButton.setDisable(true);
        statusLabel.setText("Khám phá danh mục file và thư mục trên Tracker.");
    }

    public void configure(TrackerConnection connection, String sessionId,
                          Consumer<SearchResultItem> onFile,
                          Consumer<FolderSearchItem> onFolder) {
        this.files = new SearchClientService(connection);
        this.folders = new FolderClientService(connection);
        this.sessionId = sessionId;
        this.onFile = onFile;
        this.onFolder = onFolder;
        onSearch();
    }

    @FXML
    private void onSearch() {
        if (sessionId == null) {
            statusLabel.setText("Bạn cần đăng nhập trước khi tìm kiếm.");
            return;
        }
        query = queryField.getText() == null ? "" : queryField.getText().trim();
        generation++;
        loaded.clear();
        resultsTable.getItems().clear();
        nextFilePage = 0;
        nextFolderPage = 0;
        moreFiles = true;
        moreFolders = true;
        detailsLabel.setText("Chọn một kết quả để xem chi tiết.");
        loadNextPage();
    }

    @FXML
    private void onLoadMore() { loadNextPage(); }

    private void loadNextPage() {
        if (busy || (!moreFiles && !moreFolders)) return;
        boolean getFiles = moreFiles;
        boolean getFolders = moreFolders;
        final int filePage = nextFilePage;
        final int folderPage = nextFolderPage;
        final int requestGeneration = generation;
        final DiscoverySort sort = sortCombo.getValue();
        final String searchQuery = query;
        busy = true;
        searchButton.setDisable(true);
        loadMoreButton.setDisable(true);
        statusLabel.setText("Đang tải danh mục từ Tracker...");
        executor.execute(() -> {
            try {
                List<SearchResultItem> fileResult = getFiles
                        ? files.search(sessionId, searchQuery, filePage, PAGE_SIZE, sort).results()
                        : List.of();
                List<FolderSearchItem> folderResult = getFolders
                        ? folders.search(sessionId, searchQuery, folderPage, PAGE_SIZE, sort).groups()
                        : List.of();
                Platform.runLater(() -> {
                    if (requestGeneration != generation) return;
                    if (getFiles) {
                        nextFilePage++;
                        moreFiles = fileResult.size() == PAGE_SIZE;
                    }
                    if (getFolders) {
                        nextFolderPage++;
                        moreFolders = folderResult.size() == PAGE_SIZE;
                    }
                    fileResult.forEach(result -> loaded.add(DiscoveryEntry.ofFile(result)));
                    folderResult.forEach(result -> loaded.add(DiscoveryEntry.ofFolder(result)));
                    busy = false;
                    searchButton.setDisable(false);
                    loadMoreButton.setDisable(!moreFiles && !moreFolders);
                    applyFilter();
                    statusLabel.setText(loaded.isEmpty() ? "Không tìm thấy kết quả." :
                            "Đã tải " + loaded.size() + " mục. "
                                + (moreFiles || moreFolders ? "Có thể tải thêm." : "Đã hết kết quả."));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (requestGeneration != generation) return;
                    busy = false;
                    searchButton.setDisable(false);
                    loadMoreButton.setDisable(false);
                    statusLabel.setText("Lỗi tải danh mục: " + e.getMessage());
                });
            }
        });
    }

    private void applyFilter() {
        String selectedType = typeFilter.getValue();
        List<DiscoveryEntry> visible = loaded.stream()
                .filter(item -> selectedType == null || "Tất cả".equals(selectedType)
                        || selectedType.equals(item.type()))
                .sorted(DiscoveryEntry.comparator(sortCombo.getValue()))
                .toList();
        resultsTable.getItems().setAll(visible);
        updateSelection(resultsTable.getSelectionModel().getSelectedItem());
    }

    private void updateSelection(DiscoveryEntry selected) {
        downloadSelectedButton.setDisable(selected == null);
        if (selected == null) {
            detailsLabel.setText("Chọn một kết quả để xem chi tiết.");
        } else if (selected.isFolder()) {
            detailsLabel.setText("Thư mục: " + selected.name() + " · " + selected.folder().fileCount()
                    + " file · " + formatBytes(selected.size()) + " · " + selected.sourceCount() + " nguồn");
        } else {
            detailsLabel.setText("Tệp: " + selected.name() + " · " + formatBytes(selected.size())
                    + " · SHA-256: " + selected.file().fileHash());
        }
    }

    @FXML
    private void onDownloadSelected() {
        DiscoveryEntry item = resultsTable.getSelectionModel().getSelectedItem();
        if (item == null) return;
        if (item.isFolder()) {
            if (onFolder != null) onFolder.accept(item.folder());
        } else if (onFile != null) onFile.accept(item.file());
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1L << 30) return String.format("%.2f GiB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format("%.2f MiB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return String.format("%.1f KiB", bytes / 1024.0);
        return bytes + " B";
    }

    private static String formatDate(long epochMillis) {
        return epochMillis > 0 ? DATE.format(Instant.ofEpochMilli(epochMillis)) : "Chưa có";
    }

    public void shutdown() { generation++; executor.shutdownNow(); }
}
