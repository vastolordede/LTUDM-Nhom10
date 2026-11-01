-- R67: count complete folder-download actions separately from individual file transfers.
-- Run only after review/backup on existing DB, after schema.sql and migrations v2..v5.
-- Idempotency is per group_transfer_id (same retry/resume identifier is not counted twice).
CREATE TABLE IF NOT EXISTS folder_download_sessions (
    group_transfer_id UUID PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES file_groups(group_id) ON DELETE RESTRICT,
    destination_peer_id UUID NOT NULL REFERENCES peers(peer_id) ON DELETE RESTRICT,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_folder_download_group ON folder_download_sessions(group_id);
