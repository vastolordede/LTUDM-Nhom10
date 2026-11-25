package vn.edu.p2p.peer.transfer;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.layout.HBox;
import vn.edu.p2p.peer.view.PageNumbers;
import javafx.scene.control.*;
import vn.edu.p2p.common.dto.TransferHistoryItemDto;
import vn.edu.p2p.peer.network.TrackerConnection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.time.ZonedDateTime;
import javafx.scene.layout.VBox;
import vn.edu.p2p.peer.view.HistoryFilterBar;
import java.util.concurrent.*;
import java.util.function.Function;

public final class TransferHistoryController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    @FXML private Button refreshButton, previousButton, nextButton;
    @FXML private TableView<TransferHistoryItemDto> historyTable;
    @FXML private Label statusLabel, pageLabel;
    @FXML private HBox pageNumbers;
    @FXML private VBox filterHost;
    private HistoryFilterBar filter;
    private List<TransferHistoryItemDto> all=List.of(), filtered=List.of();
    private boolean refreshQueued;
    private int knownPages=1;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"peer-history-io"); t.setDaemon(true); return t; });
    private TransferHistoryClientService historyClientService;
    private String sessionId;
    private int page;
    private boolean busy, closed;
    @FXML private void initialize() {
        historyTable.getColumns().setAll(List.of(
                column("Tên tệp", 260, TransferHistoryItemDto::fileName),
                column("Dung lượng", 100, i -> bytes(i.fileSize())),
                column("Đã tải", 85, i -> bytes(i.bytesReceived())),
                statusColumn(),
                column("Nguồn", 65, i -> Integer.toString(i.sourceCount())),
                column("Bắt đầu", 160, i -> time(i.startedAtEpochMillis())),
                column("Kết thúc", 160, i -> time(i.completedAtEpochMillis()))));
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        historyTable.setPlaceholder(new Label("Chưa có lịch sử tải xuống."));
        historyTable.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (item == null) return;
            statusLabel.setText(item.fileName() + " · " + status(item.status()) + ("FAILED".equals(item.status()) ? " · Kiểm tra nguồn tải và thử lại." : ""));
        });
        filter=new HistoryFilterBar(()->{page=0;applyFilter();});
        filterHost.getChildren().add(filter);
        buttons();
    }
    private static TableColumn<TransferHistoryItemDto,String> column(String title, double width, Function<TransferHistoryItemDto,String> text) {
        var c = new TableColumn<TransferHistoryItemDto,String>(title);
        c.setPrefWidth(width); c.setSortable(false);
        c.setCellValueFactory(v -> new ReadOnlyStringWrapper(text.apply(v.getValue())));
        return c;
    }
    private static TableColumn<TransferHistoryItemDto,String> statusColumn() {
        var c = column("Trạng thái", 125, i -> status(i.status()));
        c.setCellFactory(col -> new TableCell<TransferHistoryItemDto,String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("outcome-success", "outcome-failure");
                setText(empty ? null : item);
                if (!empty && "Hoàn thành".equals(item)) getStyleClass().add("outcome-success");
                else if (!empty && "Thất bại".equals(item)) getStyleClass().add("outcome-failure");
            }
        });
        return c;
    }
    static String status(String raw) {
        if (raw == null) return "Không rõ";
        return switch(raw) { case "COMPLETED" -> "Hoàn thành"; case "FAILED" -> "Thất bại"; case "CANCELLED" -> "Đã hủy"; case "DOWNLOADING" -> "Đang tải"; case "QUEUED" -> "Đang chờ"; default -> "Không rõ"; };
    }
    private static String time(Long ms) { return ms == null || ms <= 0 ? "—" : TIME.format(Instant.ofEpochMilli(ms)); }
    private static String bytes(long n) { if(n >= 1048576) return String.format("%.2f MiB",n/1048576.0); if(n>=1024) return String.format("%.1f KiB",n/1024.0); return n+" B"; }
    public void configure(TrackerConnection connection, String sessionId) { this.historyClientService = new TransferHistoryClientService(connection); this.sessionId=sessionId; }
    @FXML public void onRefreshHistory() {
        if(closed || historyClientService==null || sessionId==null) return;
        if(busy){refreshQueued=true;return;}
        busy=true; refreshButton.setDisable(true); statusLabel.setText("Đang tải lịch sử…");
        final String currentSession=sessionId;
        ioExecutor.submit(()->{
            try {
                // Existing v4 paged API; fetch the complete history once per refresh.
                // Filters and pagination then operate on the same local snapshot.
                var unique=new LinkedHashMap<String,TransferHistoryItemDto>();
                for(int i=0;;i++) {
                    if(Thread.currentThread().isInterrupted())return;
                    if(i>100_000)throw new java.io.IOException("History exceeds supported page range");
                    var rows=historyClientService.fetchHistory(currentSession,i);
                    int before=unique.size();
                    for(var row:rows)unique.put(row.transferId(),row);
                    if(rows.size()<100)break;
                    if(unique.size()==before)throw new java.io.IOException("Repeated history page");
                }
                final var result=List.copyOf(unique.values());
                Platform.runLater(()->{if(closed)return;all=result;applyFilter();finishRefresh();});
            } catch(Exception e){Platform.runLater(()->{if(closed)return;statusLabel.setText("Không tải được lịch sử. Dữ liệu cũ được giữ lại.");finishRefresh();});}
        });
    }
    private void finishRefresh(){busy=false;refreshButton.setDisable(false);if(refreshQueued){refreshQueued=false;onRefreshHistory();}}
    private void applyFilter(){
        var f=filter.value();var now=ZonedDateTime.now();
        filtered=all.stream().filter(i->f.matchesName(i.fileName())&&f.matchesTime(i.startedAtEpochMillis(),now))
                .sorted(f.comparator(TransferHistoryItemDto::fileName,TransferHistoryItemDto::fileSize,TransferHistoryItemDto::startedAtEpochMillis,TransferHistoryItemDto::transferId)).toList();
        render(false);
    }
    @FXML private void onPreviousPage(){if(page>0)select(page-1);}
    @FXML private void onNextPage(){if(page+1<knownPages)select(page+1);}
    private void select(int i){page=i;render(true);}
    private void buttons(){previousButton.setDisable(page==0);nextButton.setDisable(page+1>=knownPages);PageNumbers.render(pageNumbers,page,knownPages,false,this::select);}
    private void render(boolean navigation){
        knownPages=Math.max(1,(filtered.size()+99)/100);page=Math.max(0,Math.min(page,knownPages-1));
        int start=page*100;var rows=filtered.subList(start,Math.min(start+100,filtered.size()));
        if(!historyTable.getItems().equals(rows))historyTable.getItems().setAll(rows);
        if(navigation&&!rows.isEmpty())historyTable.scrollTo(0);
        pageLabel.setText("Trang "+(page+1)+" / "+knownPages);
        statusLabel.setText(filtered.size()+" kết quả · "+all.size()+" lượt tải");buttons();
    }
    public void shutdown() { closed=true; ioExecutor.shutdownNow(); }
}
