package vn.edu.p2p.common.dto;

public record TicketRevocation(
        long sequence,
        Type type,
        String requesterPeerId,
        String uploaderPeerId,
        long fileId,
        long revokedBeforeEpochMillis
) {
    public enum Type {
        REQUESTER,
        FILE
    }
}
