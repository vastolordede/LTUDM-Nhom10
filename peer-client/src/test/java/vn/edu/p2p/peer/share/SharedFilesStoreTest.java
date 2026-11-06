package vn.edu.p2p.peer.share;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 2, unit = java.util.concurrent.TimeUnit.SECONDS)
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
        Path missing = dir.resolve("missing.txt");
        Path resized = dir.resolve("resized.txt");
        Files.writeString(missing, "hello");
        Files.writeString(resized, "world");
        SharedFilesStore store = new SharedFilesStore(dir.resolve("store.json"));
        store.add(new SharedFilesStore.Entry(missing.toString(), "missing.txt", 5, "AA"));
        store.add(new SharedFilesStore.Entry(resized.toString(), "resized.txt", 5, "BB"));

        Files.delete(missing);
        assertEquals(1, store.loadValid().size(), "A missing entry is dropped independently");

        Files.writeString(resized, "hello world");
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
    void characterizationOnePeerStoresOnlyOneEntryPerHash(@TempDir Path dir) throws Exception {
        SharedFilesStore store = new SharedFilesStore(dir.resolve("store.json"));
        store.add(new SharedFilesStore.Entry("x", "x", 1, "AA"));
        store.add(new SharedFilesStore.Entry("y", "y", 1, "aa"));
        assertEquals(List.of(new SharedFilesStore.Entry("y", "y", 1, "aa")), store.load(),
                "The current model keeps one shared path per hash on a peer");
        store.removeByHash("AA");
        assertTrue(store.load().isEmpty());
    }

    @Test
    void corruptStoreIsIgnored(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("store.json");
        Files.writeString(json, "{ khong phai json");
        SharedFilesStore store = new SharedFilesStore(json);
        IllegalStateException error = assertThrows(IllegalStateException.class, store::load);
        assertTrue(error.getMessage().contains("danh sách chia sẻ"));
        assertThrows(java.io.IOException.class, () ->
                store.add(new SharedFilesStore.Entry("x", "x", 1, "aa")));
        assertEquals("{ khong phai json", Files.readString(json),
                "Corrupt data must not be silently overwritten");
        assertTrue(Files.exists(json.resolveSibling("store.json.bak")));
    }

    @Test
    void upgradesLegacyArrayAndPreservesUnknownVersionAndExistingBackup(@TempDir Path dir)
            throws Exception {
        Path json = dir.resolve("store.json");
        Files.writeString(json, "[{\"path\":\"old.txt\",\"fileName\":\"old.txt\","
                + "\"fileSize\":3,\"fileHash\":\"abc\"}]");
        SharedFilesStore store = new SharedFilesStore(json);
        assertEquals(1, store.load().size());
        store.add(new SharedFilesStore.Entry("new.txt", "new.txt", 3, "def"));
        assertTrue(Files.readString(json).stripLeading().startsWith("["),
                "Writes keep the legacy array shape for compatibility with older app versions");

        Files.writeString(json, "{\"schema_version\":99,\"entries\":[]}");
        Path backup = json.resolveSibling("store.json.bak");
        Files.writeString(backup, "preserve this backup");
        String original = Files.readString(json);
        assertThrows(IllegalStateException.class, store::load);
        assertThrows(java.io.IOException.class,
                () -> store.add(new SharedFilesStore.Entry("other", "other", 1, "ghi")));
        assertEquals(original, Files.readString(json));
        assertEquals("preserve this backup", Files.readString(backup));
    }
}