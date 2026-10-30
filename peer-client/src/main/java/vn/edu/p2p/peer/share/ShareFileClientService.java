package vn.edu.p2p.peer.share;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FilePiecesPageRequest;
import vn.edu.p2p.common.dto.FilePiecesPageResponse;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.ShareFileRequest;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.common.dto.UnshareFileResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.peer.network.TrackerConnection;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Sends ShareFileRequest (T11) and UnshareFileRequest (T22) to the Tracker.
 * Mirrors AuthClientService's request/response pattern.
 */
public final class ShareFileClientService {

    private final TrackerConnection connection;

    public ShareFileClientService(TrackerConnection connection) {
        this.connection = connection;
    }

    public ShareFileResponse publish(String sessionId, FileMetadata metadata)
            throws ShareFileClientException {

        // Publish the compact header and then bounded SHA pages for large files.
        boolean paged = metadata.pieces().size() > 2000;
        FileMetadata advertised = paged
                ? new FileMetadata(metadata.fileName(), metadata.fileSize(), metadata.fileHash(),
                        metadata.pieceSize(), metadata.pieceCount(), List.of())
                : metadata;
        ShareFileRequest payload = new ShareFileRequest(sessionId, advertised);

        MessageEnvelope request = MessageEnvelope.request(
                MessageType.SHARE_FILE_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        try {
            MessageEnvelope response = connection.request(request);

            if (response.status() == ResponseStatus.ERROR) {
                throw new ShareFileClientException(errorText(response));
            }
            if (response.type() != MessageType.SHARE_FILE_RESPONSE) {
                throw new ShareFileClientException(
                        "Unexpected response type from Tracker: " + response.type()
                );
            }

            ShareFileResponse published = ProtocolCodec.fromPayload(response.payload(), ShareFileResponse.class);
            if (paged && published.isNewFile()) {
                try {
                    for (int start = 0; start < metadata.pieces().size(); start += 400) {
                        List<PieceInfo> batch = metadata.pieces().subList(start,
                                Math.min(start + 400, metadata.pieces().size()));
                        MessageEnvelope pageRequest = MessageEnvelope.request(
                                MessageType.FILE_PIECES_PAGE_REQUEST, UUID.randomUUID().toString(),
                                ProtocolCodec.toPayload(new FilePiecesPageRequest(sessionId, published.fileId(), batch))
                        );
                        MessageEnvelope pageResponse = connection.request(pageRequest);
                        if (pageResponse.status() == ResponseStatus.ERROR
                                || pageResponse.type() != MessageType.FILE_PIECES_PAGE_RESPONSE) {
                            throw new IOException("Tracker rejected metadata page starting at " + start
                                    + ": " + errorText(pageResponse));
                        }
                        FilePiecesPageResponse ack = ProtocolCodec.fromPayload(
                                pageResponse.payload(), FilePiecesPageResponse.class);
                        if (ack == null || ack.fileId() != published.fileId()
                                || ack.acceptedCount() != batch.size()) {
                            throw new IOException("Incorrect metadata page acknowledgement");
                        }
                    }
                } catch (IOException pageError) {
                    try { unshare(sessionId, published.fileId()); }
                    catch (ShareFileClientException ignored) { }
                    throw pageError;
                }
            }
            return published;
        } catch (IOException e) {
            throw new ShareFileClientException(
                    "Network error while sharing file: " + e.getMessage(), e
            );
        }
    }

    /** T22: stop sharing a file this peer previously published. */
    public UnshareFileResponse unshare(String sessionId, long fileId) throws ShareFileClientException {
        UnshareFileRequest payload = new UnshareFileRequest(sessionId, fileId);

        MessageEnvelope request = MessageEnvelope.request(
                MessageType.UNSHARE_FILE_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        try {
            MessageEnvelope response = connection.request(request);

            if (response.status() == ResponseStatus.ERROR) {
                throw new ShareFileClientException(errorText(response));
            }
            if (response.type() != MessageType.UNSHARE_FILE_RESPONSE) {
                throw new ShareFileClientException(
                        "Unexpected response type from Tracker: " + response.type()
                );
            }

            return ProtocolCodec.fromPayload(response.payload(), UnshareFileResponse.class);
        } catch (IOException e) {
            throw new ShareFileClientException(
                    "Network error while unsharing file: " + e.getMessage(), e
            );
        }
    }

    private static String errorText(MessageEnvelope response) {
        return response.errorCode() != null
                ? response.errorCode() + ": " + response.message()
                : response.message();
    }
}
