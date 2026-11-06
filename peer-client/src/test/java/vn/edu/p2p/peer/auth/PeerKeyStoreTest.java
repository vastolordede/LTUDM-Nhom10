package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;
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
        Path keyFile = directory.resolve("peer-" + peerId + ".key");
        Files.writeString(keyFile, "not=properties-key");
        PeerKeyStore store = new PeerKeyStore(directory);

        IOException error = assertThrows(IOException.class, () -> store.loadOrCreate(peerId));

        assertTrue(error.getMessage().contains("Tệp khóa Peer"));
        assertTrue(Files.exists(keyFile.resolveSibling(keyFile.getFileName() + ".bak")));
        assertEquals("not=properties-key", Files.readString(keyFile));
    }

    @Test
    void upgradesUnversionedKeyAndBacksUpUnknownVersion(@TempDir Path temp) throws Exception {
        String peerId = UUID.randomUUID().toString();
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = temp.resolve("peer-" + peerId + ".key");
        Properties old = new Properties();
        old.setProperty("private.pkcs8", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        old.setProperty("public.x509", PeerRequestProof.encodePublicKey(pair.getPublic()));
        try (var output = Files.newOutputStream(keyFile)) { old.store(output, "old key"); }

        assertEquals(pair.getPublic(), new PeerKeyStore(temp).loadOrCreate(peerId).getPublic());
        Properties upgraded = new Properties();
        try (var input = Files.newInputStream(keyFile)) { upgraded.load(input); }
        assertEquals("2", upgraded.getProperty("schema_version"));

        upgraded.setProperty("schema_version", "99");
        try (var output = Files.newOutputStream(keyFile)) { upgraded.store(output, "future key"); }
        assertThrows(IOException.class, () -> new PeerKeyStore(temp).loadOrCreate(peerId));
        assertTrue(Files.exists(keyFile.resolveSibling(keyFile.getFileName() + ".bak")));
    }
}
