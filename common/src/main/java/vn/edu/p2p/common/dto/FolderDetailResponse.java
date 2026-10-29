package vn.edu.p2p.common.dto;
import java.util.List;
public record FolderDetailResponse(long groupId, String groupName, List<FolderFileInfo> files) {}