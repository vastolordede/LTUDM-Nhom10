-- R67: one completed folder event must reference one unique COMPLETED history row
-- for each member file. Never run on a production DB without a backup/review.
CREATE TABLE IF NOT EXISTS folder_download_evidence (
    group_transfer_id UUID NOT NULL REFERENCES folder_download_sessions(group_transfer_id)
        ON DELETE CASCADE,
    transfer_id UUID NOT NULL UNIQUE REFERENCES transfer_sessions(transfer_id)
        ON DELETE RESTRICT,
    PRIMARY KEY (group_transfer_id, transfer_id)
);
CREATE INDEX IF NOT EXISTS idx_folder_evidence_group ON folder_download_evidence(group_transfer_id);
