package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** R57: never upload bytes which differ from SHA-256 published at sharing time. */
class LocalFileRegistryIntegrityTest {
    @Test
    void detectsChangedSourceAndHidesInvalidPiece(@TempDir Path dir) throws Exception {
        byte[] original = new byte[]{1, 2, 3, 4};
        Path file = dir.resolve("source.bin");
        Files.write(file, original);
        FileMetadata meta = metadata(original);
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(42, file, meta);
        assertArrayEquals(original, registry.readPieceBytes(42, 0));
        Files.write(file, new byte[]{1, 2, 3, 5});
        assertThrows(java.io.IOException.class, () -> registry.readPieceBytes(42, 0));
        assertFalse(registry.find(42).orElseThrow().hasPiece(0));
    }

    @Test
    void rejectsInvalidPieceBeforeWritingOrSeeding(@TempDir Path dir) throws Exception {
        byte[] original = new byte[]{1, 2, 3, 4};
        Path target = dir.resolve("download.bin");
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerDownloadingFile(7, target, metadata(original), new BitSet());
        assertThrows(java.io.IOException.class,
                () -> registry.writePieceBytes(7, 0, new byte[]{1, 2, 3, 5}));
        assertFalse(registry.find(7).orElseThrow().hasPiece(0));
        registry.writePieceBytes(7, 0, original);
        assertTrue(registry.find(7).orElseThrow().hasPiece(0));
    }

    @Test
    void cannotSeedFromHeaderOnlyMetadata(@TempDir Path dir) throws Exception {
        byte[] original = new byte[]{1, 2, 3, 4};
        var meta = metadata(original);
        var header = new FileMetadata("piece.bin", meta.fileSize(), meta.fileHash(),
                meta.pieceSize(), meta.pieceCount(), List.of());
        var path = dir.resolve("header.bin");
        Files.write(path, original);
        var registry = new LocalFileRegistry();
        registry.registerCompleteFile(12, path, header);
        assertThrows(java.io.IOException.class, () -> registry.readPieceBytes(12, 0));
        assertFalse(registry.find(12).orElseThrow().hasPiece(0));
    }

    private static FileMetadata metadata(byte[] bytes) {
        String hash = FileHashUtil.sha256(bytes, 0, bytes.length);
        return new FileMetadata("piece.bin", bytes.length, hash, bytes.length, 1,
                List.of(new PieceInfo(0, bytes.length, hash)));
    }
}
