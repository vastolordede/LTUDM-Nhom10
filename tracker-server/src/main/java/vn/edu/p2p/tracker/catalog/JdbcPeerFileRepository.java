package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class JdbcPeerFileRepository implements PeerFileRepository {

    private final DatabaseConnectionFactory connectionFactory;

    public JdbcPeerFileRepository(DatabaseConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void upsertOwnership(UUID peerId, long fileId, String availabilityStatus) throws SQLException {
        // peer_files primary key is (peer_id, file_id), so ON CONFLICT
        // upserts cleanly whether this peer already announced the file before.
        String sql = """
                INSERT INTO peer_files
                    (peer_id, file_id, availability_status, is_sharing, announced_at, updated_at, completed_at)
                VALUES
                    (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                ON CONFLICT (peer_id, file_id) DO UPDATE
                SET availability_status = EXCLUDED.availability_status,
                    is_sharing = TRUE,
                    updated_at = CURRENT_TIMESTAMP,
                    completed_at = COALESCE(peer_files.completed_at, EXCLUDED.completed_at)
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, peerId);
            statement.setLong(2, fileId);
            statement.setString(3, availabilityStatus);

            Timestamp completedAt = "COMPLETE".equals(availabilityStatus)
                    ? Timestamp.from(OffsetDateTime.now().toInstant())
                    : null;
            statement.setTimestamp(4, completedAt);

            statement.executeUpdate();
        }
    }

    @Override
    public boolean isSharing(UUID peerId, long fileId) throws SQLException {
        String sql = "SELECT 1 FROM peer_files WHERE peer_id = ? AND file_id = ? AND is_sharing = TRUE";
        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, peerId);
            statement.setLong(2, fileId);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    @Override
    public void setSharing(UUID peerId, long fileId, boolean sharing) throws SQLException {
        String sql = """
                UPDATE peer_files
                SET is_sharing = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE peer_id = ? AND file_id = ?
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, sharing);
            statement.setObject(2, peerId);
            statement.setLong(3, fileId);
            statement.executeUpdate();
        }
    }
}
