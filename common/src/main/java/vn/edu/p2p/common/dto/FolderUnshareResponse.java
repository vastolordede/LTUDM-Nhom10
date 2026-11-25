package vn.edu.p2p.common.dto;
import java.util.List;

public record FolderUnshareResponse(long groupId, boolean stopped, List<Long> stoppedFileIds) {
    public FolderUnshareResponse { stoppedFileIds = stoppedFileIds == null ? List.of() : List.copyOf(stoppedFileIds); }
    public FolderUnshareResponse(long groupId, boolean stopped) { this(groupId, stopped, List.of()); }
}
