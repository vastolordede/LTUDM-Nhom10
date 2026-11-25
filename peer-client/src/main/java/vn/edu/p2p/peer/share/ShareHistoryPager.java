package vn.edu.p2p.peer.share;

import javafx.scene.control.*;
import javafx.scene.layout.*;
import vn.edu.p2p.peer.view.*;
import java.time.*;
import java.util.*;

/** Successful local share batches, filtered before paging; each page contains up to 100 file entries. */
final class ShareHistoryPager {
    record Row(String id,String heading,String name,long size,boolean folder) {}
    private final TreeView<String> view; private final HBox numbers; private final Label label;
    private final Button prev,next; private final HistoryFilterBar filter;
    private int page; private List<ShareHistoryStore.Batch> batches=List.of(); private List<Row> all=List.of();
    private final Set<String> expanded=new HashSet<>(); private final Set<String> collapsed=new HashSet<>();
    ShareHistoryPager(TreeView<String> view,HBox numbers,Label label,Button prev,Button next,VBox filterHost) {
        this.view=view;this.numbers=numbers;this.label=label;this.prev=prev;this.next=next;
        view.setShowRoot(false);view.setCellFactory(v->new TreeCell<>(){
            @Override protected void updateItem(String value,boolean empty){super.updateItem(value,empty);setText(empty?null:value);setGraphic(null);
                getStyleClass().remove("share-group-title");
                if(!empty&&getTreeItem()!=null&&getTreeItem().getParent()==view.getRoot())getStyleClass().add("share-group-title");
            }
        });
        filter=new HistoryFilterBar(()->{page=0;applyFilter();});filterHost.getChildren().add(filter);
        prev.setOnAction(e->select(page-1));next.setOnAction(e->select(page+1));render();
    }
    void update(List<ShareHistoryStore.Batch> batches){this.batches=List.copyOf(batches);applyFilter();}
    private void applyFilter(){
        var f=filter.value();var now=ZonedDateTime.now();List<Row> rows=new ArrayList<>();
        var sorted=batches.stream().filter(b->f.matchesTime(b.sharedAt(),now))
                .sorted(f.comparator(ShareHistoryStore.Batch::title,b->b.items().stream().mapToLong(i->Math.max(0,i.size())).sum(),ShareHistoryStore.Batch::sharedAt,ShareHistoryStore.Batch::id)).toList();
        for(var b:sorted){
            String heading=ShareListPager.DATE.format(Instant.ofEpochMilli(b.sharedAt()))+" · "+b.title()+" · "+b.items().stream().filter(i->i.size()>=0).count()+" tệp";
            var items=b.items().stream().filter(i->f.matchesName(b.title())||f.matchesName(i.name()))
                    .sorted(f.comparator(ShareHistoryStore.Item::name,ShareHistoryStore.Item::size,i->b.sharedAt(),ShareHistoryStore.Item::name)).toList();
            if(b.items().isEmpty()&&f.matchesName(b.title()))rows.add(new Row(b.id(),heading,"Thư mục rỗng",-1,false));
            for(var item:items)rows.add(new Row(b.id(),heading,item.name(),item.size(),b.title().startsWith("Thư mục ")));
        }
        all=List.copyOf(rows);render();
    }
    private void select(int i){page=i;render();view.scrollTo(0);}
    private TreeItem<String> branch(String title,String key,boolean openByDefault){
        var node=new TreeItem<String>(title);node.setExpanded(expanded.contains(key)||(!collapsed.contains(key)&&openByDefault));
        node.expandedProperty().addListener((o,a,b)->{if(b){expanded.add(key);collapsed.remove(key);}else{collapsed.add(key);expanded.remove(key);}});return node;
    }
    private void render(){
        int count=Math.max(1,(all.size()+99)/100);page=Math.max(0,Math.min(page,count-1));int start=page*100;
        var root=new TreeItem<String>("Lịch sử chia sẻ");root.setExpanded(true);
        Map<String,TreeItem<String>> nodes=new LinkedHashMap<>();
        for(var row:all.subList(start,Math.min(start+100,all.size()))){
            String id=row.id();var batch=nodes.get(id);
            if(batch==null){batch=branch(row.heading(),id,true);nodes.put(id,batch);root.getChildren().add(batch);}
            var parent=batch;String key=id;
            boolean directory=row.folder()&&row.size()<0&&row.name().endsWith("/");
            List<String> parts=row.folder()?ShareHistoryPaths.parts(row.name()):List.of(row.name());
            int depth=directory?parts.size():Math.max(0,parts.size()-1);
            for(int j=0;j<depth;j++){
                key+="/"+parts.get(j);var dir=nodes.get(key);
                if(dir==null){dir=branch("📁 "+parts.get(j),key,false);nodes.put(key,dir);parent.getChildren().add(dir);}parent=dir;
            }
            if(!directory){
                String name=parts.isEmpty()?row.name():parts.getLast();
                parent.getChildren().add(new TreeItem<>("📄 "+name+(row.size()<0?"":"  ·  "+bytes(row.size()))));
            }
        }
        if(all.isEmpty())root.getChildren().add(new TreeItem<>("Không có lượt chia sẻ phù hợp."));
        view.setRoot(root);label.setText("Trang "+(page+1)+" / "+count+" · "+all.size()+" mục");prev.setDisable(page==0);next.setDisable(page+1>=count);
        PageNumbers.render(numbers,page,count,false,this::select);
    }
    private static String bytes(long n){return n>=1048576?String.format("%.2f MiB",n/1048576.0):n>=1024?String.format("%.1f KiB",n/1024.0):n+" B";}
}
