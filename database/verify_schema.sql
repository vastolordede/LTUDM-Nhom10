-- Read-only catalog checks for predeploy review (psql -v ON_ERROR_STOP=1 -f ...)
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(t.name, ', ') INTO missing
    FROM (VALUES ('users'),('peers'),('peer_sessions'),('files'),('file_pieces'),
                 ('peer_files'),('share_events'),('transfer_sessions'),('transfer_sources'),
                 ('file_aliases'),('file_groups'),('group_files')) AS t(name)
    WHERE to_regclass('public.' || t.name) IS NULL;
    IF missing IS NOT NULL THEN RAISE EXCEPTION 'Missing required tables: %', missing; END IF;
    IF to_regclass('public.v_file_sources') IS NULL THEN
        RAISE EXCEPTION 'Missing v_file_sources view';
    END IF;
END $$;
SELECT publish_status, COUNT(*) AS file_count FROM files GROUP BY publish_status ORDER BY 1;
SELECT COUNT(*) AS ready_files_missing_pieces
FROM files f WHERE f.publish_status='READY' AND f.piece_count <>
       (SELECT COUNT(*) FROM file_pieces fp WHERE fp.file_id = f.file_id);
