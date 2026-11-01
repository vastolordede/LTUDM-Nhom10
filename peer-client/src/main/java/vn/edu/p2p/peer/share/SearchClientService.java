package vn.edu.p2p.peer.share;

import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.SearchRequest;
import vn.edu.p2p.common.dto.SearchResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.peer.network.TrackerConnection;

import java.io.IOException;
import java.util.UUID;

/** Sends a SEARCH_REQUEST to the Tracker and parses the SearchResponse (T16-T17). */
public final class SearchClientService {

    private final TrackerConnection connection;

    public SearchClientService(TrackerConnection connection) {
        this.connection = connection;
    }

    public SearchResponse search(String sessionId, String query) throws ShareFileClientException {
        return search(sessionId, query, 0, 50);
    }

    public SearchResponse search(String sessionId, String query, int page, int pageSize)
            throws ShareFileClientException {
        return search(sessionId, query, page, pageSize, DiscoverySort.POPULARITY);
    }

    public SearchResponse search(String sessionId, String query, int page, int pageSize,
                                 DiscoverySort sort) throws ShareFileClientException {
        SearchRequest payload = new SearchRequest(sessionId, query, page, pageSize, sort);

        MessageEnvelope request = MessageEnvelope.request(
                MessageType.SEARCH_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        try {
            MessageEnvelope response = connection.request(request);

            if (response.status() == ResponseStatus.ERROR) {
                throw new ShareFileClientException(errorText(response));
            }
            if (response.type() != MessageType.SEARCH_RESPONSE) {
                throw new ShareFileClientException("Unexpected response type: " + response.type());
            }

            return ProtocolCodec.fromPayload(response.payload(), SearchResponse.class);
        } catch (IOException e) {
            throw new ShareFileClientException("Network error while searching: " + e.getMessage(), e);
        }
    }

    private static String errorText(MessageEnvelope response) {
        return response.errorCode() != null
                ? response.errorCode() + ": " + response.message()
                : response.message();
    }
}
