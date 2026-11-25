package vn.edu.p2p.tracker.transfer;

import vn.edu.p2p.common.dto.TransferHistoryItemDto;
import vn.edu.p2p.common.dto.TransferSourceRecordDto;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * S35-S37: Giao diện thao tác với 2 bảng transfer_sessions và transfer_sources.
 */
public interface TransferHistoryRepository {

    void upsertTransferSession(
            UUID transferId,
            long fileId,
            UUID destinationPeerId,
            String status,
            long bytesReceived,
            Instant startedAt,
            Instant completedAt,
            String failureReason
    ) throws SQLException;

    void upsertTransferSources(
            UUID transferId,
            List<TransferSourceRecordDto> sources
    ) throws SQLException;

    List<TransferHistoryItemDto> findByDestinationPeer(UUID destinationPeerId) throws SQLException;

    default List<TransferHistoryItemDto> findByDestinationPeer(UUID destinationPeerId, int page) throws SQLException {
        var rows = findByDestinationPeer(destinationPeerId);
        long from = (long) page * 100;
        return from >= rows.size() ? List.of() : rows.subList((int) from, Math.min(rows.size(), (int) from + 100));
    }
}