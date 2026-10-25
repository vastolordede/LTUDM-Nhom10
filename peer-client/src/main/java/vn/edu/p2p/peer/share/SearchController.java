package vn.edu.p2p.peer.share;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.common.dto.SearchResponse;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.peer.network.TrackerConnection;

import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search screen (T19-T20): search by name (T16-T17), then show sources
 * for whichever result is selected (T18), reusing the Tracker's existing
 * FILE_SOURCES_REQUEST/RESPONSE via FileSourceClientService.
 */
public final class SearchController {

    @FXML
    private TextField queryField;

    @FXML
    private Button searchButton;

    @FXML
    private Button downloadSelectedButton;

    @FXML
    private ListView<SearchResultItem> resultsListView;

    @FXML
    private Label sourcesLabel;

    @FXML
    private Label statusLabel;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "peer-search-io");
        thread.setDaemon(true);
        return thread;
    });

    private SearchClientService searchClientService;
    private FileSourceClientService fileSourceClientService;
    private String sessionId;
    private Consumer<SearchResultItem> onDownloadSelected;

    @FXML
    private void initialize() {
        statusLabel.setText("");

        resultsListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(SearchResultItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null
                        ? null
                        : item.fileName() + "  (" + item.fileSize() + " bytes, "
                                + item.sourceCount() + " nguồn)");
            }
        });

        downloadSelectedButton.setDisable(true);
        resultsListView.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, selected) -> {
                    downloadSelectedButton.setDisable(selected == null);
                    onResultSelected(selected);
                });
    }

    public void configure(TrackerConnection connection, String sessionId) {
        configure(connection, sessionId, null);
    }

    public void configure(TrackerConnection connection, String sessionId, Consumer<SearchResultItem> action) {
        this.searchClientService = new SearchClientService(connection);
        this.fileSourceClientService = new FileSourceClientService(connection);
        this.sessionId = sessionId;
        this.onDownloadSelected = action;
    }

    @FXML
    private void onDownloadSelected() {
        SearchResultItem item = resultsListView.getSelectionModel().getSelectedItem();
        if (item == null) return;
        if (onDownloadSelected == null) {
            statusLabel.setText("Download screen is not connected.");
            return;
        }
        onDownloadSelected.accept(item);
    }

    /** T16-T17: search by name. */
    @FXML
    private void onSearch() {
        String query = queryField.getText();
        if (query == null || query.isBlank()) {
            statusLabel.setText("Nhập tên file cần tìm.");
            return;
        }
        if (searchClientService == null || sessionId == null) {
            statusLabel.setText("Chưa cấu hình phiên đăng nhập cho màn hình Search.");
            return;
        }

        searchButton.setDisable(true);
        statusLabel.setText("Đang tìm kiếm...");
        sourcesLabel.setText("");

        String trimmedQuery = query.trim();

        ioExecutor.submit(() -> {
            try {
                SearchResponse response = searchClientService.search(sessionId, trimmedQuery);
                Platform.runLater(() -> onSearchDone(response));
            } catch (Exception e) {
                Platform.runLater(() -> onError("Lỗi tìm kiếm: " + e.getMessage()));
            }
        });
    }

    private void onSearchDone(SearchResponse response) {
        resultsListView.getItems().setAll(response.results());
        resultsListView.getSelectionModel().clearSelection();
        statusLabel.setText(response.results().isEmpty()
                ? "Không tìm thấy file nào."
                : "Tìm thấy " + response.results().size() + " file.");
        searchButton.setDisable(false);
    }

    /** T18: fetch and show sources for the selected file. */
    private void onResultSelected(SearchResultItem selected) {
        if (selected == null || fileSourceClientService == null) {
            return;
        }

        sourcesLabel.setText("Đang tải danh sách nguồn...");

        ioExecutor.submit(() -> {
            try {
                FileSourcesResponse sources = fileSourceClientService.findSources(
                        sessionId, selected.fileId()
                );
                Platform.runLater(() -> onSourcesReady(sources));
            } catch (Exception e) {
                Platform.runLater(() -> sourcesLabel.setText("Lỗi lấy nguồn: " + e.getMessage()));
            }
        });
    }

    private void onSourcesReady(FileSourcesResponse sources) {
        List<FileSourceInfo> list = sources.sources();

        if (list.isEmpty()) {
            sourcesLabel.setText("Hiện không có peer nào online giữ file này.");
            return;
        }

        StringBuilder builder = new StringBuilder(list.size()).append(" nguồn đang online:\n");
        for (FileSourceInfo source : list) {
            builder.append("- ")
                    .append(source.ipAddress())
                    .append(':')
                    .append(source.listeningPort())
                    .append(" (")
                    .append(source.availabilityStatus())
                    .append(")\n");
        }
        sourcesLabel.setText(builder.toString());
    }

    private void onError(String message) {
        statusLabel.setText(message);
        searchButton.setDisable(false);
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
    }
}
