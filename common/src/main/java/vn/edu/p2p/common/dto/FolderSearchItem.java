package vn.edu.p2p.common.dto;

/** Folder summary. completedDownloads counts only verified group completions. */
public record FolderSearchItem(
        long groupId, String groupName, int fileCount,
        long totalSize, int sourceCount,
        long completedDownloads, long createdAtEpochMillis
) {
    public FolderSearchItem(long groupId, String groupName, int fileCount,
                            long totalSize, int sourceCount) {
        this(groupId, groupName, fileCount, totalSize, sourceCount, 0L, 0L);
    }
}
