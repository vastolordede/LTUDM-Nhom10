package vn.edu.p2p.peer.share;

import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import vn.edu.p2p.peer.view.PageNumbers;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ShareListPager {
    static final int PAGE_SIZE=100;
    static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("dd/MM/yyyy — HH:mm:ss").withZone(ZoneId.systemDefault());
    record GroupRow(SharedFileEntry file, String key, String title, long time) {}
    private int page;
    private final ListView<SharedFileEntry> view;
    private final ObservableList<SharedFileEntry> source;
    private final Button previous,next;
    private final Label label;
    private final HBox numbers;
    private List<GroupRow> shown=List.of();
    ShareListPager(ListView<SharedFileEntry> view, ObservableList<SharedFileEntry> source, Button previous, Button next, Label label, HBox numbers) {
        this.view=view; this.source=source; this.previous=previous; this.next=next; this.label=label; this.numbers=numbers;
        view.setPlaceholder(new Label("Chưa có tệp đang chia sẻ."));
        view.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(SharedFileEntry e, boolean empty) {
                super.updateItem(e,empty); setText(null); setGraphic(null);
                if (empty || e==null || getIndex()<0 || getIndex()>=shown.size()) return;
                int i=getIndex(); var row=shown.get(i);
                VBox content=new VBox(5);
                if (i==0 || !shown.get(i-1).key().equals(row.key())) {
                    Label heading=new Label(row.title()); heading.getStyleClass().add("share-group-title"); heading.setWrapText(true);
                    heading.maxWidthProperty().bind(view.widthProperty().subtract(65)); content.getChildren().add(heading);
                }
                Label name=new Label(e.fileName()+(e.sizeText().isEmpty()?"":"  ·  "+e.sizeText()));
                name.setWrapText(true); name.maxWidthProperty().bind(view.widthProperty().subtract(65));
                content.getChildren().add(name); setGraphic(content);
            }
        });
        previous.setOnAction(e->{if(page>0){page--;render();}});
        next.setOnAction(e->{page++;render();});
        source.addListener((ListChangeListener<SharedFileEntry>) c->render());
        render();
    }
    static List<GroupRow> groupRows(List<SharedFileEntry> files) {
        List<GroupRow> rows=new ArrayList<>();
        for(var f:files) {
            for(var g:f.groups()) rows.add(new GroupRow(f,"G"+g.groupId(),DATE.format(Instant.ofEpochMilli(g.sharedAt()))+" · Thư mục "+g.name(),g.sharedAt()));
            if(f.directAt()!=null) rows.add(new GroupRow(f,"D"+f.fileId()+":"+f.directAt(),DATE.format(Instant.ofEpochMilli(f.directAt()))+" · Tệp riêng",f.directAt()));
            if(f.legacy() || (f.groups().isEmpty()&&f.directAt()==null)) rows.add(new GroupRow(f,"legacy","Chia sẻ cũ · Không rõ ngày giờ",Long.MIN_VALUE));
        }
        return rows.stream().sorted(Comparator.comparingLong(GroupRow::time).reversed().thenComparing(GroupRow::key).thenComparing(x->x.file().fileName(),String.CASE_INSENSITIVE_ORDER)).toList();
    }
    private void render() {
        var rows=groupRows(List.copyOf(source));
        int count=Math.max(1,(rows.size()+PAGE_SIZE-1)/PAGE_SIZE);
        page=Math.max(0,Math.min(page,count-1));
        int from=page*PAGE_SIZE;
        shown=List.copyOf(rows.subList(from,Math.min(rows.size(),from+PAGE_SIZE)));
        view.getSelectionModel().clearSelection(); view.getItems().setAll(shown.stream().map(GroupRow::file).toList()); view.refresh();
        label.setText("Trang "+(page+1)+" / "+count);
        previous.setDisable(page==0); next.setDisable(page+1>=count);
        PageNumbers.render(numbers,page,count,false,index->{page=index;render();view.scrollTo(0);});
    }
}
