package vn.edu.p2p.common.dto;

public record HeartbeatRequest(
        String sessionId,
        long lastRevocationSeq
) {
    public HeartbeatRequest(String sessionId) {
        this(sessionId, 0L);
    }

    public HeartbeatRequest {
        if (lastRevocationSeq < 0) lastRevocationSeq = 0L;
    }
}
