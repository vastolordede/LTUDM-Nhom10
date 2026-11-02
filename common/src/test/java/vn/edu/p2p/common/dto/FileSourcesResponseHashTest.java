package vn.edu.p2p.common.dto;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileSourcesResponseHashTest {
    @Test void rejectsInvalidTrustedCatalogHash() {
        assertThrows(IllegalArgumentException.class, () -> new FileSourcesResponse(4L, List.of(), "oops"));
        assertEquals("a".repeat(64), new FileSourcesResponse(4L, List.of(), "a".repeat(64)).fileHash());
    }
}
