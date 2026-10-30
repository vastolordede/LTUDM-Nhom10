package vn.edu.p2p.common.dto;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FileMetadataGeometryTest {
    private static final String H = "a".repeat(64);

    @Test void acceptsHeaderOnlyForPagedMetadata() {
        var m = new FileMetadata("huge.bin", 2049L * 1024, H, 1024, 2049, List.of());
        assertTrue(m.pieces().isEmpty());
    }
    @Test void rejectsCountMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileMetadata("x", 1025, H, 1024, 1, List.of()));
    }
    @Test void rejectsBadLastPiece() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileMetadata("x", 1025, H, 1024, 2,
                        List.of(new PieceInfo(0, 1024, H), new PieceInfo(1, 1024, H))));
    }
    @Test void rejectsMissingAndDuplicatePieceIndexes() {
        assertThrows(IllegalArgumentException.class,
                () -> new FileMetadata("x", 2048, H, 1024, 2,
                        List.of(new PieceInfo(0, 1024, H), new PieceInfo(0, 1024, H))));
    }
    @Test void permitsEmptyFile() {
        assertEquals(0, new FileMetadata("empty", 0, H, 1024, 0, List.of()).pieceCount());
    }
}
