package vn.edu.p2p.tracker.repository.jdbc;

import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;
import vn.edu.p2p.tracker.domain.FileSourceRecord;
import vn.edu.p2p.tracker.repository.FileSourceRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcFileSourceRepository implements FileSourceRepository {
    private final DatabaseConnectionFactory connectionFactory;

    public JdbcFileSourceRepository(DatabaseConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Optional<String> findReadyCatalogHash(long fileId) throws SQLException {
        try (Connection connection = connectionFactory.open();
             PreparedStatement st = connection.prepareStatement(
                     "SELECT file_hash FROM files WHERE file_id = ? AND publish_status = 'READY'")) {
            st.setLong(1, fileId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public List<FileSourceRecord> findOnlineSources(
            long fileId,
            UUID excludingPeerId
    ) throws SQLException {
        String sql = """
                SELECT file_id, peer_id, availability_status,
                       ip_address, listening_port, last_seen
                FROM v_file_sources
                WHERE file_id = ? AND peer_id <> ?
                ORDER BY
                    CASE availability_status
                        WHEN 'COMPLETE' THEN 0
                        ELSE 1
                    END,
                    last_seen DESC
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, fileId);
            statement.setObject(2, excludingPeerId);

            try (ResultSet rs = statement.executeQuery()) {
                List<FileSourceRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new FileSourceRecord(
                            rs.getLong("file_id"),
                            rs.getObject("peer_id", UUID.class),
                            rs.getString("availability_status"),
                            rs.getString("ip_address"),
                            rs.getInt("listening_port"),
                            toOffsetDateTime(rs.getTimestamp("last_seen"))
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
