package vn.edu.p2p.common.dto;

/** Completion report for a whole-folder download (not a single file). */
public record RecordFolderDownloadRequest(String sessionId, String groupTransferId, long groupId) {}
