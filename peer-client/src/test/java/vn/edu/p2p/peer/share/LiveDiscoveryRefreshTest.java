package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveDiscoveryRefreshTest {
    @Test void latestFilterWinsOverOldPollingAndIntermediateFilters(){
        var q=new LatestDiscoveryRequest();
        q.offer("", "Tất cả", DiscoverySort.POPULARITY,2,false);
        var old=q.take();
        q.offer("docs", "Thư mục", DiscoverySort.SIZE_ASC,0,true);
        q.offer("new", "Tệp", DiscoverySort.DATE_NEWEST,0,true);
        assertFalse(q.isCurrent(old));assertTrue(q.hasCurrentExplicit());
        q.completed(old);var fresh=q.take();assertTrue(q.isCurrent(fresh));
        assertEquals("new",fresh.query());assertEquals("Tệp",fresh.type());assertEquals(0,fresh.page());
        assertEquals(DiscoverySort.DATE_NEWEST,fresh.sort());
        q.invalidate();assertFalse(q.isCurrent(fresh));assertFalse(q.hasCurrentExplicit());
        q.completed(fresh);assertNull(q.pending());
    }
    @Test void unchangedRowsStaySameObjectsAndUpdatesPreserveOtherRows(){
        var a=file(1,1);var b=file(2,1);var c=file(3,1);
        var rows=new ArrayList<>(List.of(a,b,c));
        DiscoveryRows.reconcile(rows,List.of(file(1,1),file(2,1),file(3,1)));
        assertSame(a,rows.get(0));assertSame(b,rows.get(1));assertSame(c,rows.get(2));
        var changed=file(2,2);DiscoveryRows.reconcile(rows,List.of(changed,c,file(4,1)));
        assertEquals(List.of(changed,c,file(4,1)),rows);assertSame(c,rows.get(1));
        DiscoveryRows.reconcile(rows,List.of(c,changed));assertSame(c,rows.get(0));assertSame(changed,rows.get(1));
    }
    @Test void fileAndFolderIdsDoNotCollide(){
        var a=file(1,1);var folder=DiscoveryEntry.ofFolder(new FolderSearchItem(1,"folder",1,20,1));
        var rows=new ArrayList<>(List.of(a,folder));DiscoveryRows.reconcile(rows,List.of(folder,a));
        assertSame(folder,rows.get(0));assertSame(a,rows.get(1));
    }
    private static DiscoveryEntry file(long id,int source){return DiscoveryEntry.ofFile(new SearchResultItem(id,"file"+id,20,"hash",source));}
    @Test void folderHistoryPreservesNestedAndEmptyPaths(){
        assertEquals(List.of("a","b","file.txt"),ShareHistoryPaths.parts("a\\b/file.txt"));
        assertEquals(List.of("empty/child","other"),ShareHistoryPaths.emptyLeafDirectories(List.of("a","empty","empty/child","other"),List.of("a/file.txt")));
    }
}
