package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class UserTrackerSettingsStoreTest {
    @TempDir Path temp;

    @Test
    void persistsEndpointWithoutSecretsAndRejectsBadHosts() throws Exception {
        var store = new UserTrackerSettingsStore(temp.resolve("state/tracker.properties"));
        var defaults = PeerSettings.load();
        var input = new UserTrackerSettingsStore.Endpoint("localhost", 9123, false, "");
        assertFalse(store.hasSavedSettings());
        store.save(input);
        assertTrue(store.hasSavedSettings());
        assertEquals(input, store.load(defaults));
        String saved = java.nio.file.Files.readString(temp.resolve("state/tracker.properties"));
        assertFalse(saved.contains("password"));
        assertThrows(IllegalArgumentException.class,
                () -> new UserTrackerSettingsStore.Endpoint("http://bad", 5000, false, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new UserTrackerSettingsStore.Endpoint("localhost", 65536, false, ""));
    }
}
