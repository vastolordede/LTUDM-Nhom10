package vn.edu.p2p.common.dto;

/**
 * Network endpoint and availability advertised by one online source Peer.
 */
public record FileSourceInfo(
        String peerId,
        String availabilityStatus,
        String ipAddress,
        int listeningPort,
        long lastSeenEpochMillis,
        String accessTicket
) {
    public FileSourceInfo(String peerId, String availabilityStatus, String ipAddress,
                          int listeningPort, long lastSeenEpochMillis) {
        this(peerId, availabilityStatus, ipAddress, listeningPort, lastSeenEpochMillis, null);
    }

}
