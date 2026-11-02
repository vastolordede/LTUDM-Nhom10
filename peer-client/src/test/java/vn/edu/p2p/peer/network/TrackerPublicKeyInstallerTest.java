package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class TrackerPublicKeyInstallerTest {
    @Test void verifiesAndNeverOverwritesAnotherKey(@TempDir Path dir) throws Exception {
        var original = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicB64 = Base64.getEncoder().encodeToString(original.getPublic().getEncoded());
        Path source = dir.resolve("operator.key.b64");
        Path destination = dir.resolve("peer/tracker-public.key.b64");
        Files.writeString(source, publicB64);
        assertThrows(java.io.IOException.class, () ->
                TrackerPublicKeyInstaller.install(source, destination, "wrong fingerprint"));
        TrackerPublicKeyInstaller.install(source, destination,
                TrackerPublicKeyInstaller.fingerprint(publicB64));
        assertEquals(publicB64, Files.readString(destination).trim());
        var second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String changed = Base64.getEncoder().encodeToString(second.getPublic().getEncoded());
        Files.writeString(source, changed);
        assertThrows(java.io.IOException.class, () ->
                TrackerPublicKeyInstaller.install(source, destination,
                        TrackerPublicKeyInstaller.fingerprint(changed)));
    }
}
