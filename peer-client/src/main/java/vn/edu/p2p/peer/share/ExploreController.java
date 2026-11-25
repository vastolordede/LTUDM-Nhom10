package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.animation.Timeline;
import javafx.animation.PauseTransition;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import javafx.scene.layout.HBox;
import vn.edu.p2p.peer.view.PageNumbers;
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
    private static final int PAGE_SIZE = 100;
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
    @FXML private Button nextPageButton;
    @FXML private HBox pageNumbers;
    @FXML private Button previousPageButton;
    @FXML private Label pageLabel;
    @FXML private Button downloadSelectedButton;
    @FXML private Label detailsLabel;
    @FXML private Label statusLabel;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "peer-explore-io");
        thread.setDaemon(true);
        return thread;
    });
    private int currentPage, knownPages = 1;
    private final LatestDiscoveryRequest requests = new LatestDiscoveryRequest();
    private int renderedPage = -1, renderedPages = -1;
    private boolean busy, visible, closed;
    private SearchClientService files;
    private FolderClientService folders;
    private Consumer<SearchResultItem> onFile;
    private Consumer<FolderSearchItem> onFolder;
    private String sessionId;
    private PauseTransition debounce;
    private boolean applyingRows, staleQuery;
    private int retryPage;
    private LatestDiscoveryRequest.Request displayed;
    private boolean localRefreshPending;
    private Timeline refresh;

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
                .addListener((observable, oldValue, selected) -> { if (!applyingRows) updateSelection(selected); });
        typeFilter.valueProperty().addListener((observable, before, after) -> { if (sessionId != null) onSearch(); });
        resultsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        sortCombo.valueProperty().addListener((observable, before, after) -> {
            if (sessionId != null) onSearch();
        });
        downloadSelectedButton.setDisable(true);
        statusLabel.setText("100 mục mỗi trang");
        debounce = new PauseTransition(Duration.millis(400));
        debounce.setOnFinished(e -> enqueue(0, true));
        queryField.textProperty().addListener((o, a, b) -> {
            if (closed || sessionId == null) return;
            requests.invalidate(); staleQuery=true; retryPage=0;
            downloadSelectedButton.setDisable(true); // immediately invalidate any old response, even while typing
            setPagerAvailability();
            debounce.playFromStart();
        });
        setPagerAvailability();
        refresh = new Timeline(new KeyFrame(Duration.seconds(5), e -> {
            if (visible && !busy && !closed && sessionId != null && !typing()) enqueue(staleQuery ? retryPage : currentPage, staleQuery);
        }));
        refresh.setCycleCount(Timeline.INDEFINITE); refresh.play();
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
        debounce.stop();
        enqueue(0, true);
    }

    private boolean typing() { return debounce.getStatus() == javafx.animation.Animation.Status.RUNNING; }

    /** Called on the FX thread after a local share/unshare has completed. */
    public void catalogChanged() {
        if (visible && !typing() && !closed && sessionId != null) {
            // A newer user-selected query/page must not be replaced by an automatic refresh.
            if (requests.pending() != null || requests.hasCurrentExplicit()) localRefreshPending=true;
            else enqueue(staleQuery ? retryPage : currentPage, staleQuery);
        }
    }

    public void setActive(boolean active) {
        boolean entering = active && !visible;
        visible = active;
        if (entering && sessionId != null && !closed && !typing()) catalogChanged();
    }

    @FXML private void onNextPage() { if (currentPage+1 < knownPages) navigate(currentPage+1); }
    @FXML private void onPreviousPage() { if (currentPage>0) navigate(currentPage-1); }
    private void navigate(int target) {
        if (typing() || !displayedFilterMatches()) { onSearch(); return; }
        enqueue(target, true);
    }

    private void enqueue(int target, boolean explicit) {
        if (closed || sessionId == null) return;
        String text = queryField.getText() == null ? "" : queryField.getText().trim();
        requests.offer(text, typeFilter.getValue(), sortCombo.getValue(), Math.max(0,target), explicit);
        if (explicit) {
            staleQuery=true; retryPage=target;
            statusLabel.setText("Đang tìm kiếm…");
            downloadSelectedButton.setDisable(true);
            setPagerAvailability();
        }
        pump();
    }

    private void pump() {
        if (closed || busy || requests.pending() == null) return;
        final var request = requests.take();
        final String currentSession = sessionId;
        busy = true;
        executor.execute(() -> {
            try {
                MergedDiscoveryPager fresh = new MergedDiscoveryPager(
                    (page,size) -> "Thư mục".equals(request.type()) ? List.of() : files.search(currentSession,request.query(),page,size,request.sort()).results().stream().map(DiscoveryEntry::ofFile).toList(),
                    (page,size) -> "Tệp".equals(request.type()) ? List.of() : folders.search(currentSession,request.query(),page,size,request.sort()).groups().stream().map(DiscoveryEntry::ofFolder).toList(),
                    DiscoveryEntry.comparator(request.sort()));
                List<DiscoveryEntry> chosen = List.of(), last = List.of();
                int lastIndex=0, count=0;
                for (int i=0; i<=request.page()+2 && fresh.hasMore(); i++) {
                    // Avoid scanning more pages for a request already superseded by the UI.
                    if (!requests.isCurrent(request)) break;
                    var rows = fresh.nextPage(PAGE_SIZE);
                    if (rows.isEmpty()) break;
                    count++; last=rows; lastIndex=i;
                    if (i==request.page()) chosen=rows;
                }
                final int actual = Math.min(request.page(),lastIndex), available=Math.max(1,count);
                final List<DiscoveryEntry> rows = chosen.isEmpty() ? last : chosen;
                Platform.runLater(() -> finish(request, actual, available, rows, null));
            } catch (Exception e) {
                Platform.runLater(() -> finish(request, 0, 1, List.of(), e));
            }
        });
    }

    private void finish(LatestDiscoveryRequest.Request request, int actual, int available,
                        List<DiscoveryEntry> rows, Exception failure) {
        busy=false;
        requests.completed(request);
        if (closed) return;
        if (requests.isCurrent(request)) {
            if (failure != null) {
                setText(statusLabel,"Không cập nhật được danh mục. Hệ thống sẽ thử lại.");
                // Keep the last good data, but do not allow downloading a stale selection
                // when an explicit filter/page request has failed.
                if (request.explicit()) downloadSelectedButton.setDisable(true);
            } else {
                currentPage=actual; knownPages=available; staleQuery=false; displayed=request;
                applyRows(rows, request.explicit());
                setText(pageLabel,"Trang " + (currentPage+1) + " · " + rows.size() + " mục");
                setText(statusLabel,rows.isEmpty()?"Không tìm thấy kết quả.":"100 mục/trang · Tự cập nhật");
                updatePagerButtons();
            }
        }
        pump(); // latest filter/page request always runs after the old one, including failures
        if (!busy && localRefreshPending) {
            localRefreshPending=false;
            if (visible && !typing() && !staleQuery) enqueue(currentPage,false);
        }
    }

    private static void setText(Label label, String text) {
        if (!java.util.Objects.equals(label.getText(), text)) label.setText(text);
    }

    private static boolean sameKey(DiscoveryEntry a, DiscoveryEntry b) {
        return DiscoveryRows.sameKey(a,b);
    }

    private void applyRows(List<DiscoveryEntry> rows, boolean explicit) {
        var items = resultsTable.getItems();
        if (items.equals(rows) && !explicit) return;
        var selected = resultsTable.getSelectionModel().getSelectedItem();
        ScrollBar bar = resultsTable.lookupAll(".scroll-bar").stream()
                .filter(n -> n instanceof ScrollBar b && b.getOrientation()==Orientation.VERTICAL)
                .map(n -> (ScrollBar)n).findFirst().orElse(null);
        double scroll = bar == null ? 0 : bar.getValue();
        applyingRows=true;
        try {
            DiscoveryRows.reconcile(items, rows);
            if (explicit) {
                resultsTable.getSelectionModel().clearSelection();
                if (!items.isEmpty()) resultsTable.scrollTo(0);
            } else {
                int index=-1;
                if(selected!=null) for(int i=0;i<items.size();i++) if(sameKey(items.get(i),selected)){index=i;break;}
                if(index<0) resultsTable.getSelectionModel().clearSelection();
                else if(resultsTable.getSelectionModel().getSelectedIndex()!=index || !java.util.Objects.equals(resultsTable.getSelectionModel().getSelectedItem(),items.get(index))) resultsTable.getSelectionModel().select(index);
                if (bar!=null) bar.setValue(Math.max(bar.getMin(),Math.min(scroll,bar.getMax())));
            }
        } finally { applyingRows=false; }
        updateSelection(resultsTable.getSelectionModel().getSelectedItem());
    }

    private boolean displayedFilterMatches() {
        return displayed!=null && java.util.Objects.equals(displayed.query(),queryField.getText()==null?"":queryField.getText().trim())
                && java.util.Objects.equals(displayed.type(),typeFilter.getValue()) && displayed.sort()==sortCombo.getValue();
    }
    private void setPagerAvailability() {
        boolean mismatched=!displayedFilterMatches();
        previousPageButton.setDisable(mismatched || currentPage==0);
        nextPageButton.setDisable(mismatched || currentPage+1>=knownPages);
        pageNumbers.setDisable(mismatched);
    }
    private void updatePagerButtons() {
        setPagerAvailability();
        if(renderedPage!=currentPage || renderedPages!=knownPages) {
            PageNumbers.render(pageNumbers,currentPage,knownPages,false,this::navigate);
            renderedPage=currentPage; renderedPages=knownPages;
        }
    }

    private void updateSelection(DiscoveryEntry selected) {
        downloadSelectedButton.setDisable(staleQuery || typing() || selected == null || selected.sourceCount() == 0);
        if (selected == null) {
            detailsLabel.setText("Chọn một kết quả để xem chi tiết.");
        } else if (selected.isFolder()) {
            detailsLabel.setText("Thư mục: " + selected.name() + " · " + selected.folder().fileCount()
                    + " file · " + formatBytes(selected.size()) + " · " + selected.sourceCount() + " nguồn");
        } else {
            detailsLabel.setText("Tệp: " + selected.name() + " · " + formatBytes(selected.size())
                    + " · " + selected.sourceCount() + " nguồn");
        }
    }

    @FXML
    private void onDownloadSelected() {
        DiscoveryEntry item = resultsTable.getSelectionModel().getSelectedItem();
        if (typing() || requests.hasCurrentExplicit() || downloadSelectedButton.isDisabled() || item == null || item.sourceCount() == 0) return;
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

    public void shutdown() { closed=true; requests.invalidate(); if(refresh!=null)refresh.stop(); if(debounce!=null)debounce.stop(); executor.shutdownNow(); }
}
