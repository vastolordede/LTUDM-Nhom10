package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.SearchResultItem;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRepository {

    /** T13: duplicate detection - find an existing file by its SHA-256 hash. */
    Optional<Long> findFileIdByHash(String fileHash) throws SQLException;

    /** T11-T12: insert a new file plus its pieces (files, file_pieces). Returns the new file_id. */
    long insert(FileMetadata metadata) throws SQLException;

    /** Add bounded pieces for large files. Default preserves existing test fakes. */
    default void upsertPiecePage(long fileId, List<PieceInfo> pieces) throws SQLException {
        throw new UnsupportedOperationException("Paged metadata needs a JDBC repository");
    }

    /** T16-T17: search the catalog by (partial) file name, with each row's current source count. */
    List<SearchResultItem> searchByName(String likePattern) throws SQLException;


    default Optional<String> findPublishStatus(long fileId) throws SQLException {
        return Optional.empty();
    }

    default void addAlias(long fileId, String fileName, UUID peerId) throws SQLException {
    }
}
