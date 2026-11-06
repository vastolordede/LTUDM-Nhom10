package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Properties;
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
        assertTrue(saved.contains("schema_version=2"));
        assertFalse(saved.contains("password"));
        assertThrows(IllegalArgumentException.class,
                () -> new UserTrackerSettingsStore.Endpoint("http://bad", 5000, false, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new UserTrackerSettingsStore.Endpoint("localhost", 65536, false, ""));
    }

    @Test
    void upgradesLegacySettingsAndBacksUpUnknownVersionWithoutOverwriting(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("tracker.properties");
        Files.writeString(file, "tracker.host=localhost\ntracker.port=9123\n"
                + "tracker.tls=true\ntracker.truststore.path=C:\\\\trust\\\\peer.p12\n");
        UserTrackerSettingsStore store = new UserTrackerSettingsStore(file);
        assertTrue(store.load(PeerSettings.load()).tls());
        assertTrue(Files.readString(file).contains("schema_version=2"));

        Properties future = new Properties();
        try (var input = Files.newInputStream(file)) { future.load(input); }
        future.setProperty("schema_version", "99");
        try (var output = Files.newOutputStream(file)) { future.store(output, "future"); }
        String original = Files.readString(file);
        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> store.save(new UserTrackerSettingsStore.Endpoint("localhost", 9123, false, "")));
        assertTrue(error.getMessage().contains("Cấu hình Tracker"));
        assertEquals(original, Files.readString(file));
        assertTrue(Files.exists(file.resolveSibling("tracker.properties.bak")));
    }
}
