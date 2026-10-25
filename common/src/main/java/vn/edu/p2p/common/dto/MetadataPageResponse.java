package vn.edu.p2p.common.dto;
import java.util.List;
public record MetadataPageResponse(long fileId, int startIndex, List<PieceInfo> pieces) {
    public MetadataPageResponse { pieces = pieces == null ? List.of() : List.copyOf(pieces); }
}
