package vn.edu.p2p.tracker.transfer;

import vn.edu.p2p.common.dto.RecordTransferRequest;
import vn.edu.p2p.common.dto.RecordTransferResponse;
import vn.edu.p2p.common.dto.TransferHistoryItemDto;
import vn.edu.p2p.common.dto.TransferHistoryRequest;
import vn.edu.p2p.common.dto.TransferHistoryResponse;
import vn.edu.p2p.tracker.catalog.FileCatalogException;
import vn.edu.p2p.tracker.catalog.PeerFileRepository;
import vn.edu.p2p.tracker.catalog.SessionPeerResolver;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S35-S37: Dịch vụ xử lý ghi nhận lịch sử tải file và truy vấn lịch sử trên Tracker.
 */
public final class TransferHistoryService {

    private final SessionPeerResolver sessionPeerResolver;
    private final TransferHistoryRepository transferHistoryRepository;
    private final PeerFileRepository peerFileRepository;

    public TransferHistoryService(
            SessionPeerResolver sessionPeerResolver,
            TransferHistoryRepository transferHistoryRepository,
            PeerFileRepository peerFileRepository
    ) {
        this.sessionPeerResolver = sessionPeerResolver;
        this.transferHistoryRepository = transferHistoryRepository;
        this.peerFileRepository = peerFileRepository;
    }

    public RecordTransferResponse recordTransfer(RecordTransferRequest request)
            throws FileCatalogException, SQLException {
        UUID sessionId = parseUuid(request.sessionId(), "sessionId");
        UUID destinationPeerId = sessionPeerResolver.resolvePeerId(sessionId);
        UUID transferId = parseUuid(request.transferId(), "transferId");

        String status = normalizeStatus(request.status());
        Instant startedAt = request.startedAtEpochMillis() > 0
                ? Instant.ofEpochMilli(request.startedAtEpochMillis())
                : Instant.now();
        Instant completedAt = request.completedAtEpochMillis() != null && request.completedAtEpochMillis() > 0
                ? Instant.ofEpochMilli(request.completedAtEpochMillis())
                : null;

        transferHistoryRepository.upsertTransferSession(
                transferId,
                request.fileId(),
                destinationPeerId,
                status,
                request.bytesReceived(),
                startedAt,
                completedAt,
                request.failureReason()
        );

        transferHistoryRepository.upsertTransferSources(transferId, request.sources());

        // Đồng bộ trạng thái sở hữu file (PARTIAL / COMPLETE) trên Tracker
        if (peerFileRepository != null) {
            if ("COMPLETED".equals(status)) {
                peerFileRepository.upsertOwnership(destinationPeerId, request.fileId(), "COMPLETE");
            } else if ("DOWNLOADING".equals(status)) {
                peerFileRepository.upsertOwnership(destinationPeerId, request.fileId(), "PARTIAL");
            } else if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                peerFileRepository.demoteIfPartial(destinationPeerId, request.fileId());
            }
        }

        return new RecordTransferResponse(transferId.toString(), status);
    }

    public TransferHistoryResponse getHistory(TransferHistoryRequest request)
            throws FileCatalogException, SQLException {
        UUID sessionId = parseUuid(request.sessionId(), "sessionId");
        UUID destinationPeerId = sessionPeerResolver.resolvePeerId(sessionId);

        List<TransferHistoryItemDto> items =
                transferHistoryRepository.findByDestinationPeer(destinationPeerId);
        return new TransferHistoryResponse(items);
    }

    private static UUID parseUuid(String raw, String fieldName) throws FileCatalogException {
        if (raw == null || raw.isBlank()) {
            throw new FileCatalogException(fieldName + " is required");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new FileCatalogException("Invalid " + fieldName + ": " + raw);
        }
    }

    private static String normalizeStatus(String status) throws FileCatalogException {
        if (status == null || status.isBlank()) {
            return "DOWNLOADING";
        }
        String upper = status.trim().toUpperCase();
        return switch (upper) {
            case "QUEUED", "DOWNLOADING", "COMPLETED", "FAILED", "CANCELLED" -> upper;
            default -> throw new FileCatalogException("Invalid transfer status: " + status);
        };
    }
}