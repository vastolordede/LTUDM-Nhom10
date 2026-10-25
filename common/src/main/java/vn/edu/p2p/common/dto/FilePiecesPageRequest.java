package vn.edu.p2p.common.dto;
import java.util.List;
/** Page of SHA-256 hashes being stored on Tracker after sharing a large file. */
public record FilePiecesPageRequest(String sessionId, long fileId, List<PieceInfo> pieces) {
    public FilePiecesPageRequest { pieces = pieces == null ? List.of() : List.copyOf(pieces); }
}
