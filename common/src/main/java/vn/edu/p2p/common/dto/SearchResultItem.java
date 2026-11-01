package vn.edu.p2p.common.dto;

/** Search row and verified transfer-history popularity (not peer source count). */
public record SearchResultItem(
        long fileId,
        String fileName,
        long fileSize,
        String fileHash,
        int sourceCount,
        long completedDownloads,
        long createdAtEpochMillis
) {
    /** Compatibility for older in-memory tests and clients. */
    public SearchResultItem(long fileId, String fileName, long fileSize,
                            String fileHash, int sourceCount) {
        this(fileId, fileName, fileSize, fileHash, sourceCount, 0L, 0L);
    }
}
