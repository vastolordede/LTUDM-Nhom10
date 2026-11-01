package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.PieceInfo;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Pure logical geometry: no 2+ GB allocation or local disk space required. */
class PiecePageLargeFileTest {
    private static final int PIECE_SIZE = 1024 * 1024;
    private static final long FILE_SIZE = 2_147_484_000L;
    private static final int COUNT = 2049;
    private static final String HASH = "b".repeat(64);

    @Test void acceptsFirstAndFinalPageBeyondTwoGiBAndTwoThousandPieces() {
        List<PieceInfo> page = IntStream.range(0, 512)
                .mapToObj(i -> new PieceInfo(i, PIECE_SIZE, HASH)).toList();
        assertDoesNotThrow(() -> PiecePageGuard.check(FILE_SIZE, PIECE_SIZE, COUNT, page));
        assertDoesNotThrow(() -> PiecePageGuard.check(FILE_SIZE, PIECE_SIZE, COUNT,
                List.of(new PieceInfo(2048, 352, HASH))));
    }
    @Test void rejectsOversizedOrConflictingLastPage() {
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(FILE_SIZE, PIECE_SIZE, COUNT,
                List.of(new PieceInfo(2048, PIECE_SIZE, HASH))));
        List<PieceInfo> over = IntStream.range(0, 513)
                .mapToObj(i -> new PieceInfo(i, PIECE_SIZE, HASH)).toList();
        assertThrows(IllegalArgumentException.class, () -> PiecePageGuard.check(FILE_SIZE, PIECE_SIZE, COUNT, over));
    }
}
