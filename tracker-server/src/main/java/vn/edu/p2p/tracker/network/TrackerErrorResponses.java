package vn.edu.p2p.tracker.network;

import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;

final class TrackerErrorResponses {
    private TrackerErrorResponses() {
    }

    static MessageEnvelope forRequest(
            MessageEnvelope request,
            String errorCode,
            String message
    ) {
        if (request == null) {
            return protocolError("unknown", errorCode, message);
        }

        String requestId = request.requestId() == null ? "unknown" : request.requestId();
        MessageType responseType = responseTypeFor(request.type());

        return MessageEnvelope.error(
                responseType,
                requestId,
                errorCode,
                message
        );
    }

    static MessageEnvelope protocolError(
            String requestId,
            String errorCode,
            String message
    ) {
        return MessageEnvelope.error(
                MessageType.ERROR,
                requestId == null ? "unknown" : requestId,
                errorCode,
                message
        );
    }

    private static MessageType responseTypeFor(MessageType requestType) {
        if (requestType == null) {
            return MessageType.ERROR;
        }

        return switch (requestType) {
            case LOGIN_REQUEST -> MessageType.LOGIN_RESPONSE;
            case LOGOUT_REQUEST -> MessageType.LOGOUT_RESPONSE;
            case HEARTBEAT -> MessageType.HEARTBEAT_ACK;
            case FILE_SOURCES_REQUEST -> MessageType.FILE_SOURCES_RESPONSE;
            case SHARE_FILE_REQUEST -> MessageType.SHARE_FILE_RESPONSE;
            case FILE_PIECES_PAGE_REQUEST -> MessageType.FILE_PIECES_PAGE_RESPONSE;
            case SEARCH_REQUEST -> MessageType.SEARCH_RESPONSE;
            case UNSHARE_FILE_REQUEST -> MessageType.UNSHARE_FILE_RESPONSE;
            case GET_BITFIELD_REQUEST -> MessageType.BITFIELD_RESPONSE;
            case REQUEST_PIECE -> MessageType.PIECE_RESPONSE;
            case RECORD_TRANSFER_REQUEST -> MessageType.RECORD_TRANSFER_RESPONSE;
            case TRANSFER_HISTORY_REQUEST -> MessageType.TRANSFER_HISTORY_RESPONSE;
            case FOLDER_SHARE_REQUEST -> MessageType.FOLDER_SHARE_RESPONSE;
            case FOLDER_SEARCH_REQUEST -> MessageType.FOLDER_SEARCH_RESPONSE;
            case FOLDER_DETAIL_REQUEST -> MessageType.FOLDER_DETAIL_RESPONSE;
            default -> MessageType.ERROR;
        };
    }
}
