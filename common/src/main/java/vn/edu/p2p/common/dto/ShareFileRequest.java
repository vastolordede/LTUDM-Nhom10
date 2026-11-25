package vn.edu.p2p.common.dto;

/** folderOnly marks an announcement for a folder, not an independent share.
 * restore may only restore an already-active share, never re-enable a revoked one. */
public record ShareFileRequest(String sessionId, FileMetadata metadata,
                               boolean folderOnly, boolean restore) {
    public ShareFileRequest(String sessionId, FileMetadata metadata) {
        this(sessionId, metadata, false, false);
    }
}
