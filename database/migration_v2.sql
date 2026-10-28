-- D69: trạng thái publish metadata
ALTER TABLE files
    ADD COLUMN publish_status VARCHAR(12) NOT NULL DEFAULT 'READY'
        CHECK (publish_status IN ('PUBLISHING', 'READY'));
CREATE INDEX idx_files_publish_status ON files(publish_status);

-- D71: nhiều tên cho cùng một SHA-256
CREATE TABLE file_aliases (
    file_id BIGINT NOT NULL REFERENCES files(file_id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    first_peer_id UUID REFERENCES peers(peer_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id, file_name)
);
CREATE INDEX idx_file_aliases_name ON file_aliases(LOWER(file_name));

INSERT INTO file_aliases (file_id, file_name)
SELECT file_id, file_name FROM files
ON CONFLICT DO NOTHING;

-- B04: bảng xếp hạng peer (điểm có thể chỉnh trọng số)
CREATE VIEW v_peer_ranking AS
SELECT
    ts.source_peer_id AS peer_id,
    COUNT(*) AS transfers,
    COALESCE(SUM(ts.bytes_received), 0) AS uploaded_bytes,
    COALESCE(SUM(ts.pieces_received), 0) AS uploaded_pieces,
    COUNT(*) FILTER (WHERE ts.source_status = 'COMPLETED') AS ok_transfers,
    COUNT(*) FILTER (WHERE ts.source_status = 'FAILED') AS failed_transfers,
    COUNT(*) FILTER (WHERE ts.source_status = 'DISCONNECTED') AS dropped_transfers,
    ROUND(
        COALESCE(SUM(ts.bytes_received), 0) / 1048576.0
        + 10 * COUNT(*) FILTER (WHERE ts.source_status = 'COMPLETED')
        - 20 * COUNT(*) FILTER (WHERE ts.source_status = 'FAILED')
        - 5  * COUNT(*) FILTER (WHERE ts.source_status = 'DISCONNECTED'),
    2) AS score
FROM transfer_sources ts
GROUP BY ts.source_peer_id;

-- Dọn file PUBLISHING quá 1 giờ chưa đủ piece (chạy định kỳ hoặc thủ công)
DELETE FROM files f
WHERE f.publish_status = 'PUBLISHING'
  AND f.created_at < CURRENT_TIMESTAMP - INTERVAL '1 hour'
  AND NOT EXISTS (SELECT 1 FROM transfer_sessions t WHERE t.file_id = f.file_id)
  AND NOT EXISTS (SELECT 1 FROM share_events s WHERE s.file_id = f.file_id);