package vn.edu.p2p.common.dto;
import java.util.List;
public record FolderShareRequest(String sessionId, String groupName, List<FolderEntryDto> entries) {}