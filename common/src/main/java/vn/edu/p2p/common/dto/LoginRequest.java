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
        String peerPublicKey,
        Long peerProofTimestamp,
        String peerProofNonce,
        String peerProofSignature
) {
    /** Compatibility for development fixtures; production logins require a device proof. */
    public LoginRequest(String username, String password, int listeningPort, String peerId,
                        String deviceName, String peerPublicKey) {
        this(username, password, listeningPort, peerId, deviceName, peerPublicKey,
                null, null, null);
    }
}
