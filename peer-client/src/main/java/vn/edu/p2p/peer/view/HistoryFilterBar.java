package vn.edu.p2p.peer.view;

import javafx.animation.PauseTransition;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.geometry.Pos;
import javafx.util.Duration;

/** Same controls in the upload/share and download history screens; wraps on small windows. */
public final class HistoryFilterBar extends FlowPane {
    private final TextField name=new TextField();
    private final ComboBox<HistoryFilter.Order> sort=new ComboBox<>();
    private final ComboBox<HistoryFilter.Period> period=new ComboBox<>();
    private final DatePicker from=new DatePicker(), to=new DatePicker();
    private final Label fromLabel=new Label("Từ"), toLabel=new Label("Đến"), error=new Label();
    private boolean resetting;
    public HistoryFilterBar(Runnable changed) {
        setHgap(8);setVgap(6);setAlignment(Pos.CENTER_LEFT);
        name.setPromptText("Tìm theo tên");name.setPrefColumnCount(17);
        sort.getItems().setAll(HistoryFilter.Order.values());sort.setValue(HistoryFilter.Order.NEWEST);sort.setPrefWidth(175);
        period.getItems().setAll(HistoryFilter.Period.values());period.setValue(HistoryFilter.Period.ALL);period.setPrefWidth(165);
        from.setEditable(false);to.setEditable(false);from.setPrefWidth(135);to.setPrefWidth(135);
        error.setStyle("-fx-text-fill: #fca5a5;");
        Button reset=new Button("Bỏ lọc");
        getChildren().addAll(name,sort,period,fromLabel,from,toLabel,to,reset,error);
        PauseTransition debounce=new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e->changed.run());
        Runnable update=()->{if(resetting)return;debounce.stop();refreshControls();changed.run();};
        name.textProperty().addListener((o,a,b)->{if(!resetting)debounce.playFromStart();});
        name.setOnAction(e->update.run());
        sort.valueProperty().addListener((o,a,b)->update.run());
        period.valueProperty().addListener((o,a,b)->update.run());
        from.valueProperty().addListener((o,a,b)->update.run());to.valueProperty().addListener((o,a,b)->update.run());
        reset.setOnAction(e->{resetting=true;try{name.clear();sort.setValue(HistoryFilter.Order.NEWEST);period.setValue(HistoryFilter.Period.ALL);from.setValue(null);to.setValue(null);}finally{resetting=false;}update.run();});
        refreshControls();
    }
    public HistoryFilter value(){return new HistoryFilter(name.getText(),sort.getValue(),period.getValue(),from.getValue(),to.getValue());}
    private void refreshControls(){boolean custom=period.getValue()==HistoryFilter.Period.CUSTOM;
        for(var n:java.util.List.of(fromLabel,from,toLabel,to)){n.setVisible(custom);n.setManaged(custom);}
        error.setText(value().valid()?"":"Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");error.setVisible(!value().valid());error.setManaged(!value().valid());
    }
}
