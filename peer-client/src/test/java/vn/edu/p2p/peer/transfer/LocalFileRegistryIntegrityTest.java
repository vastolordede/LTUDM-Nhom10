package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.SharedFilesStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** R57: never upload bytes which differ from SHA-256 published at sharing time. */
@Timeout(value = 2, unit = java.util.concurrent.TimeUnit.SECONDS)
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

    @Test
    void restartRestoresValidShareButRejectsSameSizeChangedSource(@TempDir Path dir) throws Exception {
        byte[] original = new byte[]{1, 2, 3, 4};
        Path file = dir.resolve("source.bin");
        Path storeFile = dir.resolve("shared.json");
        Files.write(file, original);
        FileMetadata metadata = metadata(original);
        new SharedFilesStore(storeFile).add(new SharedFilesStore.Entry(
                file.toString(), metadata.fileName(), metadata.fileSize(), metadata.fileHash()));

        SharedFilesStore restartedStore = new SharedFilesStore(storeFile);
        LocalFileRegistry restartedRegistry = new LocalFileRegistry();
        SharedFilesStore.Entry restored = restartedStore.loadValid().getFirst();
        restartedRegistry.registerCompleteFile(42, Path.of(restored.path()), metadata);
        assertArrayEquals(original, restartedRegistry.readPieceBytes(42, 0));

        Files.write(file, new byte[]{4, 3, 2, 1});
        SharedFilesStore afterChange = new SharedFilesStore(storeFile);
        assertTrue(afterChange.loadValid().isEmpty(),
                "Same-size content changes while the peer is stopped must not be restored");
        LocalFileRegistry nextRegistry = new LocalFileRegistry();
        assertTrue(nextRegistry.find(42).isEmpty());
        assertThrows(java.io.IOException.class, () -> nextRegistry.readPieceBytes(42, 0));
    }

    @Test
    void restartDoesNotRegisterDeletedOrResizedShare(@TempDir Path dir) throws Exception {
        assertUnavailableAfterRestore(dir.resolve("deleted.bin"), dir.resolve("deleted.json"),
                43, true);
        assertUnavailableAfterRestore(dir.resolve("resized.bin"), dir.resolve("resized.json"),
                44, false);
    }

    private static void assertUnavailableAfterRestore(Path file, Path storePath, long fileId,
                                                       boolean delete) throws Exception {
        byte[] original = new byte[]{1, 2, 3, 4};
        Files.write(file, original);
        FileMetadata metadata = metadata(original);
        new SharedFilesStore(storePath).add(new SharedFilesStore.Entry(
                file.toString(), metadata.fileName(), metadata.fileSize(), metadata.fileHash()));

        if (delete) {
            Files.delete(file);
        } else {
            Files.write(file, new byte[]{1, 2, 3, 4, 5});
        }

        SharedFilesStore restartedStore = new SharedFilesStore(storePath);
        assertTrue(restartedStore.loadValid().isEmpty());
        LocalFileRegistry restartedRegistry = new LocalFileRegistry();
        assertTrue(restartedRegistry.find(fileId).isEmpty());
        assertThrows(java.io.IOException.class, () -> restartedRegistry.readPieceBytes(fileId, 0));
    }

    private static FileMetadata metadata(byte[] bytes) {
        String hash = FileHashUtil.sha256(bytes, 0, bytes.length);
        return new FileMetadata("piece.bin", bytes.length, hash, bytes.length, 1,
                List.of(new PieceInfo(0, bytes.length, hash)));
    }
}
