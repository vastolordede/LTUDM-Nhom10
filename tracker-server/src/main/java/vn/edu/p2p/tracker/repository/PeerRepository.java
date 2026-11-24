package vn.edu.p2p.tracker.repository;

import vn.edu.p2p.tracker.domain.PeerRecord;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PeerRepository {
    Optional<PeerRecord> findById(UUID peerId) throws SQLException;

    void create(PeerRecord peer) throws SQLException;

    void updateLastLogin(UUID peerId, OffsetDateTime lastLoginAt) throws SQLException;

    Optional<String> findPublicKey(UUID peerId) throws SQLException;

    void updatePublicKey(UUID peerId, String publicKey) throws SQLException;

    /** First enrollment is allowed; changing an existing device key is not. */
    default boolean claimPublicKey(UUID peerId, String publicKey) throws SQLException {
        Optional<String> previous = findPublicKey(peerId);
        if (previous.isPresent()) return previous.get().equals(publicKey);
        updatePublicKey(peerId, publicKey);
        return true;
    }
}
