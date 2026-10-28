package vn.edu.p2p.peer.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadSafetyTest {
    @Test
    void rejectsUnsafeNames() {
        for (String n : new String[]{"", "..", "a/b", "a\\b", "a:b", "a|b", "a?b", "a*b", "a\"b",
                "CON", "nul.txt", "COM1.log", "abc.", "abc ", "a<b"}) {
            assertNotNull(SafeFileName.validate(n), n);
        }
    }

    @Test
    void acceptsNormalNames() {
        for (String n : new String[]{"video.mp4", "Báo cáo.docx", "a.b.c", "console.txt"}) {
            assertNull(SafeFileName.validate(n), n);
        }
    }

    @Test
    void sanitizeAlwaysProducesValidName() {
        for (String n : new String[]{"a/b:c.txt", "CON", "..", "x.", ""}) {
            assertNull(SafeFileName.validate(SafeFileName.sanitize(n)), n);
        }
    }

    @Test
    void preflightRejectsHugeFile(@TempDir Path dir) {
        assertThrows(DownloadPreflight.PreflightException.class,
                () -> DownloadPreflight.check(dir.resolve("x.bin"), Long.MAX_VALUE / 2));
    }

    @Test
    void preflightAcceptsSmallFileAndCreatesParent(@TempDir Path dir) throws Exception {
        DownloadPreflight.check(dir.resolve("sub/x.bin"), 1024);
        assertTrue(Files.isDirectory(dir.resolve("sub")));
    }

    @Test
    void preflightRejectsDirectoryTarget(@TempDir Path dir) {
        assertThrows(DownloadPreflight.PreflightException.class,
                () -> DownloadPreflight.check(dir, 10));
    }
}