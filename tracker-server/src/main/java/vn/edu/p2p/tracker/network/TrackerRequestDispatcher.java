package vn.edu.p2p.tracker.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import vn.edu.p2p.common.dto.FilePiecesPageRequest;
import vn.edu.p2p.common.dto.FilePiecesPageResponse;
import vn.edu.p2p.common.dto.FileSourcesRequest;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.dto.RecordTransferRequest;
import vn.edu.p2p.common.dto.RecordTransferResponse;
import vn.edu.p2p.common.dto.SearchRequest;
import vn.edu.p2p.common.dto.SearchResponse;
import vn.edu.p2p.common.dto.ShareFileRequest;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.common.dto.TransferHistoryRequest;
import vn.edu.p2p.common.dto.TransferHistoryResponse;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.common.dto.UnshareFileResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.tracker.auth.AuthException;
import vn.edu.p2p.tracker.auth.AuthService;
import vn.edu.p2p.tracker.catalog.FileCatalogException;
import vn.edu.p2p.tracker.catalog.FileCatalogService;
import vn.edu.p2p.tracker.peer.PeerSessionService;
import vn.edu.p2p.tracker.peer.SessionException;
import vn.edu.p2p.tracker.source.FileSourceException;
import vn.edu.p2p.tracker.source.FileSourceService;
import vn.edu.p2p.tracker.transfer.TransferHistoryService;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class TrackerRequestDispatcher {
    private static final Logger LOG = Logger.getLogger(TrackerRequestDispatcher.class.getName());

    private final AuthService authService;
    private final PeerSessionService peerSessionService;
    private final FileSourceService fileSourceService;
    private final FileCatalogService fileCatalogService;
    private final TransferHistoryService transferHistoryService;
    private final RequestValidator validator;

    public TrackerRequestDispatcher(
            AuthService authService,
            PeerSessionService peerSessionService
    ) {
        this(authService, peerSessionService, null, null, null, new RequestValidator());
    }

    public TrackerRequestDispatcher(
            AuthService authService,
            PeerSessionService peerSessionService,
            FileSourceService fileSourceService
    ) {
        this(authService, peerSessionService, fileSourceService, null, null, new RequestValidator());
    }

    /** T11-T25: overload that also wires in Share/Search/Unshare (FileCatalogService). */
    public TrackerRequestDispatcher(
            AuthService authService,
            PeerSessionService peerSessionService,
            FileSourceService fileSourceService,
            FileCatalogService fileCatalogService
    ) {
        this(authService, peerSessionService, fileSourceService, fileCatalogService, null, new RequestValidator());
    }

    /** S35-S37: overload that also wires in TransferHistoryService. */
    public TrackerRequestDispatcher(
            AuthService authService,
            PeerSessionService peerSessionService,
            FileSourceService fileSourceService,
            FileCatalogService fileCatalogService,
            TransferHistoryService transferHistoryService
    ) {
        this(
                authService,
                peerSessionService,
                fileSourceService,
                fileCatalogService,
                transferHistoryService,
                new RequestValidator()
        );
    }

    TrackerRequestDispatcher(
            AuthService authService,
            PeerSessionService peerSessionService,
            FileSourceService fileSourceService,
            FileCatalogService fileCatalogService,
            RequestValidator validator
    ) {
        this(authService, peerSessionService, fileSourceService, fileCatalogService, null, validator);
    }

    TrackerRequestDispatcher(
            AuthService authService,
            PeerSessionService peerSessionService,
            FileSourceService fileSourceService,
            FileCatalogService fileCatalogService,
            TransferHistoryService transferHistoryService,
            RequestValidator validator
    ) {
        this.authService = authService;
        this.peerSessionService = peerSessionService;
        this.fileSourceService = fileSourceService;
        this.fileCatalogService = fileCatalogService;
        this.transferHistoryService = transferHistoryService;
        this.validator = validator;
    }

    public MessageEnvelope dispatch(MessageEnvelope request, String remoteIp) {
        try {
            validator.validateEnvelope(request);

            return switch (request.type()) {
                case LOGIN_REQUEST -> handleLogin(request, remoteIp);
                case LOGOUT_REQUEST -> handleLogout(request);
                case HEARTBEAT -> handleHeartbeat(request);
                case FILE_SOURCES_REQUEST -> handleFileSources(request);
                case SHARE_FILE_REQUEST -> handleShareFile(request);
                case FILE_PIECES_PAGE_REQUEST -> handleFilePiecesPage(request);
                case SEARCH_REQUEST -> handleSearch(request);
                case UNSHARE_FILE_REQUEST -> handleUnshareFile(request);
                case RECORD_TRANSFER_REQUEST -> handleRecordTransfer(request);
                case TRANSFER_HISTORY_REQUEST -> handleTransferHistory(request);
                default -> TrackerErrorResponses.forRequest(
                        request,
                        "UNSUPPORTED_MESSAGE",
                        "Tracker accepts Tracker request messages only."
                );
            };
        } catch (RequestValidationException e) {
            return TrackerErrorResponses.forRequest(
                    request, e.errorCode(), e.getMessage()
            );
        } catch (JsonProcessingException e) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "INVALID_REQUEST",
                    "Request payload does not match the expected format."
            );
        } catch (AuthException e) {
            return TrackerErrorResponses.forRequest(
                    request, e.errorCode(), e.getMessage()
            );
        } catch (SessionException e) {
            return TrackerErrorResponses.forRequest(
                    request, e.errorCode(), e.getMessage()
            );
        } catch (FileSourceException e) {
            return TrackerErrorResponses.forRequest(
                    request, e.errorCode(), e.getMessage()
            );
        } catch (FileCatalogException e) {
            return TrackerErrorResponses.forRequest(
                    request, "FILE_CATALOG_ERROR", e.getMessage()
            );
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unhandled Tracker request failure", e);
            return TrackerErrorResponses.forRequest(
                    request,
                    "SERVER_ERROR",
                    "Tracker failed to process the request."
            );
        }
    }

    private MessageEnvelope handleLogin(
            MessageEnvelope request,
            String remoteIp
    ) throws Exception {
        LoginRequest payload = ProtocolCodec.fromPayload(
                request.payload(), LoginRequest.class
        );
        validator.validateLogin(payload);
        LoginResponse response = authService.login(payload, remoteIp);

        return MessageEnvelope.success(
                MessageType.LOGIN_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    private MessageEnvelope handleLogout(MessageEnvelope request) throws Exception {
        LogoutRequest payload = ProtocolCodec.fromPayload(
                request.payload(), LogoutRequest.class
        );
        validator.validateLogout(payload);
        authService.logout(payload);

        return MessageEnvelope.success(
                MessageType.LOGOUT_RESPONSE,
                request.requestId(),
                null
        );
    }

    private MessageEnvelope handleHeartbeat(MessageEnvelope request) throws Exception {
        HeartbeatRequest payload = ProtocolCodec.fromPayload(
                request.payload(), HeartbeatRequest.class
        );
        validator.validateHeartbeat(payload);
        HeartbeatResponse response = peerSessionService.heartbeat(payload);

        return MessageEnvelope.success(
                MessageType.HEARTBEAT_ACK,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    private MessageEnvelope handleFileSources(MessageEnvelope request) throws Exception {
        if (fileSourceService == null) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "FEATURE_UNAVAILABLE",
                    "File-source discovery is not configured on this Tracker."
            );
        }

        FileSourcesRequest payload = ProtocolCodec.fromPayload(
                request.payload(), FileSourcesRequest.class
        );
        validator.validateFileSources(payload);
        FileSourcesResponse response = fileSourceService.findSources(payload);

        return MessageEnvelope.success(
                MessageType.FILE_SOURCES_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    /** T11: publish a FileMetadata (share a file) into the catalog. */
    private MessageEnvelope handleShareFile(MessageEnvelope request) throws Exception {
        if (fileCatalogService == null) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "FEATURE_UNAVAILABLE",
                    "File sharing is not configured on this Tracker."
            );
        }

        ShareFileRequest payload = ProtocolCodec.fromPayload(
                request.payload(), ShareFileRequest.class
        );
        validator.validateShareFile(payload);
        ShareFileResponse response = fileCatalogService.publish(payload);

        return MessageEnvelope.success(
                MessageType.SHARE_FILE_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    private MessageEnvelope handleFilePiecesPage(MessageEnvelope request) throws Exception {
        if (fileCatalogService == null) {
            return TrackerErrorResponses.forRequest(request, "FEATURE_UNAVAILABLE", "File catalog unavailable");
        }
        FilePiecesPageRequest page = ProtocolCodec.fromPayload(request.payload(), FilePiecesPageRequest.class);
        FilePiecesPageResponse saved = fileCatalogService.appendPiecePage(page);
        return MessageEnvelope.success(MessageType.FILE_PIECES_PAGE_RESPONSE,
                request.requestId(), ProtocolCodec.toPayload(saved));
    }

    /** T16-T17: search the catalog by (partial) file name. */
    private MessageEnvelope handleSearch(MessageEnvelope request) throws Exception {
        if (fileCatalogService == null) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "FEATURE_UNAVAILABLE",
                    "Search is not configured on this Tracker."
            );
        }

        SearchRequest payload = ProtocolCodec.fromPayload(
                request.payload(), SearchRequest.class
        );
        validator.validateSearch(payload);
        SearchResponse response = fileCatalogService.search(payload);

        return MessageEnvelope.success(
                MessageType.SEARCH_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    /** T22: stop sharing a file this peer previously announced. */
    private MessageEnvelope handleUnshareFile(MessageEnvelope request) throws Exception {
        if (fileCatalogService == null) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "FEATURE_UNAVAILABLE",
                    "File sharing is not configured on this Tracker."
            );
        }

        UnshareFileRequest payload = ProtocolCodec.fromPayload(
                request.payload(), UnshareFileRequest.class
        );
        validator.validateUnshareFile(payload);
        UnshareFileResponse response = fileCatalogService.unshare(payload);

        return MessageEnvelope.success(
                MessageType.UNSHARE_FILE_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    /** S35-S37: record transfer session start/completion and participating peer sources. */
    private MessageEnvelope handleRecordTransfer(MessageEnvelope request) throws Exception {
        if (transferHistoryService == null) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "FEATURE_UNAVAILABLE",
                    "Transfer history is not configured on this Tracker."
            );
        }

        RecordTransferRequest payload = ProtocolCodec.fromPayload(
                request.payload(), RecordTransferRequest.class
        );
        if (payload == null) {
            throw new RequestValidationException("INVALID_REQUEST", "RecordTransferRequest payload is required");
        }
        RecordTransferResponse response = transferHistoryService.recordTransfer(payload);

        return MessageEnvelope.success(
                MessageType.RECORD_TRANSFER_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }

    /** S38: fetch transfer history for the current peer. */
    private MessageEnvelope handleTransferHistory(MessageEnvelope request) throws Exception {
        if (transferHistoryService == null) {
            return TrackerErrorResponses.forRequest(
                    request,
                    "FEATURE_UNAVAILABLE",
                    "Transfer history is not configured on this Tracker."
            );
        }

        TransferHistoryRequest payload = ProtocolCodec.fromPayload(
                request.payload(), TransferHistoryRequest.class
        );
        if (payload == null) {
            throw new RequestValidationException("INVALID_REQUEST", "TransferHistoryRequest payload is required");
        }
        TransferHistoryResponse response = transferHistoryService.getHistory(payload);

        return MessageEnvelope.success(
                MessageType.TRANSFER_HISTORY_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(response)
        );
    }
}