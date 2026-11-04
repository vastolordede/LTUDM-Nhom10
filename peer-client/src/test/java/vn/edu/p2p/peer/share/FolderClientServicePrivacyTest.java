package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FolderDetailResponse;
import vn.edu.p2p.common.dto.FolderFileInfo;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.common.dto.UnshareFileResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 3, unit = TimeUnit.SECONDS)
class FolderClientServicePrivacyTest {
    @Test
    void folderUnshareUsesPerFileRequestsAndDropsLocalUploadEntries() throws Exception {
        List<Long> unsharedIds = new ArrayList<>();
        FolderClientService service = new FolderClientService(request -> {
            if (request.type() == MessageType.FOLDER_DETAIL_REQUEST) {
                return MessageEnvelope.success(MessageType.FOLDER_DETAIL_RESPONSE,
                        request.requestId(), ProtocolCodec.toPayload(new FolderDetailResponse(
                                9, "shared", List.of(
                                new FolderFileInfo(21, "docs/a.txt", 1, "a".repeat(64)),
                                new FolderFileInfo(21, "alternate/a.txt", 1, "a".repeat(64)),
                                new FolderFileInfo(22, "docs/b.txt", 1, "b".repeat(64))),
                                List.of())));
            }
            assertEquals(MessageType.UNSHARE_FILE_REQUEST, request.type());
            UnshareFileRequest payload = ProtocolCodec.fromPayload(
                    request.payload(), UnshareFileRequest.class);
            unsharedIds.add(payload.fileId());
            return MessageEnvelope.success(MessageType.UNSHARE_FILE_RESPONSE,
                    request.requestId(), ProtocolCodec.toPayload(
                            new UnshareFileResponse(payload.fileId(), true)));
        });
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(21, java.nio.file.Path.of("local-a.txt"), metadata("a".repeat(64)));
        registry.registerCompleteFile(22, java.nio.file.Path.of("local-b.txt"), metadata("b".repeat(64)));

        int count = service.unshareFolder("session", 9, registry);

        assertEquals(2, count);
        assertEquals(List.of(21L, 22L), unsharedIds);
        assertTrue(registry.find(21).isEmpty());
        assertTrue(registry.find(22).isEmpty());
    }

    private static FileMetadata metadata(String hash) {
        return new FileMetadata("file.txt", 1, hash, 1, 1,
                List.of(new vn.edu.p2p.common.dto.PieceInfo(0, 1, hash)));
    }
}
