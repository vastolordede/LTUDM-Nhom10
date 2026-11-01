-- R46: preserve nested empty directories in shared folder metadata.
-- Requires migration_v4 and migration_v7; apply only on reviewed DB backup.
CREATE TABLE IF NOT EXISTS folder_directories (
    group_id BIGINT NOT NULL REFERENCES file_groups(group_id) ON DELETE CASCADE,
    relative_path VARCHAR(1024) NOT NULL,
    PRIMARY KEY (group_id, relative_path)
);
