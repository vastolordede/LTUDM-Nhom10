package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.FolderDetailRequest;
import vn.edu.p2p.common.dto.FolderDetailResponse;
import vn.edu.p2p.common.dto.FolderEntryDto;
import vn.edu.p2p.common.dto.FolderFileInfo;
import vn.edu.p2p.common.dto.FolderSearchItem;
import vn.edu.p2p.common.dto.FolderSearchRequest;
import vn.edu.p2p.common.dto.FolderSearchResponse;
import vn.edu.p2p.common.dto.FolderShareRequest;
import vn.edu.p2p.common.dto.FolderShareResponse;
import vn.edu.p2p.common.dto.FolderUnshareRequest;
import vn.edu.p2p.common.dto.FolderUnshareResponse;
import vn.edu.p2p.common.dto.RecordFolderDownloadRequest;
import vn.edu.p2p.common.dto.RecordFolderDownloadResponse;
import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** D64, D72, D73: nhóm file (thư mục chia sẻ) kèm đường dẫn tương đối. */
public final class FolderCatalogService {
    private static final int MAX_FILES = 2000;
    private final SessionPeerResolver sessionPeerResolver;
    private final DatabaseConnectionFactory connectionFactory;

    public FolderCatalogService(SessionPeerResolver sessionPeerResolver,
                                DatabaseConnectionFactory connectionFactory) {
        this.sessionPeerResolver = sessionPeerResolver;
        this.connectionFactory = connectionFactory;
    }

    public FolderShareResponse share(FolderShareRequest request) throws FileCatalogException, SQLException {
        if (request == null || request.groupName() == null || request.groupName().isBlank()
                || request.groupName().length() > 255 || request.entries() == null
                || request.entries().size() > MAX_FILES
                || request.directories().size() > 4000) {
            throw new FileCatalogException("Invalid folder share (1.." + MAX_FILES + " files)");
        }
        UUID peerId = sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        Set<String> seen = new HashSet<>();
        for (String directory : request.directories()) {
            if (!isSafeRelative(directory) || directory.length() > 1024
                    || !seen.add("dir:" + directory.toLowerCase(Locale.ROOT))) {
                throw new FileCatalogException("Invalid or duplicate folder directory");
            }
        }
        for (FolderEntryDto e : request.entries()) {
            if (!isSafeRelative(e.relativePath()) || !seen.add(e.relativePath().toLowerCase(Locale.ROOT))) {
                throw new FileCatalogException("Invalid or duplicate folder path");
            }
        }
        try (Connection c = connectionFactory.open()) {
            c.setAutoCommit(false);
            try {
                long groupId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO file_groups (group_name, owner_peer_id) VALUES (?, ?)
                        ON CONFLICT (owner_peer_id, group_name) DO NOTHING
                        RETURNING group_id""")) {
                    ps.setString(1, request.groupName().trim());
                    ps.setObject(2, peerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) groupId = rs.getLong(1);
                        else groupId = 0L;
                    }
                }
                if (groupId == 0L) {
                    try (PreparedStatement existing = c.prepareStatement("""
                            SELECT group_id FROM file_groups
                            WHERE owner_peer_id = ? AND group_name = ? FOR UPDATE""")) {
                        existing.setObject(1, peerId);
                        existing.setString(2, request.groupName().trim());
                        try (ResultSet rs = existing.executeQuery()) {
                            if (!rs.next()) throw new FileCatalogException("Folder conflict changed concurrently");
                            groupId = rs.getLong(1);
                        }
                    }
                    try (PreparedStatement check = c.prepareStatement("""
                            SELECT EXISTS(SELECT 1 FROM group_files WHERE group_id = ?)
                              OR EXISTS(SELECT 1 FROM folder_directories WHERE group_id = ?)""")) {
                        check.setLong(1, groupId);
                        check.setLong(2, groupId);
                        try (ResultSet rs = check.executeQuery()) {
                            rs.next();
                            if (rs.getBoolean(1)) {
                                throw new FileCatalogException("Folder name already shared by this Peer. "
                                        + "Unshare it first or choose a different name; existing files were not replaced.");
                            }
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM group_files WHERE group_id = ?")) {
                    ps.setLong(1, groupId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM folder_directories WHERE group_id = ?")) {
                    ps.setLong(1, groupId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO folder_directories(group_id, relative_path) VALUES (?, ?)""")) {
                    for (String directory : request.directories()) {
                        ps.setLong(1, groupId);
                        ps.setString(2, directory);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement own = c.prepareStatement(
                             "SELECT 1 FROM peer_files WHERE peer_id = ? AND file_id = ? AND is_sharing = TRUE");
                     PreparedStatement ins = c.prepareStatement(
                             "INSERT INTO group_files (group_id, file_id, relative_path) VALUES (?, ?, ?)")) {
                    for (FolderEntryDto e : request.entries()) {
                        own.setObject(1, peerId);
                        own.setLong(2, e.fileId());
                        try (ResultSet rs = own.executeQuery()) {
                            if (!rs.next()) {
                                throw new FileCatalogException("Peer is not sharing fileId=" + e.fileId());
                            }
                        }
                        ins.setLong(1, groupId);
                        ins.setLong(2, e.fileId());
                        ins.setString(3, e.relativePath());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                c.commit();
                return new FolderShareResponse(groupId, request.entries().size());
            } catch (SQLException | FileCatalogException | RuntimeException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** R94: remove folder associations without touching independently shared file records.
     *  Historical folder download sessions remain referenced by file_groups.
     */
    public FolderUnshareResponse unshare(FolderUnshareRequest request)
            throws FileCatalogException, SQLException {
        if (request == null || request.groupId() <= 0) {
            throw new FileCatalogException("Invalid folder group ID");
        }
        UUID peerId = sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        try (Connection c = connectionFactory.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement owner = c.prepareStatement("""
                        SELECT 1 FROM file_groups WHERE group_id = ? AND owner_peer_id = ? FOR UPDATE""")) {
                    owner.setLong(1, request.groupId());
                    owner.setObject(2, peerId);
                    try (ResultSet rs = owner.executeQuery()) {
                        if (!rs.next()) throw new FileCatalogException("Folder not found or not owned by this Peer");
                    }
                }
                int removed = 0;
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM group_files WHERE group_id = ?")) {
                    del.setLong(1, request.groupId());
                    removed += del.executeUpdate();
                }
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM folder_directories WHERE group_id = ?")) {
                    del.setLong(1, request.groupId());
                    removed += del.executeUpdate();
                }
                c.commit();
                return new FolderUnshareResponse(request.groupId(), removed > 0);
            } catch (Exception ex) {
                c.rollback();
                if (ex instanceof SQLException e) throw e;
                if (ex instanceof FileCatalogException e) throw e;
                throw new SQLException("Folder unshare transaction failed", ex);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public FolderSearchResponse search(FolderSearchRequest request) throws FileCatalogException, SQLException {
        sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        String q = request.query() == null ? "" : request.query().trim();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String orderBy = switch (request.sort()) {
            case POPULARITY -> "completed_downloads DESC, g.created_at DESC, g.group_id DESC";
            case NAME_ASC -> "g.group_name ASC, g.group_id DESC";
            case NAME_DESC -> "g.group_name DESC, g.group_id DESC";
            case DATE_NEWEST -> "g.created_at DESC, g.group_id DESC";
            case DATE_OLDEST -> "g.created_at ASC, g.group_id ASC";
            case SIZE_ASC -> "total_size ASC, g.group_id DESC";
            case SIZE_DESC -> "total_size DESC, g.group_id DESC";
        };
        String sql = """
                SELECT g.group_id, g.group_name,
                  (SELECT COUNT(*) FROM group_files x
                     JOIN peer_files pf ON pf.file_id = x.file_id AND pf.is_sharing = TRUE
                     JOIN v_online_peers op ON op.peer_id = pf.peer_id
                     WHERE x.group_id = g.group_id) AS file_count,
                  (SELECT COALESCE(SUM(f.file_size), 0) FROM group_files x
                      JOIN files f ON f.file_id = x.file_id
                      WHERE x.group_id = g.group_id
                        AND EXISTS (SELECT 1 FROM peer_files pf
                          JOIN v_online_peers op ON op.peer_id = pf.peer_id
                          WHERE pf.file_id = x.file_id AND pf.is_sharing = TRUE)) AS total_size,
                  (SELECT COUNT(*) FROM (
                     SELECT pf.peer_id FROM group_files x
                       JOIN peer_files pf ON pf.file_id = x.file_id AND pf.is_sharing = TRUE
                       JOIN v_online_peers op ON op.peer_id = pf.peer_id
                      WHERE x.group_id = g.group_id
                      GROUP BY pf.peer_id
                     HAVING COUNT(DISTINCT x.file_id) =
                            (SELECT COUNT(DISTINCT ax.file_id) FROM group_files ax
                               JOIN peer_files apf ON apf.file_id = ax.file_id AND apf.is_sharing = TRUE
                               JOIN v_online_peers aop ON aop.peer_id = apf.peer_id
                              WHERE ax.group_id = g.group_id)
                   ) t) +
                  (CASE WHEN NOT EXISTS (SELECT 1 FROM group_files xf WHERE xf.group_id = g.group_id)
                       AND EXISTS (SELECT 1 FROM v_online_peers op WHERE op.peer_id = g.owner_peer_id)
                    THEN 1 ELSE 0 END) AS source_count,
                  (EXTRACT(EPOCH FROM g.created_at) * 1000)::bigint AS created_at_epoch,
                  (SELECT COUNT(*) FROM folder_download_sessions fd
                    WHERE fd.group_id = g.group_id) AS completed_downloads
                FROM file_groups g
                WHERE g.group_name ILIKE ?
                  AND (EXISTS (SELECT 1 FROM group_files x
                    JOIN peer_files pf ON pf.file_id = x.file_id AND pf.is_sharing = TRUE
                    JOIN v_online_peers op ON op.peer_id = pf.peer_id
                    WHERE x.group_id = g.group_id)
                    OR (NOT EXISTS (SELECT 1 FROM group_files x WHERE x.group_id = g.group_id)
                        AND EXISTS (SELECT 1 FROM folder_directories d WHERE d.group_id = g.group_id)
                        AND EXISTS (SELECT 1 FROM v_online_peers op WHERE op.peer_id = g.owner_peer_id)))
                ORDER BY %s
                LIMIT ? OFFSET ?""".formatted(orderBy);
        try (Connection c = connectionFactory.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setInt(2, request.pageSize());
            ps.setLong(3, (long) request.page() * request.pageSize());
            try (ResultSet rs = ps.executeQuery()) {
                List<FolderSearchItem> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new FolderSearchItem(rs.getLong("group_id"), rs.getString("group_name"),
                            rs.getInt("file_count"), rs.getLong("total_size"), rs.getInt("source_count"),
                            rs.getLong("completed_downloads"), rs.getLong("created_at_epoch")));
                }
                return new FolderSearchResponse(list);
            }
        }
    }

    /** R67: count a folder only after matching completed child transfers exist on Tracker. */
    public RecordFolderDownloadResponse recordCompletedDownload(RecordFolderDownloadRequest request)
            throws FileCatalogException, SQLException {
        if (request == null || request.groupId() <= 0 || request.groupTransferId() == null
                || request.fileTransferIds() == null
                || request.fileTransferIds().size() > MAX_FILES) {
            throw new FileCatalogException("Invalid completed folder evidence");
        }
        UUID groupTransferId;
        List<UUID> fileTransferIds = new ArrayList<>();
        Set<UUID> unique = new HashSet<>();
        try {
            groupTransferId = UUID.fromString(request.groupTransferId());
            for (String value : request.fileTransferIds()) {
                UUID parsedId = UUID.fromString(value);
                if (!unique.add(parsedId)) throw new FileCatalogException("Duplicate transfer evidence");
                fileTransferIds.add(parsedId);
            }
        } catch (IllegalArgumentException e) {
            throw new FileCatalogException("Malformed transfer UUID");
        }
        UUID peerId = sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        try (Connection c = connectionFactory.open()) {
            c.setAutoCommit(false);
            try {
                // An identical successful retry is idempotent; a reused UUID may not switch owner/group.
                try (PreparedStatement previous = c.prepareStatement("""
                        SELECT group_id, destination_peer_id FROM folder_download_sessions
                        WHERE group_transfer_id = ?""")) {
                    previous.setObject(1, groupTransferId);
                    try (ResultSet rs = previous.executeQuery()) {
                        if (rs.next()) {
                            if (rs.getLong(1) != request.groupId()
                                    || !peerId.equals(rs.getObject(2, UUID.class))) {
                                throw new FileCatalogException("Folder transfer UUID belongs to another peer/group");
                            }
                            c.commit();
                            return new RecordFolderDownloadResponse(request.groupId(), false);
                        }
                    }
                }
                try (PreparedStatement present = c.prepareStatement(
                        "SELECT 1 FROM file_groups WHERE group_id = ?")) {
                    present.setLong(1, request.groupId());
                    try (ResultSet rs = present.executeQuery()) {
                        if (!rs.next()) throw new FileCatalogException("Folder not found");
                    }
                }
                List<Long> expectedFiles = new ArrayList<>();
                try (PreparedStatement files = c.prepareStatement("""
                        SELECT file_id FROM group_files WHERE group_id = ? ORDER BY relative_path""")) {
                    files.setLong(1, request.groupId());
                    try (ResultSet rs = files.executeQuery()) {
                        while (rs.next()) expectedFiles.add(rs.getLong(1));
                    }
                }
                if (expectedFiles.size() != fileTransferIds.size()) {
                    throw new FileCatalogException("Folder evidence must cover every file exactly once");
                }
                try (PreparedStatement verify = c.prepareStatement("""
                        SELECT 1 FROM transfer_sessions ts JOIN files f ON f.file_id = ts.file_id
                        WHERE ts.transfer_id = ? AND ts.file_id = ? AND ts.destination_peer_id = ?
                          AND ts.status = 'COMPLETED' AND ts.completed_at IS NOT NULL
                          AND ts.bytes_received >= f.file_size""")) {
                    for (int i = 0; i < expectedFiles.size(); i++) {
                        verify.setObject(1, fileTransferIds.get(i));
                        verify.setLong(2, expectedFiles.get(i));
                        verify.setObject(3, peerId);
                        try (ResultSet rs = verify.executeQuery()) {
                            if (!rs.next()) {
                                throw new FileCatalogException("Missing verified child transfer for file "
                                        + expectedFiles.get(i));
                            }
                        }
                    }
                }
                try (PreparedStatement insert = c.prepareStatement("""
                        INSERT INTO folder_download_sessions(group_transfer_id, group_id, destination_peer_id)
                        VALUES (?, ?, ?)""")) {
                    insert.setObject(1, groupTransferId);
                    insert.setLong(2, request.groupId());
                    insert.setObject(3, peerId);
                    insert.executeUpdate();
                }
                try (PreparedStatement evidence = c.prepareStatement("""
                        INSERT INTO folder_download_evidence(group_transfer_id, transfer_id)
                        VALUES (?, ?)""")) {
                    for (UUID transferId : fileTransferIds) {
                        evidence.setObject(1, groupTransferId);
                        evidence.setObject(2, transferId);
                        evidence.addBatch();
                    }
                    evidence.executeBatch();
                }
                c.commit();
                return new RecordFolderDownloadResponse(request.groupId(), true);
            } catch (SQLException | RuntimeException | FileCatalogException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public FolderDetailResponse detail(FolderDetailRequest request) throws FileCatalogException, SQLException {
        sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        try (Connection c = connectionFactory.open()) {
            String name;
            try (PreparedStatement ps = c.prepareStatement("SELECT group_name FROM file_groups WHERE group_id = ?")) {
                ps.setLong(1, request.groupId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new FileCatalogException("Folder not found");
                    name = rs.getString(1);
                }
            }
            int totalFileCount;
            try (PreparedStatement count = c.prepareStatement(
                    "SELECT COUNT(*) FROM group_files WHERE group_id = ?")) {
                count.setLong(1, request.groupId());
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    totalFileCount = rs.getInt(1);
                }
            }
            List<FolderFileInfo> files = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT x.file_id, x.relative_path, f.file_size, f.file_hash
                    FROM group_files x JOIN files f ON f.file_id = x.file_id
                    WHERE x.group_id = ?
                      AND EXISTS (SELECT 1 FROM peer_files pf
                        JOIN v_online_peers op ON op.peer_id = pf.peer_id
                        WHERE pf.file_id = x.file_id AND pf.is_sharing = TRUE)
                    ORDER BY x.relative_path""")) {
                ps.setLong(1, request.groupId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        files.add(new FolderFileInfo(rs.getLong(1), rs.getString(2),
                                rs.getLong(3), rs.getString(4)));
                    }
                }
            }
            // Incomplete folders are visible only through available children.
            // Fully empty groups are allowed if they have at least one directory and the owner is online.
            if (files.isEmpty() && totalFileCount > 0) {
                throw new FileCatalogException("Folder has no online file sources");
            }
            // Keep empty branches, but do not disclose directory names whose only
            // descendants are unavailable/offline file paths.
            List<String> allSharedPaths = new ArrayList<>();
            try (PreparedStatement paths = c.prepareStatement(
                    "SELECT relative_path FROM group_files WHERE group_id = ?")) {
                paths.setLong(1, request.groupId());
                try (ResultSet rs = paths.executeQuery()) {
                    while (rs.next()) allSharedPaths.add(rs.getString(1));
                }
            }
            List<String> directories = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT relative_path FROM folder_directories
                    WHERE group_id = ? ORDER BY relative_path""")) {
                ps.setLong(1, request.groupId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String directory = rs.getString(1);
                        boolean directoryContainsFiles = allSharedPaths.stream().anyMatch(
                                path -> path.startsWith(directory + "/"));
                        boolean hasOnlineDescendant = files.stream().anyMatch(
                                file -> file.relativePath().startsWith(directory + "/"));
                        if (!directoryContainsFiles || hasOnlineDescendant) {
                            directories.add(directory);
                        }
                    }
                }
            }
            if (totalFileCount == 0 && directories.isEmpty()) {
                throw new FileCatalogException("Folder has no shared entries");
            }
            return new FolderDetailResponse(request.groupId(), name, files, directories,
                    totalFileCount, files.size() == totalFileCount);
        }
    }

    private static boolean isSafeRelative(String p) {
        if (p == null || p.isBlank() || p.length() > 1024 || p.startsWith("/")
                || p.contains("\\") || p.contains(":")) return false;
        for (String part : p.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private static UUID parse(String s) throws FileCatalogException {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw new FileCatalogException("Invalid sessionId");
        }
    }
}