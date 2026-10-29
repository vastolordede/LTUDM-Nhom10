package vn.edu.p2p.tracker.catalog;

import vn.edu.p2p.common.dto.FolderDetailRequest;
import vn.edu.p2p.common.dto.FolderDetailResponse;
import vn.edu.p2p.common.dto.FolderEntryDto;
import vn.edu.p2p.common.dto.FolderFileInfo;
import vn.edu.p2p.common.dto.FolderSearchItem;
import vn.edu.p2p.common.dto.FolderSearchRequest;
import vn.edu.p2p.common.dto.FolderSearchResponse;
import vn.edu.p2p.common.dto.FolderShareRequest;
import vn.edu.p2p.common.dto.FolderShareResponse;
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
                || request.entries().isEmpty() || request.entries().size() > MAX_FILES) {
            throw new FileCatalogException("Invalid folder share (1.." + MAX_FILES + " files)");
        }
        UUID peerId = sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        Set<String> seen = new HashSet<>();
        for (FolderEntryDto e : request.entries()) {
            if (!isSafeRelative(e.relativePath()) || !seen.add(e.relativePath().toLowerCase(Locale.ROOT))) {
                throw new FileCatalogException("Invalid or duplicate path: " + e.relativePath());
            }
        }
        try (Connection c = connectionFactory.open()) {
            c.setAutoCommit(false);
            try {
                long groupId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO file_groups (group_name, owner_peer_id) VALUES (?, ?)
                        ON CONFLICT (owner_peer_id, group_name)
                        DO UPDATE SET created_at = CURRENT_TIMESTAMP
                        RETURNING group_id""")) {
                    ps.setString(1, request.groupName().trim());
                    ps.setObject(2, peerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        groupId = rs.getLong(1);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM group_files WHERE group_id = ?")) {
                    ps.setLong(1, groupId);
                    ps.executeUpdate();
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

    public FolderSearchResponse search(FolderSearchRequest request) throws FileCatalogException, SQLException {
        sessionPeerResolver.resolvePeerId(parse(request.sessionId()));
        String q = request.query() == null ? "" : request.query().trim();
        if (q.isEmpty()) throw new FileCatalogException("Search query must not be empty");
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String sql = """
                SELECT g.group_id, g.group_name,
                  (SELECT COUNT(*) FROM group_files x WHERE x.group_id = g.group_id) AS file_count,
                  (SELECT COALESCE(SUM(f.file_size), 0) FROM group_files x
                     JOIN files f ON f.file_id = x.file_id WHERE x.group_id = g.group_id) AS total_size,
                  (SELECT COUNT(*) FROM (
                     SELECT pf.peer_id FROM group_files x
                       JOIN peer_files pf ON pf.file_id = x.file_id AND pf.is_sharing = TRUE
                       JOIN v_online_peers op ON op.peer_id = pf.peer_id
                      WHERE x.group_id = g.group_id
                      GROUP BY pf.peer_id
                     HAVING COUNT(DISTINCT x.file_id) =
                            (SELECT COUNT(DISTINCT file_id) FROM group_files WHERE group_id = g.group_id)
                   ) t) AS source_count
                FROM file_groups g WHERE g.group_name ILIKE ?
                ORDER BY g.group_name LIMIT 50""";
        try (Connection c = connectionFactory.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, like);
            try (ResultSet rs = ps.executeQuery()) {
                List<FolderSearchItem> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new FolderSearchItem(rs.getLong("group_id"), rs.getString("group_name"),
                            rs.getInt("file_count"), rs.getLong("total_size"), rs.getInt("source_count")));
                }
                return new FolderSearchResponse(list);
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
            List<FolderFileInfo> files = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT x.file_id, x.relative_path, f.file_size, f.file_hash
                    FROM group_files x JOIN files f ON f.file_id = x.file_id
                    WHERE x.group_id = ? ORDER BY x.relative_path""")) {
                ps.setLong(1, request.groupId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        files.add(new FolderFileInfo(rs.getLong(1), rs.getString(2),
                                rs.getLong(3), rs.getString(4)));
                    }
                }
            }
            return new FolderDetailResponse(request.groupId(), name, files);
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