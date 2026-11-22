package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PeerLoginProofTest {
    @Test void signatureBindsAccountDeviceKeyAndPortWithinShortWindow() throws Exception {
        var peer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var clock = Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC);
        String id = UUID.randomUUID().toString();
        String key = PeerRequestProof.encodePublicKey(peer.getPublic());
        var proof = PeerLoginProof.sign(peer.getPrivate(), "alice", id, 7001, key, clock);
        assertTrue(PeerLoginProof.verify(peer.getPublic(), "alice", id, 7001, key, proof, clock));
        assertFalse(PeerLoginProof.verify(attacker.getPublic(), "alice", id, 7001, key, proof, clock));
        assertFalse(PeerLoginProof.verify(peer.getPublic(), "bob", id, 7001, key, proof, clock));
        assertFalse(PeerLoginProof.verify(peer.getPublic(), "alice", id, 7002, key, proof, clock));
        assertFalse(PeerLoginProof.verify(peer.getPublic(), "alice", UUID.randomUUID().toString(),
                7001, key, proof, clock));
        assertFalse(PeerLoginProof.verify(peer.getPublic(), "alice", id, 7001, key, proof,
                Clock.offset(clock, Duration.ofMinutes(5))));
    }
}
