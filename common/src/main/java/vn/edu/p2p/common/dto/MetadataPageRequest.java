package vn.edu.p2p.common.dto;

/** Peer-to-peer request for a bounded range of SHA-256 hashes with requester proof. */
public record MetadataPageRequest(
        long fileId,
        int startIndex,
        int pageSize,
        String accessTicket,
        Long proofTimestamp,
        String proofNonce,
        String proofSignature
) {
    public MetadataPageRequest(long fileId, int startIndex, int pageSize) {
        this(fileId, startIndex, pageSize, null, null, null, null);
    }

    public MetadataPageRequest(long fileId, int startIndex, int pageSize, String accessTicket) {
        this(fileId, startIndex, pageSize, accessTicket, null, null, null);
    }

    public MetadataPageRequest {
        if (fileId <= 0 || startIndex < 0 || pageSize < 1 || pageSize > 400) {
            throw new IllegalArgumentException("Invalid metadata page request bounds");
        }
    }
}
