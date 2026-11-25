package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShareHistoryStoreTest {
    @TempDir Path dir;
    @Test void confirmedBatchesStaySeparateAfterUnshareAndRestart() throws Exception {
        var inventory=new SharedFilesStore(dir.resolve("active.json"));
        inventory.add(SharedFilesStore.Entry.direct("a.txt","a.txt",3,"aa",1000));
        inventory.history().append(1000,"Tệp riêng",List.of(new ShareHistoryStore.Item("a.txt",3)));
        inventory.history().append(2000,"Thư mục figures",List.of(new ShareHistoryStore.Item("a.png",1),new ShareHistoryStore.Item("b.png",2)));
        inventory.removeByHash("aa");
        assertTrue(inventory.load().isEmpty());
        var history=new SharedFilesStore(dir.resolve("active.json")).history().load();
        assertEquals(2,history.size()); assertEquals(1000,history.getFirst().sharedAt());
        assertEquals(2,history.getLast().items().size()); assertNotEquals(history.getFirst().id(),history.getLast().id());
    }
}
