package vn.edu.p2p.tracker.repository.jdbc;

import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;
import vn.edu.p2p.tracker.domain.PeerRecord;
import vn.edu.p2p.tracker.repository.PeerRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

public final class JdbcPeerRepository implements PeerRepository {
    private final DatabaseConnectionFactory connectionFactory;

    public JdbcPeerRepository(DatabaseConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Optional<PeerRecord> findById(UUID peerId) throws SQLException {
        String sql = """
                SELECT peer_id, user_id, device_name, created_at, last_login_at
                FROM peers
                WHERE peer_id = ?
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, peerId);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rs));
            }
        }
    }

    @Override
    public void create(PeerRecord peer) throws SQLException {
        String sql = """
                INSERT INTO peers(peer_id, user_id, device_name, created_at, last_login_at)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, peer.peerId());
            statement.setLong(2, peer.userId());
            statement.setString(3, peer.deviceName());
            statement.setObject(4, peer.createdAt());
            statement.setObject(5, peer.lastLoginAt());
            statement.executeUpdate();
        }
    }

    @Override
    public void updateLastLogin(UUID peerId, OffsetDateTime lastLoginAt) throws SQLException {
        String sql = "UPDATE peers SET last_login_at = ? WHERE peer_id = ?";

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, lastLoginAt);
            statement.setObject(2, peerId);
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<String> findPublicKey(UUID peerId) throws SQLException {
        String sql = "SELECT public_key FROM peers WHERE peer_id = ?";
        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, peerId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("public_key")) : Optional.empty();
            }
        }
    }

    @Override
    public void updatePublicKey(UUID peerId, String publicKey) throws SQLException {
        String sql = "UPDATE peers SET public_key = ? WHERE peer_id = ?";
        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, publicKey);
            statement.setObject(2, peerId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Peer public key update matched no peer row");
            }
        }
    }

    private static PeerRecord map(ResultSet rs) throws SQLException {
        return new PeerRecord(
                rs.getObject("peer_id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("device_name"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("last_login_at"))
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
