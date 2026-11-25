package vn.edu.p2p.peer.view;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import java.util.function.IntConsumer;

/** Displays a bounded window of known page numbers (zero-based internally). */
public final class PageNumbers {
    private PageNumbers() {}
    public static void render(HBox box, int page, int count, boolean busy, IntConsumer select) {
        count = Math.max(1, count);
        box.getChildren().clear();
        int start = Math.max(0, Math.min(page - 1, count - 3));
        int end = Math.min(count, start + 3);
        if (start > 0) { add(box, 0, page, busy, select); if (start > 1) box.getChildren().add(new Label("…")); }
        for (int i=start; i<end; i++) add(box,i,page,busy,select);
        if (end < count) { if (end < count-1) box.getChildren().add(new Label("…")); add(box,count-1,page,busy,select); }
    }
    private static void add(HBox box,int index,int current,boolean busy,IntConsumer select) {
        Button b = new Button(Integer.toString(index+1));
        b.getStyleClass().add("page-number");
        if (index == current) b.getStyleClass().add("page-current");
        b.setDisable(busy || index == current);
        b.setOnAction(e -> select.accept(index)); box.getChildren().add(b);
    }
}
