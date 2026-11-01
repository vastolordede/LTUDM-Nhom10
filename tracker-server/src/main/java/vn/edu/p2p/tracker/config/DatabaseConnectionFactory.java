package vn.edu.p2p.tracker.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseConnectionFactory {
    private final DatabaseConfig config;

    public DatabaseConnectionFactory(DatabaseConfig config) {
        this.config = config;
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(
                config.url(),
                config.username(),
                config.password()
        );
    }

    public void verify() throws SQLException {
        try (Connection connection = open();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1")) {
            if (!rs.next() || rs.getInt(1) != 1) {
                throw new SQLException("Database verification failed");
            }
        }
        verifyRequiredSchema();
    }

    /** R47: block launching a Tracker against a DB missing mandatory migrations. */
    public void verifyRequiredSchema() throws SQLException {
        String[] required = {
                "users", "peers", "peer_sessions", "files", "file_pieces", "peer_files",
                "share_events", "transfer_sessions", "transfer_sources", "file_aliases",
                "file_groups", "group_files", "folder_download_sessions",
                "v_online_peers", "v_file_sources"
        };
        try (Connection connection = open();
             PreparedStatement query = connection.prepareStatement("SELECT to_regclass(?)")) {
            for (String table : required) {
                query.setString(1, "public." + table);
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next() || rs.getString(1) == null) {
                        throw new SQLException("Database migration is missing: " + table
                                + ". Apply schema.sql and migration_v2..v6 in order.\n"
                                + "Do NOT re-run non-idempotent migrations on populated DBs.");
                    }
                }
            }
            try (PreparedStatement check = connection.prepareStatement("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'files'
                      AND column_name = 'publish_status'""")) {
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != 1) {
                        throw new SQLException("Migration v2 required: files.publish_status is missing");
                    }
                }
            }
        }
    }
}
