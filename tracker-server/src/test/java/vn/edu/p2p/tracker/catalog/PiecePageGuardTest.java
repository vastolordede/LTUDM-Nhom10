package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.PieceInfo;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PiecePageGuardTest {
    private static final String H = "a".repeat(64);

    @Test void permitsOutOfOrderPagesButRejectsRepeatedIndexes() {
        assertDoesNotThrow(() -> PiecePageGuard.check(2100, 1000, 3,
                List.of(new PieceInfo(2, 100, H), new PieceInfo(0, 1000, H))));
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(2100, 1000, 3,
                List.of(new PieceInfo(1, 1000, H), new PieceInfo(1, 1000, H))));
    }
    @Test void rejectsWrongSizeOutOfRangeAndMalformedHash() {
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(2100, 1000, 3,
                List.of(new PieceInfo(2, 1000, H))));
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(2100, 1000, 3,
                List.of(new PieceInfo(3, 100, H))));
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(2100, 1000, 3,
                List.of(new PieceInfo(2, 100, "bad"))));
    }
    @Test void rejectsEmptyPagesAndWrongDeclaredPieceCount() {
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(100, 1024, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(100, 1024, 2,
                List.of(new PieceInfo(0, 100, H))));
    }
}
