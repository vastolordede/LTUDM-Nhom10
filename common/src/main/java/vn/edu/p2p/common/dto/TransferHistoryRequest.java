package vn.edu.p2p.common.dto;

/** Zero-based history page, fixed 100 rows. */
public record TransferHistoryRequest(String sessionId, int page) {
    public TransferHistoryRequest(String sessionId) { this(sessionId, 0); }
}
