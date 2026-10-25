package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.peer.share.FileChunker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdaptiveChunkingTest {
    @Test
    void pieceSizeScalesWithFileLength() {
        assertEquals(256 * 1024, FileChunker.choosePieceSize(1000));
        assertEquals(1024 * 1024, FileChunker.choosePieceSize(1024L * 1024 * 1024));
        assertEquals(4 * 1024 * 1024, FileChunker.choosePieceSize(8L * 1024 * 1024 * 1024));
        assertEquals(16 * 1024 * 1024, FileChunker.choosePieceSize(512L * 1024 * 1024 * 1024));
        assertEquals(1, FileChunker.pieceCount(1, FileChunker.choosePieceSize(1)));
        assertThrows(IllegalArgumentException.class, () -> FileChunker.pieceCount(Long.MAX_VALUE, 1));
    }
}
