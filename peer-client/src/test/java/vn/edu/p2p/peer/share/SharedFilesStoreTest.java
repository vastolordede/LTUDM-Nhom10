package vn.edu.p2p.peer.share;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedFilesStoreTest {

    @Test
    void persistsAndRestores(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello");
        SharedFilesStore store = new SharedFilesStore(dir.resolve("store.json"));
        store.add(new SharedFilesStore.Entry(file.toString(), "a.txt", 5, "AA"));

        SharedFilesStore reopened = new SharedFilesStore(dir.resolve("store.json"));
        assertEquals(1, reopened.loadValid().size());
    }

    @Test
    void dropsMissingOrChangedFiles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello");
        SharedFilesStore store = new SharedFilesStore(dir.resolve("store.json"));
        store.add(new SharedFilesStore.Entry(file.toString(), "a.txt", 5, "AA"));

        Files.writeString(file, "hello world"); // đổi kích thước
        assertTrue(store.loadValid().isEmpty());

        Files.delete(file);
        assertTrue(store.loadValid().isEmpty());
    }

    @Test
    void rejectsSameSizeTamperingOnRestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "hello");
        SharedFilesStore store = new SharedFilesStore(dir.resolve("store.json"));
        store.add(new SharedFilesStore.Entry(file.toString(), "a.txt", 5,
                FileHashUtil.sha256(file)));
        Files.writeString(file, "HELLO");
        assertTrue(store.loadValid().isEmpty(), "Same-size changed content must not be re-advertised");
    }

    @Test
    void removeAndDuplicateHash(@TempDir Path dir) throws Exception {
        SharedFilesStore store = new SharedFilesStore(dir.resolve("store.json"));
        store.add(new SharedFilesStore.Entry("x", "x", 1, "AA"));
        store.add(new SharedFilesStore.Entry("y", "y", 1, "aa")); // cùng hash: thay thế
        assertEquals(1, store.load().size());
        store.removeByHash("AA");
        assertTrue(store.load().isEmpty());
    }

    @Test
    void corruptStoreIsIgnored(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("store.json");
        Files.writeString(json, "{ khong phai json");
        SharedFilesStore store = new SharedFilesStore(json);
        assertTrue(store.load().isEmpty());
        assertThrows(java.io.IOException.class, () ->
                store.add(new SharedFilesStore.Entry("x", "x", 1, "aa")));
        assertEquals("{ khong phai json", Files.readString(json),
                "Corrupt data must not be silently overwritten");
    }
}