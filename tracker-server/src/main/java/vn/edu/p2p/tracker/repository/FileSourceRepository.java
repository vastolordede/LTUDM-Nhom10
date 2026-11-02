package vn.edu.p2p.tracker.repository;

import vn.edu.p2p.tracker.domain.FileSourceRecord;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileSourceRepository {
    /** Canonical catalog hash; implementation must reject non-READY files. */
    default Optional<String> findReadyCatalogHash(long fileId) throws SQLException {
        return Optional.empty();
    }
    List<FileSourceRecord> findOnlineSources(
            long fileId,
            UUID excludingPeerId
    ) throws SQLException;
}
