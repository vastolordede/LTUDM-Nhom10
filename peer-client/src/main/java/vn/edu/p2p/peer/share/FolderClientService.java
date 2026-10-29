package vn.edu.p2p.peer.share;

import com.fasterxml.jackson.databind.JsonNode;
import vn.edu.p2p.common.dto.FolderDetailRequest;
import vn.edu.p2p.common.dto.FolderDetailResponse;
import vn.edu.p2p.common.dto.FolderEntryDto;
import vn.edu.p2p.common.dto.FolderSearchRequest;
import vn.edu.p2p.common.dto.FolderSearchResponse;
import vn.edu.p2p.common.dto.FolderShareRequest;
import vn.edu.p2p.common.dto.FolderShareResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.peer.network.TrackerConnection;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public final class FolderClientService {
    private final TrackerConnection connection;

    public FolderClientService(TrackerConnection connection) {
        this.connection = connection;
    }

    public FolderShareResponse share(String sessionId, String groupName, List<FolderEntryDto> entries)
            throws ShareFileClientException {
        return call(MessageType.FOLDER_SHARE_REQUEST, MessageType.FOLDER_SHARE_RESPONSE,
                ProtocolCodec.toPayload(new FolderShareRequest(sessionId, groupName, entries)),
                FolderShareResponse.class);
    }

    public FolderSearchResponse search(String sessionId, String query) throws ShareFileClientException {
        return call(MessageType.FOLDER_SEARCH_REQUEST, MessageType.FOLDER_SEARCH_RESPONSE,
                ProtocolCodec.toPayload(new FolderSearchRequest(sessionId, query)),
                FolderSearchResponse.class);
    }

    public FolderDetailResponse detail(String sessionId, long groupId) throws ShareFileClientException {
        return call(MessageType.FOLDER_DETAIL_REQUEST, MessageType.FOLDER_DETAIL_RESPONSE,
                ProtocolCodec.toPayload(new FolderDetailRequest(sessionId, groupId)),
                FolderDetailResponse.class);
    }

    private <T> T call(MessageType reqType, MessageType respType, JsonNode payload, Class<T> cls)
            throws ShareFileClientException {
        MessageEnvelope request = MessageEnvelope.request(reqType, UUID.randomUUID().toString(), payload);
        try {
            MessageEnvelope response = connection.request(request);
            if (response.status() == ResponseStatus.ERROR) {
                throw new ShareFileClientException(response.errorCode() + ": " + response.message());
            }
            if (response.type() != respType) {
                throw new ShareFileClientException("Unexpected response type: " + response.type());
            }
            return ProtocolCodec.fromPayload(response.payload(), cls);
        } catch (IOException e) {
            throw new ShareFileClientException("Network error: " + e.getMessage(), e);
        }
    }
}