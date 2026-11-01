package vn.edu.p2p.common.dto;

import java.util.List;

/** Tracker verifies each child file transfer was COMPLETED by the same peer. */
public record RecordFolderDownloadRequest(String sessionId, String groupTransferId,
                                          long groupId, List<String> fileTransferIds) {
    public RecordFolderDownloadRequest {
        fileTransferIds = fileTransferIds == null ? List.of() : List.copyOf(fileTransferIds);
    }
    public RecordFolderDownloadRequest(String sessionId, String groupTransferId, long groupId) {
        this(sessionId, groupTransferId, groupId, List.of());
    }
}
