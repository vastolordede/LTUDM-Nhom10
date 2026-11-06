package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.peer.network.PeerSettings;
import vn.edu.p2p.peer.network.TrackerPublicKeyInstaller;
import vn.edu.p2p.peer.network.UserTrackerSettingsStore;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.SharedFilesStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUpgradeStateIntegrationTest {
    @Test
    @Timeout(10)
    void preservesLegacyUserStateWhenApplicationDirectoryIsReplaced(@TempDir Path temp)
            throws Exception {
        Path userData = temp.resolve("profile/.fileshare-p2p");
        Files.createDirectories(userData);
        Path oldApp = temp.resolve("app-image");
        Files.createDirectories(oldApp);
        Files.writeString(oldApp.resolve("FileShare-P2P.exe"), "old app");

        String peerId = UUID.randomUUID().toString();
        Path identityFile = userData.resolve("peer.properties");
        Files.writeString(identityFile, "peer.id=" + peerId + "\ndevice.name=upgrade-test\n");

        KeyPair peerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path keyFile = userData.resolve("peer-" + peerId + ".key");
        Properties oldKey = new Properties();
        oldKey.setProperty("private.pkcs8",
                Base64.getEncoder().encodeToString(peerKey.getPrivate().getEncoded()));
        oldKey.setProperty("public.x509", PeerRequestProof.encodePublicKey(peerKey.getPublic()));
        try (var output = Files.newOutputStream(keyFile)) { oldKey.store(output, "old key"); }

        Path trackerSettings = userData.resolve("tracker-settings.properties");
        Files.writeString(trackerSettings, "tracker.host=localhost\ntracker.port=9123\n"
                + "tracker.tls=true\ntracker.truststore.path=C:\\\\trust\\\\peer.p12\n");

        KeyPair trackerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String trackerKeyText = Base64.getEncoder().encodeToString(trackerKey.getPublic().getEncoded());
        Path trackerKeyFile = userData.resolve("tracker-public.key.b64");
        Files.writeString(trackerKeyFile, trackerKeyText);
        Path trustSettings = userData.resolve("tracker-public-key.properties");
        Properties oldPin = new Properties();
        oldPin.setProperty(PeerAccessTickets.TRUSTED_FINGERPRINT_PROPERTY,
                PeerAccessTickets.fingerprint(trackerKey.getPublic()));
        try (var output = Files.newOutputStream(trustSettings)) {
            oldPin.store(output, "old fingerprint");
        }
        Path suppliedKey = temp.resolve("tracker-public.key.b64");
        Files.writeString(suppliedKey, trackerKeyText);

        Path sharedFile = temp.resolve("shared.txt");
        Files.writeString(sharedFile, "shared content");
        Path sharedStoreFile = userData.resolve("alice-9123-shared.json");
        Files.writeString(sharedStoreFile, "[{\"path\":\"" + sharedFile.toString().replace("\\", "\\\\")
                + "\",\"fileName\":\"shared.txt\",\"fileSize\":14,\"fileHash\":\""
                + FileHashUtil.sha256(sharedFile) + "\"}]");

        try (var paths = Files.walk(oldApp)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }

        assertEquals(peerId, new PeerIdentityStore(identityFile).loadOrCreate().peerId());
        assertEquals(peerKey.getPublic(),
                new PeerKeyStore(userData).loadOrCreate(peerId).getPublic());
        UserTrackerSettingsStore settingsStore = new UserTrackerSettingsStore(trackerSettings);
        var endpoint = settingsStore.load(PeerSettings.load());
        assertTrue(endpoint.tls());
        assertEquals(9123, endpoint.port());
        TrackerPublicKeyInstaller.install(suppliedKey, trackerKeyFile,
                TrackerPublicKeyInstaller.fingerprint(trackerKeyText));
        assertEquals("2", Files.readString(trustSettings).contains("schema_version=2") ? "2" : "");
        assertEquals(trackerKey.getPublic(),
                PeerAccessTickets.loadPinnedPublicKey(trackerKeyFile, trustSettings));
        SharedFilesStore shares = new SharedFilesStore(sharedStoreFile);
        assertEquals(1, shares.loadValid().size());
        shares.add(new SharedFilesStore.Entry(sharedFile.toString(), "shared.txt", 14,
                FileHashUtil.sha256(sharedFile)));
        assertTrue(Files.readString(sharedStoreFile).stripLeading().startsWith("["));
    }
}
