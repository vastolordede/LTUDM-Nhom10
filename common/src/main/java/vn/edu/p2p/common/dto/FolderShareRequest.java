package vn.edu.p2p.common.dto;

import java.util.List;

/** A folder may include empty subdirectories and even zero files. */
public record FolderShareRequest(String sessionId, String groupName,
                                 List<FolderEntryDto> entries,
                                 List<String> directories) {
    public FolderShareRequest {
        entries = entries == null ? List.of() : List.copyOf(entries);
        directories = directories == null ? List.of() : List.copyOf(directories);
    }
    public FolderShareRequest(String sessionId, String groupName, List<FolderEntryDto> entries) {
        this(sessionId, groupName, entries, List.of());
    }
}
