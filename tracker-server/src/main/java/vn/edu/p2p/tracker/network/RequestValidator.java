package vn.edu.p2p.tracker.network;

import vn.edu.p2p.common.dto.FileSourcesRequest;
import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.dto.RegisterRequest;
import vn.edu.p2p.common.auth.RegisterInputPolicy;
import vn.edu.p2p.common.dto.SearchRequest;
import vn.edu.p2p.common.dto.ShareFileRequest;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;

import java.util.UUID;

final class RequestValidator {
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final int MAX_USERNAME_LENGTH = 50;
    private static final int MAX_DEVICE_NAME_LENGTH = 100;
    private static final int MAX_SEARCH_QUERY_LENGTH = 255;

    void validateEnvelope(MessageEnvelope request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing request envelope.");
        }
        if (request.version() != MessageEnvelope.CURRENT_VERSION) {
            throw new RequestValidationException(
                    "UNSUPPORTED_PROTOCOL_VERSION",
                    "Tracker supports protocol version " + MessageEnvelope.CURRENT_VERSION
                            + ", received " + request.version()
            );
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw invalid("Missing requestId.");
        }
        if (request.requestId().length() > MAX_REQUEST_ID_LENGTH) {
            throw invalid("requestId is too long.");
        }
        if (request.payload() == null || request.payload().isNull()) {
            throw invalid("Missing request payload.");
        }
    }

    void validateLogin(LoginRequest request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing login payload.");
        }
        if (request.username() == null || request.username().isBlank()) {
            throw invalid("Missing username.");
        }
        if (request.username().trim().length() > MAX_USERNAME_LENGTH) {
            throw invalid("Username is too long.");
        }
        if (request.password() == null || request.password().isBlank()) {
            throw invalid("Missing password.");
        }
        if (request.listeningPort() < 1 || request.listeningPort() > 65535) {
            throw new RequestValidationException(
                    "INVALID_PORT",
                    "Invalid Peer listening port."
            );
        }
        requireUuid(request.peerId(), "INVALID_PEER_ID", "peerId");
        if (request.deviceName() != null
                && request.deviceName().trim().length() > MAX_DEVICE_NAME_LENGTH) {
            throw invalid("Device name is too long.");
        }
    }

    void validateRegister(RegisterRequest request) throws RequestValidationException {
        String error = request == null
                ? "Missing registration payload."
                : RegisterInputPolicy.validate(request.username(), request.password());
        if (error != null) throw new RequestValidationException("INVALID_REGISTRATION", error);
    }

    void validateLogout(LogoutRequest request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing logout payload.");
        }
        requireUuid(request.sessionId(), "INVALID_SESSION", "sessionId");
    }

    void validateHeartbeat(HeartbeatRequest request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing heartbeat payload.");
        }
        requireUuid(request.sessionId(), "INVALID_SESSION", "sessionId");
        if (request.lastRevocationSeq() < 0) {
            throw invalid("lastRevocationSeq must be non-negative.");
        }
    }

    void validateFileSources(FileSourcesRequest request)
            throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing file-source payload.");
        }
        requireUuid(request.sessionId(), "INVALID_SESSION", "sessionId");
        if (request.fileId() <= 0) {
            throw new RequestValidationException(
                    "INVALID_FILE_ID",
                    "fileId must be positive."
            );
        }
    }

    /** T11: validate a ShareFileRequest before it reaches FileCatalogService.publish(...). */
    void validateShareFile(ShareFileRequest request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing share-file payload.");
        }
        requireUuid(request.sessionId(), "INVALID_SESSION", "sessionId");
        if (request.metadata() == null) {
            throw new RequestValidationException(
                    "INVALID_FILE_METADATA",
                    "metadata is required."
            );
        }
        // metadata's own compact constructor (FileMetadata record) already
        // rejects a blank fileName, a malformed SHA-256 fileHash, and a
        // non-positive pieceSize, so no need to re-check those fields here.
    }

    /** T16: validate a SearchRequest before it reaches FileCatalogService.search(...). */
    void validateSearch(SearchRequest request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing search payload.");
        }
        requireUuid(request.sessionId(), "INVALID_SESSION", "sessionId");
        if (request.query() != null && request.query().trim().length() > MAX_SEARCH_QUERY_LENGTH) {
            throw new RequestValidationException(
                    "INVALID_SEARCH_QUERY",
                    "query is too long."
            );
        }
    }

    /** T22: validate an UnshareFileRequest before it reaches FileCatalogService.unshare(...). */
    void validateUnshareFile(UnshareFileRequest request) throws RequestValidationException {
        if (request == null) {
            throw invalid("Missing unshare payload.");
        }
        requireUuid(request.sessionId(), "INVALID_SESSION", "sessionId");
        if (request.fileId() <= 0) {
            throw new RequestValidationException(
                    "INVALID_FILE_ID",
                    "fileId must be positive."
            );
        }
    }

    private static void requireUuid(
            String raw,
            String errorCode,
            String fieldName
    ) throws RequestValidationException {
        if (raw == null || raw.isBlank()) {
            throw new RequestValidationException(
                    errorCode,
                    "Missing " + fieldName + "."
            );
        }
        try {
            UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException(
                    errorCode,
                    fieldName + " must be a valid UUID."
            );
        }
    }

    private static RequestValidationException invalid(String message) {
        return new RequestValidationException("INVALID_REQUEST", message);
    }
}
