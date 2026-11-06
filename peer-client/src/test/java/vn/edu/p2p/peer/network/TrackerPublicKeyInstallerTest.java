package vn.edu.p2p.peer.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class TrackerPublicKeyInstallerTest {
    @Test void verifiesPinsAndRequiresExplicitReplacementConfirmation(@TempDir Path dir)
            throws Exception {
        var original = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicB64 = Base64.getEncoder().encodeToString(original.getPublic().getEncoded());
        Path source = dir.resolve("operator.key.b64");
        Path destination = dir.resolve("peer/tracker-public.key.b64");
        Files.writeString(source, publicB64);
        assertThrows(java.io.IOException.class, () ->
                TrackerPublicKeyInstaller.install(source, destination, "wrong fingerprint"));
        String originalFingerprint = TrackerPublicKeyInstaller.fingerprint(publicB64);
        assertEquals(16, originalFingerprint.split(" ").length);
        TrackerPublicKeyInstaller.install(source, destination, originalFingerprint);
        assertEquals(publicB64, Files.readString(destination).trim());
        Properties storedTrust = new Properties();
        try (var input = Files.newInputStream(destination.resolveSibling(
                "tracker-public-key.properties"))) {
            storedTrust.load(input);
        }
        assertEquals("2", storedTrust.getProperty("schema_version"));
        assertEquals(vn.edu.p2p.common.security.PeerAccessTickets.fingerprint(original.getPublic()),
                storedTrust.getProperty(
                        vn.edu.p2p.common.security.PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY));
        assertEquals(original.getPublic(), vn.edu.p2p.common.security.PeerAccessTickets
                .loadPinnedPublicKey(destination,
                        destination.resolveSibling("tracker-public-key.properties")));

        var second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String changed = Base64.getEncoder().encodeToString(second.getPublic().getEncoded());
        Files.writeString(source, changed);
        String changedFingerprint = TrackerPublicKeyInstaller.fingerprint(changed);
        assertThrows(TrackerPublicKeyInstaller.ReplacementConfirmationRequiredException.class, () ->
                TrackerPublicKeyInstaller.install(source, destination,
                        changedFingerprint));
        assertThrows(TrackerPublicKeyInstaller.ReplacementConfirmationRequiredException.class, () ->
                TrackerPublicKeyInstaller.install(source, destination, changedFingerprint,
                        "wrong fingerprint"));
        TrackerPublicKeyInstaller.install(source, destination, changedFingerprint,
                changedFingerprint);
        assertEquals(second.getPublic(), vn.edu.p2p.common.security.PeerAccessTickets
                .loadPinnedPublicKey(destination,
                        destination.resolveSibling("tracker-public-key.properties")));
    }

    @Test
    void rejectsEmptyMalformedAndPrivateKeyFiles(@TempDir Path dir) throws Exception {
        var trusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String fingerprint = TrackerPublicKeyInstaller.fingerprint(
                Base64.getEncoder().encodeToString(trusted.getPublic().getEncoded()));
        Path destination = dir.resolve("peer/tracker-public.key.b64");
        Path source = dir.resolve("invalid.key");
        String encodedPrivate = Base64.getEncoder().encodeToString(trusted.getPrivate().getEncoded());
        for (String contents : new String[] {
                "", "not-base64", "-----BEGIN " + "PRIVATE KEY-----", encodedPrivate
        }) {
            Files.writeString(source, contents);
            java.io.IOException error = assertThrows(java.io.IOException.class,
                    () -> TrackerPublicKeyInstaller.install(source, destination, fingerprint));
            assertTrue(error.getMessage().contains("Tệp"));
        }
        assertFalse(Files.exists(destination));
    }

    @Test
    void requiresConfirmationWhenPinnedKeyFileWasRemoved(@TempDir Path dir) throws Exception {
        var oldKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var newKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path source = dir.resolve("operator.key.b64");
        Path destination = dir.resolve("peer/tracker-public.key.b64");
        Files.writeString(source, Base64.getEncoder().encodeToString(oldKey.getPublic().getEncoded()));
        String oldFingerprint = TrackerPublicKeyInstaller.fingerprint(Files.readString(source));
        TrackerPublicKeyInstaller.install(source, destination, oldFingerprint);

        Files.delete(destination);
        Files.writeString(source, Base64.getEncoder().encodeToString(newKey.getPublic().getEncoded()));
        String newFingerprint = TrackerPublicKeyInstaller.fingerprint(Files.readString(source));
        assertThrows(TrackerPublicKeyInstaller.ReplacementConfirmationRequiredException.class,
                () -> TrackerPublicKeyInstaller.install(source, destination, newFingerprint));
        TrackerPublicKeyInstaller.install(source, destination, newFingerprint, newFingerprint);
        assertEquals(newKey.getPublic(), vn.edu.p2p.common.security.PeerAccessTickets
                .loadPinnedPublicKey(destination,
                        destination.resolveSibling("tracker-public-key.properties")));
    }

    @Test
    void backsUpUnknownPinVersionAndCorruptPublicKeyWithoutReplacingEither(@TempDir Path dir)
            throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String encoded = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
        Path source = dir.resolve("operator.key");
        Path destination = dir.resolve("peer/tracker-public.key.b64");
        Files.writeString(source, encoded);
        String fingerprint = TrackerPublicKeyInstaller.fingerprint(encoded);
        TrackerPublicKeyInstaller.install(source, destination, fingerprint);

        Path trust = destination.resolveSibling("tracker-public-key.properties");
        Properties future = new Properties();
        try (var input = Files.newInputStream(trust)) { future.load(input); }
        future.setProperty("schema_version", "99");
        try (var output = Files.newOutputStream(trust)) { future.store(output, "future"); }
        String originalTrust = Files.readString(trust);
        assertThrows(java.io.IOException.class,
                () -> TrackerPublicKeyInstaller.install(source, destination, fingerprint));
        assertEquals(originalTrust, Files.readString(trust));
        assertTrue(Files.exists(trust.resolveSibling(trust.getFileName() + ".bak")));

        Files.writeString(trust, "schema_version=2\n"
                + vn.edu.p2p.common.security.PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY
                + "=" + vn.edu.p2p.common.security.PeerAccessTickets.fingerprint(key.getPublic())
                + "\n");
        Files.writeString(destination, "damaged");
        assertThrows(java.io.IOException.class,
                () -> TrackerPublicKeyInstaller.install(source, destination, fingerprint));
        assertTrue(Files.exists(destination.resolveSibling(destination.getFileName() + ".bak")));
        assertEquals("damaged", Files.readString(destination));
    }

    @Test
    void migratesExistingPinOnStartupWithoutReinstallingKey(@TempDir Path dir) throws Exception {
        var trusted = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = dir.resolve("tracker-public.key.b64");
        String encoded = Base64.getEncoder().encodeToString(trusted.getPublic().getEncoded());
        Files.writeString(keyFile, encoded);
        Path settings = dir.resolve("tracker-public-key.properties");
        Properties legacy = new Properties();
        legacy.setProperty(vn.edu.p2p.common.security.PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY,
                vn.edu.p2p.common.security.PeerAccessTickets.fingerprint(trusted.getPublic()));
        try (var output = Files.newOutputStream(settings)) { legacy.store(output, "old pin"); }

        TrackerPublicKeyInstaller.migratePinSettings(keyFile);

        Properties migrated = new Properties();
        try (var input = Files.newInputStream(settings)) { migrated.load(input); }
        assertEquals("2", migrated.getProperty("schema_version"));
        assertEquals(encoded, Files.readString(keyFile));
    }
}
