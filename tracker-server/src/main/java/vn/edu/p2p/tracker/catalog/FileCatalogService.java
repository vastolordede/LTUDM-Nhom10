package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FilePiecesPageRequest;
import vn.edu.p2p.common.dto.FilePiecesPageResponse;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.SearchRequest;
import vn.edu.p2p.common.dto.SearchResponse;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.common.dto.ShareFileRequest;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.common.dto.UnshareFileResponse;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic behind SHARE_FILE_REQUEST, SEARCH_REQUEST and
 * UNSHARE_FILE_REQUEST:
 *  - resolve the sessionId to an active peer (via SessionPeerResolver)
 *  - T13: detect duplicate files by SHA-256 hash
 *  - T11-T12: insert new file + pieces into the catalog when not a duplicate
 *  - T14-T15: record/refresh the sharing peer's ownership row
 *  - T16-T17: search the catalog by name
 *  - T22: stop sharing a file this peer previously announced
 *  - T24-T25: log SHARE_START / SHARE_STOP into share_events
 */
public final class FileCatalogService {

    private final SessionPeerResolver sessionPeerResolver;
    private final FileRepository fileRepository;
    private final PeerFileRepository peerFileRepository;
    private final ShareEventRepository shareEventRepository;

    public FileCatalogService(
            SessionPeerResolver sessionPeerResolver,
            FileRepository fileRepository,
            PeerFileRepository peerFileRepository,
            ShareEventRepository shareEventRepository
    ) {
        this.sessionPeerResolver = sessionPeerResolver;
        this.fileRepository = fileRepository;
        this.peerFileRepository = peerFileRepository;
        this.shareEventRepository = shareEventRepository;
    }

    public ShareFileResponse publish(ShareFileRequest request) throws FileCatalogException, SQLException {
        UUID sessionId = parseSessionId(request.sessionId());
        UUID peerId = sessionPeerResolver.resolvePeerId(sessionId);

        FileMetadata metadata = request.metadata();

        Optional<Long> existingFileId = fileRepository.findFileIdByHash(metadata.fileHash());

        long fileId;
        boolean isNewFile;

        if (existingFileId.isPresent()) {
            // T13: identical content already known to the Tracker - reuse
            // the existing file_id instead of inserting a duplicate row.
            fileId = existingFileId.get();
            isNewFile = false;
        } else {
            // T11-T12: publish new metadata into the File Catalog.
            fileId = fileRepository.insert(metadata);
            isNewFile = true;
        }

        // T14-T15: the sharing peer already holds the whole file locally,
        // so it is recorded as a COMPLETE, actively-sharing source.
        String availabilityStatus = "COMPLETE";
        peerFileRepository.upsertOwnership(peerId, fileId, availabilityStatus);

        // T24: log SHARE_START every time a peer (re-)announces sharing.
        shareEventRepository.insert(peerId, fileId, "SHARE_START");

        return new ShareFileResponse(fileId, isNewFile, availabilityStatus);
    }

    /** Large-file metadata pages, only from a peer actively sharing this file. */
    public FilePiecesPageResponse appendPiecePage(FilePiecesPageRequest request)
            throws FileCatalogException, SQLException {
        if (request == null || request.fileId() <= 0 || request.sessionId() == null
                || request.pieces().isEmpty()
                || request.pieces().size() > 512) {
            throw new FileCatalogException("Invalid metadata page (max 512 pieces)");
        }
        UUID peer = sessionPeerResolver.resolvePeerId(parseSessionId(request.sessionId()));
        if (!peerFileRepository.isSharing(peer, request.fileId())) {
            throw new FileCatalogException("Peer does not own an active share for this file");
        }
        for (PieceInfo piece : request.pieces()) {
            if (piece.pieceIndex() < 0 || piece.pieceLength() <= 0 || piece.pieceHash() == null
                    || !piece.pieceHash().matches("^[0-9A-Fa-f]{64}$")) {
                throw new FileCatalogException("Invalid piece metadata in page");
            }
        }
        fileRepository.upsertPiecePage(request.fileId(), request.pieces());
        return new FilePiecesPageResponse(request.fileId(), request.pieces().size());
    }

    /** T16-T17: search by (partial) file name. */
    public SearchResponse search(SearchRequest request) throws FileCatalogException, SQLException {
        UUID sessionId = parseSessionId(request.sessionId());
        // Require an active session even though the query itself doesn't
        // need the peer identity - keeps Search behind the same auth gate
        // as every other Tracker request.
        sessionPeerResolver.resolvePeerId(sessionId);

        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            throw new FileCatalogException("Search query must not be empty");
        }

        String likePattern = "%" + escapeLike(query) + "%";
        List<SearchResultItem> results = fileRepository.searchByName(likePattern);

        return new SearchResponse(results);
    }

    /** T22: stop sharing - only affects this peer's own peer_files row. T25: logs SHARE_STOP. */
    public UnshareFileResponse unshare(UnshareFileRequest request) throws FileCatalogException, SQLException {
        UUID sessionId = parseSessionId(request.sessionId());
        UUID peerId = sessionPeerResolver.resolvePeerId(sessionId);

        peerFileRepository.setSharing(peerId, request.fileId(), false);
        shareEventRepository.insert(peerId, request.fileId(), "SHARE_STOP");

        return new UnshareFileResponse(request.fileId(), true);
    }

    private UUID parseSessionId(String sessionId) throws FileCatalogException {
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new FileCatalogException("Invalid sessionId: " + sessionId);
        }
    }

    /** Escapes ILIKE special characters so a search for "50%" or "a_b" is literal. */
    private static String escapeLike(String raw) {
        return raw
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
