package vn.edu.p2p.common.dto;

import java.util.List;

/** Preserves privacy: unavailable file paths are NOT returned, only their count. */
public record FolderDetailResponse(long groupId, String groupName,
                                   List<FolderFileInfo> files, List<String> directories,
                                   int totalFileCount, boolean complete) {
    public FolderDetailResponse {
        files = files == null ? List.of() : List.copyOf(files);
        directories = directories == null ? List.of() : List.copyOf(directories);
        if (totalFileCount < files.size()) throw new IllegalArgumentException("Invalid folder file count");
    }
    public FolderDetailResponse(long groupId, String groupName, List<FolderFileInfo> files,
                                List<String> directories) {
        this(groupId, groupName, files, directories, files == null ? 0 : files.size(), true);
    }
    public FolderDetailResponse(long groupId, String groupName, List<FolderFileInfo> files) {
        this(groupId, groupName, files, List.of());
    }
}
