package vn.edu.p2p.tracker.source;

import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.FileSourcesRequest;
import vn.edu.p2p.common.dto.FileSourcesResponse;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import vn.edu.p2p.tracker.domain.FileSourceRecord;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.peer.PeerSessionService;
import vn.edu.p2p.tracker.peer.SessionException;
import vn.edu.p2p.tracker.repository.FileSourceRepository;
import vn.edu.p2p.tracker.repository.PeerRepository;

import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;

public final class FileSourceService {
    private final FileSourceRepository repository;
    private final PeerSessionService sessionService;
    private final PeerRepository peerRepository;
    private final PrivateKey signingKey;

    public FileSourceService(
            FileSourceRepository repository,
            PeerSessionService sessionService,
            PeerRepository peerRepository
    ) {
        this(repository, sessionService, peerRepository, null);
    }

    public FileSourceService(FileSourceRepository repository, PeerSessionService sessionService,
                             PeerRepository peerRepository, PrivateKey signingKey) {
        this.repository = repository;
        this.sessionService = sessionService;
        this.peerRepository = peerRepository;
        this.signingKey = signingKey;
    }

    public FileSourcesResponse findSources(FileSourcesRequest request)
            throws FileSourceException {
        if (request == null) {
            throw new FileSourceException(
                    "INVALID_REQUEST",
                    "Missing file-source request payload."
            );
        }
        if (request.fileId() <= 0) {
            throw new FileSourceException(
                    "INVALID_FILE_ID",
                    "fileId must be positive."
            );
        }

        final PeerSessionRecord requester;
        try {
            requester = sessionService.requireOnlineSession(request.sessionId());
        } catch (SessionException e) {
            throw new FileSourceException(e.errorCode(), e.getMessage(), e);
        }

        try {
            String encodedPublicKey = peerRepository.findPublicKey(requester.peerId())
                    .orElseThrow(() -> new FileSourceException("REQUESTER_KEY_MISSING",
                            "Peer public key is missing; please log in again."));
            final PublicKey requesterPublicKey;
            try {
                requesterPublicKey = PeerRequestProof.decodePublicKey(encodedPublicKey);
            } catch (IllegalArgumentException e) {
                throw new FileSourceException("REQUESTER_KEY_INVALID",
                        "Registered Peer public key is invalid; please log in again.", e);
            }
            String trustedHash = repository.findReadyCatalogHash(request.fileId())
                    .orElseThrow(() -> new FileSourceException("FILE_NOT_READY",
                            "File is not published or catalog hash is unavailable"));
            List<FileSourceInfo> sources = repository.findOnlineSources(
                            request.fileId(),
                            requester.peerId()
                    )
                    .stream()
                    .map(source -> toInfo(source, request.fileId(), requester.peerId().toString(),
                            requesterPublicKey))
                    .toList();

            return new FileSourcesResponse(request.fileId(), sources, trustedHash);
        } catch (SQLException e) {
            throw new FileSourceException(
                    "DATABASE_ERROR",
                    "Tracker cannot query file sources.",
                    e
            );
        }
    }

    private FileSourceInfo toInfo(FileSourceRecord source, long fileId, String requesterPeerId,
                                  PublicKey requesterPublicKey) {
        long lastSeen = source.lastSeen() == null
                ? 0L
                : source.lastSeen()
                        .withOffsetSameInstant(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli();

        return new FileSourceInfo(
                source.peerId().toString(),
                source.availabilityStatus(),
                source.ipAddress(),
                source.listeningPort(),
                lastSeen,
                signingKey == null ? null : PeerAccessTickets.issue(signingKey, fileId,
                        requesterPeerId, source.peerId().toString(), requesterPublicKey, Instant.now())
        );
    }
}
