package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
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
    }
}
