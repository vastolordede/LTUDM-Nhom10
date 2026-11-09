package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.FolderUnshareRequest;
import vn.edu.p2p.common.dto.FolderUnshareResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import static org.junit.jupiter.api.Assertions.*;

class FolderClientServicePrivacyTest {
    @Test void unshareFolderOnlyRequestsGroupRemoval() throws Exception {
        FolderClientService client = new FolderClientService(request -> {
            assertEquals(MessageType.FOLDER_UNSHARE_REQUEST, request.type());
            FolderUnshareRequest payload = ProtocolCodec.fromPayload(
                    request.payload(), FolderUnshareRequest.class);
            assertEquals(9L, payload.groupId());
            return MessageEnvelope.success(MessageType.FOLDER_UNSHARE_RESPONSE,
                    request.requestId(), ProtocolCodec.toPayload(
                            new FolderUnshareResponse(payload.groupId(), true)));
        });
        assertTrue(client.unshareFolder("session", 9).stopped());
    }
}
