package vn.edu.p2p.tracker.catalog;

import java.sql.SQLException;
import java.util.UUID;

public interface PeerFileRepository {

    /**
     * T14-T15: record that a peer owns/shares a file (peer_files table),
     * or refresh its status if the (peer_id, file_id) row already exists.
     */
    void upsertOwnership(UUID peerId, long fileId, String availabilityStatus) throws SQLException;

    /** T22: stop/resume sharing without touching availability_status or the catalog row. */
    void setSharing(UUID peerId, long fileId, boolean sharing) throws SQLException;

    default boolean isSharing(UUID peerId, long fileId) throws SQLException { return false; }

    default void demoteIfPartial(UUID peerId, long fileId) throws SQLException {}
}
