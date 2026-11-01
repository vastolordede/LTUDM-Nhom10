package vn.edu.p2p.common.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPaginationTest {
    @Test void defaultsAndBoundsPages() {
        SearchRequest file = new SearchRequest("sid", "", -2, 0);
        assertEquals(0, file.page());
        assertEquals(50, file.pageSize());
        FolderSearchRequest folder = new FolderSearchRequest("sid", "", 2, 500);
        assertEquals(2, folder.page());
        assertEquals(50, folder.pageSize());
    }
}
