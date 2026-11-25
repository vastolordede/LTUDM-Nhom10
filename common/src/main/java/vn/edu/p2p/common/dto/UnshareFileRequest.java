package vn.edu.p2p.common.dto;

/** onlyUnreferenced is cleanup of a failed folder publication; explicit stop is false. */
public record UnshareFileRequest(String sessionId, long fileId, boolean onlyUnreferenced) {
    public UnshareFileRequest(String sessionId, long fileId) { this(sessionId, fileId, false); }
}
