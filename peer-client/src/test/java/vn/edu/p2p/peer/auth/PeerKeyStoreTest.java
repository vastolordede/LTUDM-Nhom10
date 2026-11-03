package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerKeyStoreTest {
    @TempDir
    Path directory;

    @Test
    void createsAndReloadsKeyForPeerIdAndCreatesFreshKeyForNewPeerId() throws Exception {
        PeerKeyStore store = new PeerKeyStore(directory);
        String firstPeerId = UUID.randomUUID().toString();
        String secondPeerId = UUID.randomUUID().toString();

        KeyPair first = store.loadOrCreate(firstPeerId);
        KeyPair reloaded = store.loadOrCreate(firstPeerId);
        KeyPair rotated = store.loadOrCreate(secondPeerId);

        assertEquals(first.getPublic(), reloaded.getPublic());
        assertEquals(first.getPrivate(), reloaded.getPrivate());
        assertNotEquals(first.getPublic(), rotated.getPublic());
        assertTrue(Files.exists(directory.resolve("peer-" + firstPeerId + ".key")));
        assertEquals(PeerRequestProof.encodePublicKey(first.getPublic()),
                store.encodePublicKey(first));
    }

    @Test
    void reportsCorruptStoredKeyFileAsClearIOException() throws Exception {
        String peerId = UUID.randomUUID().toString();
        Files.writeString(directory.resolve("peer-" + peerId + ".key"), "not=properties-key");
        PeerKeyStore store = new PeerKeyStore(directory);

        IOException error = assertThrows(IOException.class, () -> store.loadOrCreate(peerId));

        assertTrue(error.getMessage().contains("Stored Peer key file is invalid"));
    }
}
