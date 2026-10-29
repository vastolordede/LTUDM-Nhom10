-- D72: nhóm file (thư mục chia sẻ) và đường dẫn tương đối
CREATE TABLE file_groups (
    group_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name VARCHAR(255) NOT NULL,
    owner_peer_id UUID NOT NULL REFERENCES peers(peer_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_peer_id, group_name)
);

CREATE TABLE group_files (
    group_id BIGINT NOT NULL REFERENCES file_groups(group_id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL REFERENCES files(file_id) ON DELETE RESTRICT,
    relative_path VARCHAR(1024) NOT NULL,
    PRIMARY KEY (group_id, relative_path)
);
CREATE INDEX idx_group_files_file ON group_files(file_id);