package vn.edu.p2p.peer.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

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
        Path target = dir.resolve("x.bin");
        assertThrows(DownloadPreflight.PreflightException.class,
                () -> DownloadPreflight.check(target, Long.MAX_VALUE / 2));
        assertFalse(Files.exists(target));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "Preflight must not leave a destination or temp file");
        } catch (java.io.IOException e) {
            fail(e);
        }
    }

    @Test
    void preflightAcceptsSmallFileAndCreatesParent(@TempDir Path dir) throws Exception {
        DownloadPreflight.check(dir.resolve("sub/x.bin"), 1024);
        assertTrue(Files.isDirectory(dir.resolve("sub")));
    }

    @Test
    void preflightRejectsNegativeFileSize(@TempDir Path dir) {
        assertThrows(DownloadPreflight.PreflightException.class,
                () -> DownloadPreflight.check(dir.resolve("file.bin"), -1));
    }

    @Test
    void preflightRejectsSymlinkTarget(@TempDir Path dir) throws Exception {
        Path actual = dir.resolve("actual.txt");
        Files.writeString(actual, "untouched");
        Path alias = dir.resolve("alias.txt");
        try {
            Files.createSymbolicLink(alias, actual);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return; // OS/filesystem does not allow symlinks
        }
        assertThrows(DownloadPreflight.PreflightException.class,
                () -> DownloadPreflight.check(alias, 5));
    }

    @Test
    void preflightRejectsDirectoryTarget(@TempDir Path dir) {
        assertThrows(DownloadPreflight.PreflightException.class,
                () -> DownloadPreflight.check(dir, 10));
    }

    @Test
    void preflightRejectsReadOnlyTargetWhenFilesystemEnforcesIt(@TempDir Path dir)
            throws Exception {
        Path target = dir.resolve("read-only.bin");
        byte[] original = new byte[]{1, 2, 3};
        Files.write(target, original);
        try {
            Files.setAttribute(target, "dos:readonly", true);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.assumeTrue(false,
                    "Filesystem does not support setting the DOS read-only attribute");
            return;
        }
        try {
            Assumptions.assumeTrue(!Files.isWritable(target),
                    "Filesystem reports the DOS read-only file as writable");
            assertThrows(DownloadPreflight.PreflightException.class,
                    () -> DownloadPreflight.check(target, original.length));
            assertArrayEquals(original, Files.readAllBytes(target));
            try (var files = Files.list(dir)) {
                assertEquals(1, files.count(), "Permission rejection must not create temp files");
            }
        } finally {
            Files.setAttribute(target, "dos:readonly", false);
        }
    }
}