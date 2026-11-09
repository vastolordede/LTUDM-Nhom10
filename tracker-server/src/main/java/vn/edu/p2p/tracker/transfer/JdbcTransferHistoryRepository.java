package vn.edu.p2p.tracker.transfer;

import vn.edu.p2p.common.dto.TransferHistoryItemDto;
import vn.edu.p2p.common.dto.TransferSourceRecordDto;
import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S35-S37: Thực thi JDBC lưu lịch sử phiên tải vào bảng transfer_sessions
 * và các nguồn tham gia vào bảng transfer_sources.
 */
public final class JdbcTransferHistoryRepository implements TransferHistoryRepository {

    private static final int HISTORY_LIMIT = 100;

    private final DatabaseConnectionFactory connectionFactory;

    public JdbcTransferHistoryRepository(DatabaseConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void upsertTransferSession(
            UUID transferId,
            long fileId,
            UUID destinationPeerId,
            String status,
            long bytesReceived,
            Instant startedAt,
            Instant completedAt,
            String failureReason
    ) throws SQLException {
        String sql = """
                INSERT INTO transfer_sessions
                    (transfer_id, file_id, destination_peer_id, status, bytes_received, started_at, completed_at, failure_reason)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transfer_id) DO UPDATE
                SET status = EXCLUDED.status,
                    bytes_received = EXCLUDED.bytes_received,
                    completed_at = EXCLUDED.completed_at,
                    failure_reason = EXCLUDED.failure_reason
                WHERE transfer_sessions.destination_peer_id = EXCLUDED.destination_peer_id
                  AND transfer_sessions.file_id = EXCLUDED.file_id
                  AND EXCLUDED.bytes_received >= transfer_sessions.bytes_received
                  AND (transfer_sessions.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                       OR (transfer_sessions.status = EXCLUDED.status
                           AND transfer_sessions.bytes_received = EXCLUDED.bytes_received))
                """;

        Instant safeStart = startedAt != null ? startedAt : Instant.now();
        Instant safeCompleted = null;
        if (completedAt != null) {
            safeCompleted = completedAt.isBefore(safeStart) ? safeStart : completedAt;
        }

        if (bytesReceived < 0) throw new SQLException("Negative transfer byte count");
        try (Connection connection = connectionFactory.open()) {
            // Status COMPLETED is not credible when the reported byte count differs from the catalog.
            // This bounds falsified client reports, although true end-to-end proof still needs
            // evidence from the uploader (a separate R101 security acceptance item).
            try (PreparedStatement file = connection.prepareStatement(
                    "SELECT file_size FROM files WHERE file_id = ?")) {
                file.setLong(1, fileId);
                try (ResultSet rs = file.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Unknown transfer fileId");
                    long fileSize = rs.getLong(1);
                    if (bytesReceived > fileSize
                            || ("COMPLETED".equals(status) && bytesReceived != fileSize)) {
                        throw new SQLException("Reported bytes inconsistent with file metadata");
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, transferId);
            statement.setLong(2, fileId);
            statement.setObject(3, destinationPeerId);
            statement.setString(4, status);
            statement.setLong(5, Math.max(0L, bytesReceived));
            statement.setTimestamp(6, Timestamp.from(safeStart));
            statement.setTimestamp(7, safeCompleted != null ? Timestamp.from(safeCompleted) : null);
            statement.setString(8, failureReason);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Invalid transfer UUID ownership, state transition or byte regression");
            }
            }
        }
    }

    @Override
    public void upsertTransferSources(
            UUID transferId,
            List<TransferSourceRecordDto> sources
    ) throws SQLException {
        if (sources == null || sources.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO transfer_sources
                    (transfer_id, source_peer_id, bytes_received, pieces_received, source_status, connected_at, disconnected_at)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (transfer_id, source_peer_id) DO UPDATE
                SET bytes_received = EXCLUDED.bytes_received,
                    pieces_received = EXCLUDED.pieces_received,
                    source_status = EXCLUDED.source_status,
                    disconnected_at = EXCLUDED.disconnected_at
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TransferSourceRecordDto src : sources) {
                UUID sourcePeerId;
                try {
                    sourcePeerId = UUID.fromString(src.sourcePeerId());
                } catch (Exception ignored) {
                    continue;
                }

                Instant connectedAt = src.connectedAtEpochMillis() > 0
                        ? Instant.ofEpochMilli(src.connectedAtEpochMillis())
                        : Instant.now();

                Instant disconnectedAt = null;
                if (src.disconnectedAtEpochMillis() != null && src.disconnectedAtEpochMillis() > 0) {
                    Instant rawDisc = Instant.ofEpochMilli(src.disconnectedAtEpochMillis());
                    disconnectedAt = rawDisc.isBefore(connectedAt) ? connectedAt : rawDisc;
                }

                statement.setObject(1, transferId);
                statement.setObject(2, sourcePeerId);
                statement.setLong(3, Math.max(0L, src.bytesReceived()));
                statement.setInt(4, Math.max(0, src.piecesReceived()));
                statement.setString(5, src.sourceStatus() != null ? src.sourceStatus() : "ACTIVE");
                statement.setTimestamp(6, Timestamp.from(connectedAt));
                statement.setTimestamp(7, disconnectedAt != null ? Timestamp.from(disconnectedAt) : null);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    @Override
    public List<TransferHistoryItemDto> findByDestinationPeer(UUID destinationPeerId)
            throws SQLException {
        String sql = """
                SELECT ts.transfer_id,
                       ts.file_id,
                       f.file_name,
                       f.file_size,
                       ts.status,
                       ts.bytes_received,
                       ts.started_at,
                       ts.completed_at,
                       ts.failure_reason,
                       COUNT(src.source_peer_id) AS source_count
                FROM transfer_sessions ts
                JOIN files f ON f.file_id = ts.file_id
                LEFT JOIN transfer_sources src ON src.transfer_id = ts.transfer_id
                WHERE ts.destination_peer_id = ?
                GROUP BY ts.transfer_id, ts.file_id, f.file_name, f.file_size,
                         ts.status, ts.bytes_received, ts.started_at,
                         ts.completed_at, ts.failure_reason
                ORDER BY ts.started_at DESC
                LIMIT ?
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, destinationPeerId);
            statement.setInt(2, HISTORY_LIMIT);

            try (ResultSet rs = statement.executeQuery()) {
                List<TransferHistoryItemDto> items = new ArrayList<>();
                while (rs.next()) {
                    Timestamp startedTs = rs.getTimestamp("started_at");
                    Timestamp completedTs = rs.getTimestamp("completed_at");
                    items.add(new TransferHistoryItemDto(
                            rs.getObject("transfer_id", UUID.class).toString(),
                            rs.getLong("file_id"),
                            rs.getString("file_name"),
                            rs.getLong("file_size"),
                            rs.getString("status"),
                            rs.getLong("bytes_received"),
                            startedTs != null ? startedTs.toInstant().toEpochMilli() : 0L,
                            completedTs != null ? completedTs.toInstant().toEpochMilli() : null,
                            rs.getString("failure_reason"),
                            rs.getInt("source_count")
                    ));
                }
                return items;
            }
        }
    }
}