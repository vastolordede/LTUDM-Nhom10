package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC-backed FileRepository, following the same connection/PreparedStatement
 * style as JdbcUserRepository / JdbcPeerRepository / JdbcFileSourceRepository.
 */
public final class JdbcFileRepository implements FileRepository {

    private static final int SEARCH_RESULT_LIMIT = 50;

    private final DatabaseConnectionFactory connectionFactory;

    public JdbcFileRepository(DatabaseConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Optional<Long> findFileIdByHash(String fileHash) throws SQLException {
        String sql = """
                SELECT file_id
                FROM files
                WHERE file_hash = ?
                """;

        try (Connection connection = connectionFactory.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileHash);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(rs.getLong("file_id"));
            }
        }
    }

    @Override
    public long insert(FileMetadata metadata) throws SQLException {
        String insertFileSql = """
                INSERT INTO files (file_name, file_size, file_hash, piece_size, piece_count, publish_status)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        String insertPieceSql = """
                INSERT INTO file_pieces (file_id, piece_index, piece_length, piece_hash)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection = connectionFactory.open()) {
            connection.setAutoCommit(false);

            try {
                long fileId;

                try (PreparedStatement statement = connection.prepareStatement(
                        insertFileSql, Statement.RETURN_GENERATED_KEYS
                )) {
                    statement.setString(1, metadata.fileName());
                    statement.setLong(2, metadata.fileSize());
                    statement.setString(3, metadata.fileHash());
                    statement.setInt(4, metadata.pieceSize());
                    statement.setInt(5, metadata.pieceCount());
                    statement.setString(6, metadata.pieces().size() == metadata.pieceCount() ? "READY" : "PUBLISHING");
                    statement.executeUpdate();

                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Insert into files did not return a generated key");
                        }
                        fileId = keys.getLong(1);
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(insertPieceSql)) {
                    for (PieceInfo piece : metadata.pieces()) {
                        statement.setLong(1, fileId);
                        statement.setInt(2, piece.pieceIndex());
                        statement.setInt(3, piece.pieceLength());
                        statement.setString(4, piece.pieceHash());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }

                try (PreparedStatement ready = connection.prepareStatement("""
                        UPDATE files SET publish_status = 'READY'
                        WHERE file_id = ? AND publish_status = 'PUBLISHING'
                        AND (SELECT COUNT(*) FROM file_pieces WHERE file_id = ?) = piece_count
                        """)) {
                    ready.setLong(1, fileId);
                    ready.setLong(2, fileId);
                    ready.executeUpdate();
                }

                connection.commit();
                return fileId;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public void upsertPiecePage(long fileId, List<PieceInfo> pieces) throws SQLException {
        // Lock the parent row to serialize concurrent page updates and the READY transition.
        // A duplicate page with identical bytes is idempotent; a conflicting replay is rejected.
        String upsertSql = """
                INSERT INTO file_pieces (file_id, piece_index, piece_length, piece_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (file_id, piece_index) DO UPDATE
                    SET piece_hash = EXCLUDED.piece_hash
                WHERE file_pieces.piece_length = EXCLUDED.piece_length
                  AND LOWER(file_pieces.piece_hash) = LOWER(EXCLUDED.piece_hash)
                """;
        try (Connection connection = connectionFactory.open()) {
            connection.setAutoCommit(false);
            try {
                long fileSize;
                int pieceSize;
                int pieceCount;
                try (PreparedStatement lock = connection.prepareStatement("""
                        SELECT file_size, piece_size, piece_count
                        FROM files WHERE file_id = ? FOR UPDATE
                        """)) {
                    lock.setLong(1, fileId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Unknown fileId: " + fileId);
                        fileSize = rs.getLong("file_size");
                        pieceSize = rs.getInt("piece_size");
                        pieceCount = rs.getInt("piece_count");
                    }
                }
                try {
                    PiecePageGuard.check(fileSize, pieceSize, pieceCount, pieces);
                } catch (IllegalArgumentException ex) {
                    throw new SQLException("Rejected metadata page: " + ex.getMessage(), ex);
                }
                try (PreparedStatement upsert = connection.prepareStatement(upsertSql)) {
                    for (PieceInfo piece : pieces) {
                        upsert.setLong(1, fileId);
                        upsert.setInt(2, piece.pieceIndex());
                        upsert.setInt(3, piece.pieceLength());
                        upsert.setString(4, piece.pieceHash());
                        if (upsert.executeUpdate() != 1) {
                            throw new SQLException("Conflicting metadata for piece " + piece.pieceIndex());
                        }
                    }
                }
                // All indexes were verified to lie in [0, pieceCount); a unique PK and an
                // exact count therefore prove that the complete contiguous index set exists.
                try (PreparedStatement ready = connection.prepareStatement("""
                        UPDATE files SET publish_status = 'READY'
                        WHERE file_id = ? AND publish_status = 'PUBLISHING'
                          AND (SELECT COUNT(*) FROM file_pieces WHERE file_id = ?) = piece_count
                        """)) {
                    ready.setLong(1, fileId);
                    ready.setLong(2, fileId);
                    ready.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Override
    public List<SearchResultItem> searchByName(String likePattern) throws SQLException {
        String sql = """
                SELECT f.file_id, m.file_name, f.file_size, f.file_hash,
                    (SELECT COUNT(*) FROM peer_files pf
                        JOIN v_online_peers op ON op.peer_id = pf.peer_id
                        WHERE pf.file_id = f.file_id AND pf.is_sharing = TRUE) AS source_count
                FROM files f
                JOIN LATERAL (
                    SELECT a.file_name FROM file_aliases a
                    WHERE a.file_id = f.file_id AND a.file_name ILIKE ?
                    ORDER BY a.created_at LIMIT 1
                ) m ON TRUE
                WHERE f.publish_status = 'READY'
                ORDER BY m.file_name
                LIMIT ?
                """;
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, likePattern);
            statement.setInt(2, SEARCH_RESULT_LIMIT);
            try (ResultSet rs = statement.executeQuery()) {
                List<SearchResultItem> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new SearchResultItem(
                            rs.getLong("file_id"),
                            rs.getString("file_name"),
                            rs.getLong("file_size"),
                            rs.getString("file_hash"),
                            rs.getInt("source_count")));
                }
                return results;
            }
        }
    }

    @Override
    public Optional<String> findPublishStatus(long fileId) throws SQLException {
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT publish_status FROM files WHERE file_id = ?")) {
            statement.setLong(1, fileId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public void addAlias(long fileId, String fileName, UUID peerId) throws SQLException {
        try (Connection connection = connectionFactory.open();
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO file_aliases (file_id, file_name, first_peer_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """)) {
            statement.setLong(1, fileId);
            statement.setString(2, fileName);
            statement.setObject(3, peerId);
            statement.executeUpdate();
        }
    }
}
