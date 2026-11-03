package vn.edu.p2p.common.dto;

import java.util.List;

public record HeartbeatResponse(
        long serverTimeEpochMillis,
        List<TicketRevocation> revocations,
        Long latestRevocationSeq,
        String trackerInstanceId
) {
    public HeartbeatResponse(long serverTimeEpochMillis) {
        this(serverTimeEpochMillis, List.of(), 0L, null);
    }

    public HeartbeatResponse(long serverTimeEpochMillis, List<TicketRevocation> revocations,
                             Long latestRevocationSeq) {
        this(serverTimeEpochMillis, revocations, latestRevocationSeq, null);
    }

    public HeartbeatResponse {
        revocations = revocations == null ? List.of() : List.copyOf(revocations);
        if (latestRevocationSeq != null && latestRevocationSeq < 0) {
            latestRevocationSeq = 0L;
        }
    }
}
