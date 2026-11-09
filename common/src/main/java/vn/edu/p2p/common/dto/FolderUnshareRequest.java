package vn.edu.p2p.common.dto;

/** Remove one folder grouping without stopping independently shared file IDs. */
public record FolderUnshareRequest(String sessionId, long groupId) { }
