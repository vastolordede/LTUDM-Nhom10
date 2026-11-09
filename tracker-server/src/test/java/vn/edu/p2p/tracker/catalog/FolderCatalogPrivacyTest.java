package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.dto.FolderDetailRequest;
import vn.edu.p2p.common.dto.FolderEntryDto;
import vn.edu.p2p.common.dto.FolderShareRequest;
import vn.edu.p2p.common.dto.FolderSearchRequest;
import vn.edu.p2p.tracker.config.DatabaseConfig;
import vn.edu.p2p.tracker.config.DatabaseConnectionFactory;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 3, unit = TimeUnit.SECONDS)
class FolderCatalogPrivacyTest {
    @Test
    void folderShareRejectsAbsoluteLocalPathsWithoutEchoingThem() {
        FolderCatalogService service = new FolderCatalogService(ignored -> UUID.randomUUID(),
                new DatabaseConnectionFactory(new DatabaseConfig("jdbc:unavailable:", "", "")));
        String localPath = "C:/Users/private/report.pdf";

        FileCatalogException error = assertThrows(FileCatalogException.class, () -> service.share(
                new FolderShareRequest(UUID.randomUUID().toString(), "shared",
                        List.of(new FolderEntryDto(1, localPath)), List.of())));

        assertFalse(error.getMessage().contains(localPath));
    }

    @Test
    void folderSearchAndDetailOnlyExposeFilesWithOnlineActiveSources() throws Exception {
        PrivacyDriver driver = new PrivacyDriver();
        DriverManager.registerDriver(driver);
        try {
            FolderCatalogService service = new FolderCatalogService(
                    ignored -> UUID.randomUUID(),
                    new DatabaseConnectionFactory(new DatabaseConfig("jdbc:folder-privacy:", "", "")));

            var search = service.search(new FolderSearchRequest(UUID.randomUUID().toString(), "shared"));
            var detail = service.detail(new FolderDetailRequest(UUID.randomUUID().toString(), 7));

            assertEquals(List.of(7L), search.groups().stream().map(item -> item.groupId()).toList());
            assertEquals(1, search.groups().getFirst().fileCount());
            assertEquals(List.of(70L), detail.files().stream().map(file -> file.fileId()).toList());
            assertEquals("keep/a.txt", detail.files().getFirst().relativePath());
            assertEquals(List.of("keep"), detail.directories());
            // The unavailable file is counted without revealing its relative path.
            assertEquals(2, detail.totalFileCount());
            assertFalse(detail.complete());
            assertFalse(detail.files().stream().anyMatch(file -> file.relativePath().contains("secret")));
            assertTrue(driver.sql.stream().filter(sql -> sql.contains("FROM file_groups g"))
                    .allMatch(sql -> sql.contains("AND (EXISTS")
                            && sql.contains("pf.is_sharing = TRUE")
                            && sql.contains("v_online_peers")));
            assertTrue(driver.sql.stream().filter(sql -> sql.contains("SELECT x.file_id"))
                    .allMatch(sql -> sql.contains("pf.is_sharing = TRUE")
                            && sql.contains("v_online_peers")));

            driver.hasOnlineShares = false;
            assertTrue(service.search(new FolderSearchRequest(
                    UUID.randomUUID().toString(), "shared")).groups().isEmpty());
            assertThrows(FileCatalogException.class,
                    () -> service.detail(new FolderDetailRequest(UUID.randomUUID().toString(), 7)));
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    private static final class PrivacyDriver implements Driver {
        private final List<String> sql = new ArrayList<>();
        private boolean hasOnlineShares = true;

        @Override public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) return null;
            return proxy(Connection.class, (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")) {
                    String query = (String) args[0];
                    sql.add(query);
                    return statement(query);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement(String query) {
            return proxy(PreparedStatement.class, (proxy, method, args) -> {
                if (method.getName().equals("executeQuery")) return resultSet(rows(query));
                return defaultValue(method.getReturnType());
            });
        }

        private List<Map<String, Object>> rows(String query) {
            if (query.contains("SELECT group_name FROM file_groups")) {
                return List.of(row("group_name", "shared"));
            }
            if (query.contains("SELECT COUNT(*) FROM group_files WHERE group_id = ?")) {
                return List.of(row("total_file_count", 2));
            }
            if (query.contains("SELECT relative_path FROM group_files WHERE group_id = ?")) {
                return List.of(row("relative_path", "keep/a.txt"),
                        row("relative_path", "stale/secret.txt"));
            }
            if (query.contains("SELECT x.file_id")) {
                if (!hasOnlineShares) return List.of();
                if (query.contains("pf.is_sharing = TRUE") && query.contains("v_online_peers")) {
                    return List.of(row("file_id", 70L, "relative_path", "keep/a.txt",
                            "file_size", 12L, "file_hash", "a".repeat(64)));
                }
                return List.of(
                        row("file_id", 70L, "relative_path", "keep/a.txt",
                                "file_size", 12L, "file_hash", "a".repeat(64)),
                        row("file_id", 71L, "relative_path", "stale/secret.txt",
                                "file_size", 99L, "file_hash", "b".repeat(64)));
            }
            if (query.contains("SELECT relative_path FROM folder_directories")) {
                return List.of(row("relative_path", "keep"), row("relative_path", "stale"));
            }
            if (query.contains("FROM file_groups g")) {
                if (!hasOnlineShares) return List.of();
                if (query.contains("AND (EXISTS")
                        && query.contains("pf.is_sharing = TRUE")
                        && query.contains("v_online_peers")) {
                    return List.of(searchRow(7, "shared", 1, 12, 1));
                }
                return List.of(searchRow(7, "shared", 1, 12, 1),
                        searchRow(8, "empty", 0, 0, 0));
            }
            throw new IllegalStateException("Unexpected test query");
        }

        private static Map<String, Object> searchRow(long id, String name, int count, long size, int sources) {
            return row("group_id", id, "group_name", name, "file_count", count,
                    "total_size", size, "source_count", sources,
                    "completed_downloads", 0L, "created_at_epoch", 1L);
        }

        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:folder-privacy:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] cursor = {-1};
        return proxy(ResultSet.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "next" -> ++cursor[0] < rows.size();
                case "getString", "getLong", "getInt" -> {
                    Object key = args[0];
                    Object value = key instanceof Integer index
                            ? new ArrayList<>(rows.get(cursor[0]).values()).get(index - 1)
                            : rows.get(cursor[0]).get(key);
                    if (method.getName().equals("getString")) yield value == null ? null : value.toString();
                    if (method.getName().equals("getInt")) yield ((Number) value).intValue();
                    yield ((Number) value).longValue();
                }
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
