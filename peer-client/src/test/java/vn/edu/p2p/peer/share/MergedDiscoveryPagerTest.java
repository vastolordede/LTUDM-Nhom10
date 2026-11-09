package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.common.dto.FolderSearchItem;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MergedDiscoveryPagerTest {
    @Test
    void ranksSixtyFilesAheadOfOneFolderWithoutSkippingAfterFifty() throws Exception {
        List<DiscoveryEntry> files = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            files.add(DiscoveryEntry.ofFile(new SearchResultItem(1000 - i, "f" + i,
                    100, "hash", 1, 100 - i, 1_000L)));
        }
        List<DiscoveryEntry> folders = List.of(DiscoveryEntry.ofFolder(
                new FolderSearchItem(5, "folder", 1, 0, 1, 1, 1_000L)));
        MergedDiscoveryPager pager = new MergedDiscoveryPager(
                (page, size) -> slice(files, page, size),
                (page, size) -> slice(folders, page, size),
                DiscoveryEntry.comparator(DiscoverySort.POPULARITY));
        assertEquals(50, pager.nextPage(50).size());
        List<DiscoveryEntry> second = pager.nextPage(50);
        assertEquals(11, second.size());
        for (int i = 0; i < 10; i++) assertTrue(!second.get(i).isFolder());
        assertTrue(second.get(10).isFolder());
        assertFalse(pager.hasMore());
    }

    @Test
    void doesNotFetchAnExhaustedSourceAgain() throws Exception {
        int[] fileFetches = {0};
        MergedDiscoveryPager pager = new MergedDiscoveryPager(
                (page, size) -> {fileFetches[0]++; return List.of();},
                (page, size) -> List.of(),
                DiscoveryEntry.comparator(DiscoverySort.POPULARITY));
        assertEquals(List.of(), pager.nextPage(50));
        assertFalse(pager.hasMore());
        assertEquals(1, fileFetches[0]);
    }

    private static List<DiscoveryEntry> slice(List<DiscoveryEntry> rows, int page, int size) {
        int from = Math.min(page * size, rows.size());
        return rows.subList(from, Math.min(from + size, rows.size()));
    }
}
