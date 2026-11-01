package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FolderEmptyDirectoryTest {
    @TempDir Path temp;

    @Test void nestedEmptyDirectoriesAndZeroByteFileSurviveScanning() throws Exception {
        Path base = Files.createDirectories(temp.resolve("root"));
        Files.createDirectories(base.resolve("deep/empty/leaf"));
        Files.write(base.resolve("deep/zero.bin"), new byte[0]);
        assertEquals(1, FolderScanner.scan(base).size());
        assertEquals(0, FolderScanner.scan(base).getFirst().size());
        assertTrue(FolderScanner.scanDirectories(base).contains("deep/empty/leaf"));
        var metadata = new FileMetadataBuilder().build(base.resolve("deep/zero.bin"));
        assertEquals(0, metadata.pieceCount());
        assertEquals(0, metadata.fileSize());
    }
}
