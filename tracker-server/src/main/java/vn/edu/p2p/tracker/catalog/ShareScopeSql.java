package vn.edu.p2p.tracker.catalog;

import java.sql.*;
import java.util.UUID;

/** v9: latest explicit SHARE_START/STOP plus active folder references. No schema migration. */
final class ShareScopeSql {
    private ShareScopeSql() {}
    static boolean stopIfUnreferenced(Connection c, UUID peerId, long fileId) throws SQLException {
        String sql = """
                UPDATE peer_files pf SET is_sharing = FALSE, updated_at = CURRENT_TIMESTAMP
                WHERE pf.peer_id = ? AND pf.file_id = ? AND pf.is_sharing = TRUE
                  AND NOT EXISTS (SELECT 1 FROM group_files gf JOIN file_groups g ON g.group_id = gf.group_id
                                  WHERE g.owner_peer_id = pf.peer_id AND gf.file_id = pf.file_id)
                  AND COALESCE((SELECT se.event_type FROM share_events se
                                WHERE se.peer_id = pf.peer_id AND se.file_id = pf.file_id
                                ORDER BY se.share_event_id DESC LIMIT 1), 'SHARE_STOP') <> 'SHARE_START'
                """;
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setObject(1, peerId); st.setLong(2, fileId);
            return st.executeUpdate() == 1;
        }
    }
}
