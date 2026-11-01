package vn.edu.p2p.common.dto;

import java.util.List;

public record FolderDetailResponse(long groupId, String groupName,
                                   List<FolderFileInfo> files,
                                   List<String> directories) {
    public FolderDetailResponse {
        files = files == null ? List.of() : List.copyOf(files);
        directories = directories == null ? List.of() : List.copyOf(directories);
    }
    public FolderDetailResponse(long groupId, String groupName, List<FolderFileInfo> files) {
        this(groupId, groupName, files, List.of());
    }
}
