-- Repair predeploy paginated metadata published before READY finalization was fixed.
-- Safe to re-run. Incomplete files remain PUBLISHING and are not advertised.
-- Run after migration_v4.sql, then deploy the Java page finalization fix.
UPDATE files AS f
SET publish_status = 'READY'
WHERE f.publish_status = 'PUBLISHING'
  AND f.piece_count = (
      SELECT COUNT(*) FROM file_pieces AS fp WHERE fp.file_id = f.file_id
  )
  AND NOT EXISTS (
      SELECT 1 FROM file_pieces AS fp
      WHERE fp.file_id = f.file_id
        AND (fp.piece_index < 0 OR fp.piece_index >= f.piece_count
             OR fp.piece_length <> LEAST(f.piece_size,
                      f.file_size - fp.piece_index::BIGINT * f.piece_size)
             OR fp.piece_hash IS NULL)
  );
