package vn.edu.p2p.peer.share;

import com.fasterxml.jackson.databind.JsonNode;
import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.FolderDetailRequest;
import vn.edu.p2p.common.dto.FolderDetailResponse;
import vn.edu.p2p.common.dto.FolderEntryDto;
import vn.edu.p2p.common.dto.FolderSearchRequest;
import vn.edu.p2p.common.dto.FolderSearchResponse;
import vn.edu.p2p.common.dto.FolderShareRequest;
import vn.edu.p2p.common.dto.FolderShareResponse;
import vn.edu.p2p.common.dto.RecordFolderDownloadRequest;
import vn.edu.p2p.common.dto.RecordFolderDownloadResponse;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.common.dto.UnshareFileResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class FolderClientService {
    @FunctionalInterface
    interface RequestSender {
        MessageEnvelope request(MessageEnvelope request) throws IOException;
    }

    private final RequestSender requestSender;

    public FolderClientService(TrackerConnection connection) {
        this(connection::request);
    }

    FolderClientService(RequestSender requestSender) {
        this.requestSender = requestSender;
    }

    public FolderShareResponse share(String sessionId, String groupName, List<FolderEntryDto> entries)
            throws ShareFileClientException {
        return share(sessionId, groupName, entries, List.of());
    }

    public FolderShareResponse share(String sessionId, String groupName, List<FolderEntryDto> entries,
                                     List<String> directories) throws ShareFileClientException {
        return call(MessageType.FOLDER_SHARE_REQUEST, MessageType.FOLDER_SHARE_RESPONSE,
                ProtocolCodec.toPayload(new FolderShareRequest(sessionId, groupName, entries, directories)),
                FolderShareResponse.class);
    }

    public FolderSearchResponse search(String sessionId, String query) throws ShareFileClientException {
        return search(sessionId, query, 0, 50);
    }

    public FolderSearchResponse search(String sessionId, String query, int page, int pageSize)
            throws ShareFileClientException {
        return search(sessionId, query, page, pageSize, DiscoverySort.POPULARITY);
    }

    public FolderSearchResponse search(String sessionId, String query, int page, int pageSize,
                                       DiscoverySort sort) throws ShareFileClientException {
        return call(MessageType.FOLDER_SEARCH_REQUEST, MessageType.FOLDER_SEARCH_RESPONSE,
                ProtocolCodec.toPayload(new FolderSearchRequest(sessionId, query, page, pageSize, sort)),
                FolderSearchResponse.class);
    }

    public RecordFolderDownloadResponse reportCompletedDownload(
            String sessionId, UUID groupTransferId, long groupId,
            List<String> fileTransferIds) throws ShareFileClientException {
        return call(MessageType.FOLDER_DOWNLOAD_COMPLETED_REQUEST,
                MessageType.FOLDER_DOWNLOAD_COMPLETED_RESPONSE,
                ProtocolCodec.toPayload(new RecordFolderDownloadRequest(
                        sessionId, groupTransferId.toString(), groupId, fileTransferIds)),
                RecordFolderDownloadResponse.class);
    }

    public FolderDetailResponse detail(String sessionId, long groupId) throws ShareFileClientException {
        return call(MessageType.FOLDER_DETAIL_REQUEST, MessageType.FOLDER_DETAIL_RESPONSE,
                ProtocolCodec.toPayload(new FolderDetailRequest(sessionId, groupId)),
                FolderDetailResponse.class);
    }

    /** Stops this peer's share of each file currently listed in the folder. */
    public int unshareFolder(String sessionId, long groupId, LocalFileRegistry fileRegistry)
            throws ShareFileClientException {
        Objects.requireNonNull(fileRegistry, "fileRegistry");
        FolderDetailResponse folder = detail(sessionId, groupId);
        int unshared = 0;
        for (long fileId : folder.files().stream().mapToLong(file -> file.fileId()).distinct().toArray()) {
            unshareFile(sessionId, fileId);
            var registered = fileRegistry.find(fileId);
            if (registered.isPresent()) {
                try {
                    SharedFilesStore store = SharedFilesStore.current();
                    if (store != null) store.removeByHash(registered.get().metadata().fileHash());
                } catch (IOException e) {
                    throw new ShareFileClientException(
                            "File unshared, but local share state could not be removed.", e);
                } finally {
                    fileRegistry.unregisterFile(fileId);
                }
            }
            unshared++;
        }
        return unshared;
    }

    private void unshareFile(String sessionId, long fileId) throws ShareFileClientException {
        call(MessageType.UNSHARE_FILE_REQUEST, MessageType.UNSHARE_FILE_RESPONSE,
                ProtocolCodec.toPayload(new UnshareFileRequest(sessionId, fileId)),
                UnshareFileResponse.class);
    }

    private <T> T call(MessageType reqType, MessageType respType, JsonNode payload, Class<T> cls)
            throws ShareFileClientException {
        MessageEnvelope request = MessageEnvelope.request(reqType, UUID.randomUUID().toString(), payload);
        try {
            MessageEnvelope response = requestSender.request(request);
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