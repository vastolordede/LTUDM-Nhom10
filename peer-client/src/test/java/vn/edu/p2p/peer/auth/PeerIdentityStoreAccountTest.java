package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.io.OutputStream;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** R41: two accounts on one PC must not be forced to delete peer.properties. */
class PeerIdentityStoreAccountTest {
    @Test
    void oldAccountKeepsPeerIdAndAnotherAccountCanRotate(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("peer.properties");
        PeerIdentityStore store = new PeerIdentityStore(local);
        String legacy = store.loadOrCreate().peerId();
        assertEquals(legacy, store.loadOrCreateForAccount("Alice").peerId());
        assertEquals(legacy, store.loadOrCreateForAccount("Bob").peerId());
        String bob = store.rotateForAccount("bob").peerId();
        assertNotEquals(legacy, bob);
        PeerIdentityStore restart = new PeerIdentityStore(local);
        assertEquals(legacy, restart.loadOrCreateForAccount("ALICE").peerId());
        assertEquals(bob, restart.loadOrCreateForAccount("Bob").peerId());
        assertEquals(legacy, restart.loadOrCreate().peerId());
        Properties stored = new Properties();
        try (var input = Files.newInputStream(local)) { stored.load(input); }
        assertEquals("2", stored.getProperty("schema_version"));
    }

    @Test
    void readsUnversionedIdentityAndBacksUpUnknownVersion(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("peer.properties");
        String oldPeerId = UUID.randomUUID().toString();
        Files.writeString(file, "peer.id=" + oldPeerId + System.lineSeparator()
                + "device.name=Old device" + System.lineSeparator());
        assertEquals(oldPeerId, new PeerIdentityStore(file).loadOrCreate().peerId());

        Properties old = new Properties();
        try (var input = Files.newInputStream(file)) { old.load(input); }
        old.setProperty("schema_version", "99");
        try (OutputStream output = Files.newOutputStream(file)) {
            old.store(output, "future fixture");
        }
        String original = Files.readString(file);
        java.io.IOException error = assertThrows(java.io.IOException.class,
                () -> new PeerIdentityStore(file).loadOrCreate());
        assertTrue(error.getMessage().contains("Dữ liệu Peer ID"));
        assertTrue(Files.exists(file.resolveSibling("peer.properties.bak")));
        assertEquals(original, Files.readString(file));
    }
}
