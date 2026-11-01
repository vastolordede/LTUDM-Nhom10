package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.FolderSearchItem;
import vn.edu.p2p.common.dto.SearchResultItem;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryEntryTest {
    @Test void mergedRowsUseRealPopularityAndSort() {
        DiscoveryEntry a = DiscoveryEntry.ofFile(new SearchResultItem(1, "a.txt", 20,
                "a".repeat(64), 1, 6, 100));
        DiscoveryEntry b = DiscoveryEntry.ofFolder(new FolderSearchItem(2, "z_folder", 2, 150, 2, 8, 200));
        assertEquals(List.of(b, a), List.of(a, b).stream()
                .sorted(DiscoveryEntry.comparator(DiscoverySort.POPULARITY)).toList());
        assertEquals(List.of(a, b), List.of(b, a).stream()
                .sorted(DiscoveryEntry.comparator(DiscoverySort.NAME_ASC)).toList());
    }
}
