package vn.edu.p2p.common.dto;

import java.util.List;

public record HeartbeatResponse(
        long serverTimeEpochMillis,
        List<TicketRevocation> revocations,
        Long latestRevocationSeq
) {
    public HeartbeatResponse(long serverTimeEpochMillis) {
        this(serverTimeEpochMillis, List.of(), 0L);
    }

    public HeartbeatResponse {
        revocations = revocations == null ? List.of() : List.copyOf(revocations);
        if (latestRevocationSeq != null && latestRevocationSeq < 0) {
            latestRevocationSeq = 0L;
        }
    }
}
