package vn.edu.p2p.tracker.repository;

import vn.edu.p2p.tracker.domain.PeerSessionRecord;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface PeerSessionRepository {
    void create(PeerSessionRecord session) throws SQLException;

    Optional<PeerSessionRecord> findBySessionId(UUID sessionId) throws SQLException;

    int closeActiveForPeer(UUID peerId, OffsetDateTime closedAt) throws SQLException;

    /**
     * Replace the current ONLINE session for a Peer with a new session.
     *
     * JDBC implementations should override this method and perform the close + insert
     * in one transaction while serializing on the Peer row. The default implementation
     * keeps lightweight/in-memory test repositories source-compatible.
     */
    default int replaceActiveForPeer(
            PeerSessionRecord newSession,
            OffsetDateTime closedAt
    ) throws SQLException {
        int replaced = closeActiveForPeer(newSession.peerId(), closedAt);
        create(newSession);
        return replaced;
    }

    boolean updateLastSeen(UUID sessionId, OffsetDateTime lastSeen) throws SQLException;

    boolean markLoggedOut(UUID sessionId, OffsetDateTime logoutAt) throws SQLException;

    int expireStaleSessions(OffsetDateTime staleBefore) throws SQLException;

    /**
     * Expires stale online sessions and returns the sessions changed by that operation.
     * JDBC repositories override this atomically; the default keeps lightweight fakes compatible.
     */
    default List<PeerSessionRecord> expireStaleSessionsAndFind(OffsetDateTime staleBefore)
            throws SQLException {
        expireStaleSessions(staleBefore);
        return List.of();
    }
}
