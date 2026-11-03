package vn.edu.p2p.common.dto;

public record HeartbeatRequest(
        String sessionId,
        long lastRevocationSeq,
        String lastTrackerInstanceId
) {
    public HeartbeatRequest(String sessionId) {
        this(sessionId, 0L, null);
    }

    public HeartbeatRequest(String sessionId, long lastRevocationSeq) {
        this(sessionId, lastRevocationSeq, null);
    }

    public HeartbeatRequest {
        if (lastRevocationSeq < 0) lastRevocationSeq = 0L;
        if (lastTrackerInstanceId != null && lastTrackerInstanceId.isBlank()) {
            lastTrackerInstanceId = null;
        }
    }
}
