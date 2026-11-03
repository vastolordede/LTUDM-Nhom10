package vn.edu.p2p.common.dto;

/**
 * Login request sent from Peer to Tracker.
 * peerId is a stable UUID stored locally by the Peer client.
 */
public record LoginRequest(
        String username,
        String password,
        int listeningPort,
        String peerId,
        String deviceName,
        String peerPublicKey
) {
}
