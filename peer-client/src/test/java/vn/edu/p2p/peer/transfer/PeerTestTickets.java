package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;

final class PeerTestTickets {
    private PeerTestTickets() {}

    static String issue(PrivateKey trackerKey, long fileId, String requesterId,
                        String uploaderId, Instant now) {
        return PeerAccessTickets.issue(trackerKey, fileId, requesterId, uploaderId,
                PeerRequestProof.generateKeyPair().getPublic(), now);
    }

    static boolean verify(PublicKey trackerKey, String ticket, long fileId,
                          String uploaderId, Instant now) {
        return PeerAccessTickets.verifyClaims(trackerKey, ticket, fileId, uploaderId, now) != null;
    }
}
