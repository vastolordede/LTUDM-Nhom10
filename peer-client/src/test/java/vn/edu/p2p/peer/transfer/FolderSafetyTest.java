package vn.edu.p2p.peer.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.peer.share.FolderScanner;

class FolderSafetyTest {

    @Test
    void scansNestedFolderWithRelativePaths(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("root.txt"), "1");
        Files.writeString(dir.resolve("a/b/deep.pdf"), "22");
        List<FolderScanner.Entry> entries = FolderScanner.scan(dir);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.relativePath().equals("a/b/deep.pdf")));
    }

    @Test
    void rejectsUnsafeNameInsideFolder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ok.txt"), "x");
        try {
            Files.writeString(dir.resolve("CON.txt"), "x");
        } catch (IOException ignored) {
            return; // he dieu hanh khong cho tao, coi nhu da an toan
        }
        assertThrows(IOException.class, () -> FolderScanner.scan(dir));
    }

    @Test
    void resolverBlocksTraversalAndAbsolutePaths(@TempDir Path dir) {
        for (String bad : new String[]{"../x.txt", "a/../../x.txt", "/etc/x", "C:\\x.txt", "a\\b.txt", "", "a//b"}) {
            assertThrows(IOException.class, () -> FolderPathResolver.resolve(dir, bad), bad);
        }
    }

    @Test
    void resolverAcceptsNestedPath(@TempDir Path dir) throws Exception {
        Path p = FolderPathResolver.resolve(dir, "a/b/c.txt");
        assertTrue(p.startsWith(dir.toAbsolutePath().normalize()));
    }

    @Test
    void resolverRejectsSymlinkRoot(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("real");
        Files.createDirectory(real);
        Path alias = dir.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return;
        }
        assertThrows(IOException.class, () -> FolderPathResolver.resolve(alias, "data.bin"));
    }

    @Test
    void uniquifyAddsCounter(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.txt");
        Files.writeString(f, "x");
        assertEquals("a (1).txt", FolderPathResolver.uniquify(f).getFileName().toString());
    }
}