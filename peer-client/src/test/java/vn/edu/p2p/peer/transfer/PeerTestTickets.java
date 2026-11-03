package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.KeyPair;
import java.time.Instant;

final class PeerTestTickets {
    private static final KeyPair REQUESTER_KEYS = PeerRequestProof.generateKeyPair();

    private PeerTestTickets() {}

    static String issue(PrivateKey trackerKey, long fileId, String requesterId,
                        String uploaderId, Instant now) {
        return PeerAccessTickets.issue(trackerKey, fileId, requesterId, uploaderId,
                REQUESTER_KEYS.getPublic(), now);
    }

    static PrivateKey requesterPrivateKey() {
        return REQUESTER_KEYS.getPrivate();
    }

    static PublicKey requesterPublicKey() {
        return REQUESTER_KEYS.getPublic();
    }

    static boolean verify(PublicKey trackerKey, String ticket, long fileId,
                          String uploaderId, Instant now) {
        return PeerAccessTickets.verifyClaims(trackerKey, ticket, fileId, uploaderId, now) != null;
    }
}
