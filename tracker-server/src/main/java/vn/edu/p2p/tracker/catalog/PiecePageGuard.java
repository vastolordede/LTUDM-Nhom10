package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.common.dto.PieceInfo;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure boundary/geometry verification before inserting a paginated metadata batch. */
public final class PiecePageGuard {
    private PiecePageGuard() {}

    public static void check(long fileSize, int pieceSize, int pieceCount, List<PieceInfo> pieces) {
        if (fileSize < 0 || pieceSize <= 0 || pieceCount < 0 || pieces == null
                || pieces.isEmpty() || pieces.size() > 512) {
            throw new IllegalArgumentException("Invalid file/page geometry");
        }
        long calculated = fileSize / pieceSize + (fileSize % pieceSize == 0 ? 0 : 1);
        if (calculated != pieceCount) {
            throw new IllegalArgumentException("Stored file geometry is inconsistent");
        }
        Set<Integer> seen = new HashSet<>();
        for (PieceInfo piece : pieces) {
            if (piece == null || piece.pieceIndex() < 0 || piece.pieceIndex() >= pieceCount
                    || !seen.add(piece.pieceIndex()) || piece.pieceHash() == null
                    || !piece.pieceHash().matches("[0-9A-Fa-f]{64}")) {
                throw new IllegalArgumentException("Invalid/duplicate piece metadata");
            }
            long start = (long) piece.pieceIndex() * pieceSize;
            int expected = (int) Math.min(pieceSize, fileSize - start);
            if (piece.pieceLength() != expected) {
                throw new IllegalArgumentException("Piece size does not match file geometry");
            }
        }
    }
}
