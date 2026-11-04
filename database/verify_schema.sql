-- Finalize and verify schema v9 (psql -v ON_ERROR_STOP=1 -f ...).
-- This writes the version marker only after all structural/data checks pass.
DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(required.name, ', ') INTO missing
    FROM (VALUES ('users'), ('peers'), ('peer_sessions'), ('files'), ('file_pieces'),
                 ('peer_files'), ('share_events'), ('transfer_sessions'), ('transfer_sources'),
                 ('file_aliases'), ('v_peer_ranking'), ('v_online_peers'), ('v_file_sources'),
                 ('file_groups'), ('group_files'), ('folder_download_sessions'),
                 ('folder_download_evidence'), ('folder_directories')) AS required(name)
    WHERE to_regclass('public.' || required.name) IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Schema verification failed; missing relations: %', missing;
    END IF;

    SELECT string_agg(required.table_name || '.' || required.column_name, ', ') INTO missing
    FROM (VALUES
        ('users', 'user_id'), ('users', 'username'), ('users', 'password_hash'),
        ('users', 'account_status'), ('users', 'created_at'),
        ('peers', 'peer_id'), ('peers', 'user_id'), ('peers', 'device_name'),
        ('peers', 'public_key'), ('peers', 'created_at'), ('peers', 'last_login_at'),
        ('peer_sessions', 'session_id'), ('peer_sessions', 'peer_id'),
        ('peer_sessions', 'ip_address'), ('peer_sessions', 'listening_port'),
        ('peer_sessions', 'status'), ('peer_sessions', 'login_at'),
        ('peer_sessions', 'last_seen'), ('peer_sessions', 'logout_at'),
        ('files', 'file_id'), ('files', 'file_name'), ('files', 'file_size'),
        ('files', 'file_hash'), ('files', 'piece_size'), ('files', 'piece_count'),
        ('files', 'metadata_version'), ('files', 'created_at'), ('files', 'publish_status'),
        ('file_pieces', 'file_id'), ('file_pieces', 'piece_index'),
        ('file_pieces', 'piece_length'), ('file_pieces', 'piece_hash'),
        ('peer_files', 'peer_id'), ('peer_files', 'file_id'),
        ('peer_files', 'availability_status'), ('peer_files', 'is_sharing'),
        ('peer_files', 'announced_at'), ('peer_files', 'updated_at'),
        ('peer_files', 'completed_at'),
        ('share_events', 'share_event_id'), ('share_events', 'peer_id'),
        ('share_events', 'file_id'), ('share_events', 'event_type'),
        ('share_events', 'occurred_at'),
        ('transfer_sessions', 'transfer_id'), ('transfer_sessions', 'file_id'),
        ('transfer_sessions', 'destination_peer_id'), ('transfer_sessions', 'status'),
        ('transfer_sessions', 'bytes_received'), ('transfer_sessions', 'started_at'),
        ('transfer_sessions', 'completed_at'), ('transfer_sessions', 'failure_reason'),
        ('transfer_sources', 'transfer_id'), ('transfer_sources', 'source_peer_id'),
        ('transfer_sources', 'bytes_received'), ('transfer_sources', 'pieces_received'),
        ('transfer_sources', 'source_status'), ('transfer_sources', 'connected_at'),
        ('transfer_sources', 'disconnected_at'),
        ('file_aliases', 'file_id'), ('file_aliases', 'file_name'),
        ('file_aliases', 'first_peer_id'), ('file_aliases', 'created_at'),
        ('file_groups', 'group_id'), ('file_groups', 'group_name'),
        ('file_groups', 'owner_peer_id'), ('file_groups', 'created_at'),
        ('group_files', 'group_id'), ('group_files', 'file_id'),
        ('group_files', 'relative_path'),
        ('folder_download_sessions', 'group_transfer_id'),
        ('folder_download_sessions', 'group_id'),
        ('folder_download_sessions', 'destination_peer_id'),
        ('folder_download_sessions', 'completed_at'),
        ('folder_download_evidence', 'group_transfer_id'),
        ('folder_download_evidence', 'transfer_id'),
        ('folder_directories', 'group_id'), ('folder_directories', 'relative_path')
    ) AS required(table_name, column_name)
    WHERE NOT EXISTS (
        SELECT 1 FROM information_schema.columns actual
        WHERE actual.table_schema = 'public'
          AND actual.table_name = required.table_name
          AND actual.column_name = required.column_name
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Schema verification failed; missing columns: %', missing;
    END IF;

    SELECT string_agg(required.name, ', ') INTO missing
    FROM (VALUES ('uq_users_username_ci'), ('idx_peers_user_id'),
                 ('idx_peer_sessions_peer_id'), ('idx_peer_sessions_status'),
                 ('idx_peer_sessions_last_seen'),
                 ('uq_peer_single_online_session'), ('uq_files_hash'),
                 ('idx_files_name'), ('idx_files_publish_status'), ('idx_file_aliases_name'),
                 ('idx_peer_files_file_id'), ('idx_peer_files_available'),
                 ('idx_share_events_peer'), ('idx_share_events_file'),
                 ('idx_share_events_time'), ('idx_transfer_file'),
                 ('idx_transfer_destination'), ('idx_transfer_status'),
                 ('idx_transfer_started_at'), ('idx_transfer_sources_peer'),
                 ('idx_group_files_file'), ('idx_folder_download_group'),
                 ('idx_folder_evidence_group')) AS required(name)
    WHERE to_regclass('public.' || required.name) IS NULL;
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Schema verification failed; missing indexes: %', missing;
    END IF;

    SELECT string_agg(required.name, ', ') INTO missing
    FROM (VALUES ('users_pkey'), ('peers_pkey'), ('peer_sessions_pkey'), ('files_pkey'),
                 ('file_pieces_pkey'), ('peer_files_pkey'), ('share_events_pkey'),
                 ('transfer_sessions_pkey'), ('transfer_sources_pkey'),
                 ('file_groups_pkey'), ('file_groups_owner_peer_id_group_name_key'),
                 ('group_files_pkey'), ('folder_download_sessions_pkey'),
                 ('folder_download_evidence_pkey'), ('folder_download_evidence_transfer_id_key'),
                 ('folder_directories_pkey'),
                 ('users_account_status_check'), ('peer_sessions_listening_port_check'),
                 ('peer_sessions_status_check'), ('files_file_size_check'),
                 ('files_piece_size_check'), ('files_piece_count_check'),
                 ('files_metadata_version_check'), ('ck_files_sha256'),
                 ('file_pieces_piece_index_check'), ('file_pieces_piece_length_check'),
                 ('ck_piece_sha256'), ('peer_files_availability_status_check'),
                 ('share_events_event_type_check'), ('transfer_sessions_status_check'),
                 ('transfer_sessions_bytes_received_check'),
                 ('transfer_sources_bytes_received_check'),
                 ('transfer_sources_pieces_received_check'),
                 ('transfer_sources_source_status_check'), ('fk_peers_user'),
                 ('fk_peer_sessions_peer'),
                 ('fk_file_pieces_file'), ('fk_peer_files_peer'), ('fk_peer_files_file'),
                 ('fk_share_events_peer'), ('fk_share_events_file'), ('fk_transfer_file'),
                 ('fk_transfer_destination'), ('fk_transfer_sources_transfer'),
                 ('fk_transfer_sources_peer'), ('ck_transfer_time'),
                 ('ck_transfer_source_time'), ('file_groups_owner_peer_id_fkey'),
                 ('group_files_group_id_fkey'), ('group_files_file_id_fkey'),
                 ('folder_download_sessions_group_id_fkey'),
                 ('folder_download_sessions_destination_peer_id_fkey'),
                 ('folder_download_evidence_group_transfer_id_fkey'),
                 ('folder_directories_group_id_fkey')) AS required(name)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_constraint constraint_row
        WHERE constraint_row.connamespace = 'public'::regnamespace
          AND constraint_row.conname = required.name
    );
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Schema verification failed; missing constraints: %', missing;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public' AND indexname = 'uq_files_hash' AND indexdef ILIKE '%UNIQUE%'
    ) THEN
        RAISE EXCEPTION 'Schema verification failed; files.file_hash must have a unique index';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE connamespace = 'public'::regnamespace AND conname = 'peer_files_pkey'
          AND contype = 'p' AND conkey = ARRAY[
              (SELECT attnum FROM pg_attribute WHERE attrelid = 'public.peer_files'::regclass
                AND attname = 'peer_id'),
              (SELECT attnum FROM pg_attribute WHERE attrelid = 'public.peer_files'::regclass
                AND attname = 'file_id')]
    ) THEN
        RAISE EXCEPTION 'Schema verification failed; peer_files must be keyed by (peer_id, file_id)';
    END IF;

    IF pg_get_viewdef('public.v_file_sources'::regclass, true) NOT ILIKE '%publish_status%'
       OR pg_get_viewdef('public.v_file_sources'::regclass, true) NOT ILIKE '%READY%' THEN
        RAISE EXCEPTION 'Missing migration_v3 behavior: v_file_sources must filter by publish_status';
    END IF;
    IF EXISTS (
        SELECT 1 FROM files f
        WHERE f.publish_status = 'PUBLISHING'
          AND f.piece_count = (SELECT COUNT(*) FROM file_pieces fp WHERE fp.file_id = f.file_id)
          AND NOT EXISTS (
              SELECT 1 FROM file_pieces fp
              WHERE fp.file_id = f.file_id
                AND (fp.piece_index < 0 OR fp.piece_index >= f.piece_count
                     OR fp.piece_length <> LEAST(f.piece_size,
                            f.file_size - fp.piece_index::BIGINT * f.piece_size)
                     OR fp.piece_hash IS NULL)
          )
    ) THEN
        RAISE EXCEPTION 'Missing migration_v5 data repair: complete PUBLISHING files remain';
    END IF;

    IF EXISTS (
        SELECT 1 FROM files f
        WHERE f.publish_status = 'READY'
          AND f.piece_count <> (SELECT COUNT(*) FROM file_pieces fp WHERE fp.file_id = f.file_id)
    ) THEN
        RAISE EXCEPTION 'Schema verification failed; READY files have incomplete piece metadata';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS schema_version (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    version INTEGER NOT NULL CHECK (version > 0),
    verified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO schema_version (singleton, version)
VALUES (TRUE, 9)
ON CONFLICT (singleton) DO UPDATE
SET version = GREATEST(schema_version.version, EXCLUDED.version),
    verified_at = CURRENT_TIMESTAMP;

DO $$
BEGIN
    IF (SELECT version FROM schema_version WHERE singleton = TRUE) <> 9 THEN
        RAISE EXCEPTION 'Schema verification failed; database schema version is newer than supported version 9';
    END IF;
END $$;

SELECT version, verified_at FROM schema_version WHERE singleton = TRUE;
SELECT publish_status, COUNT(*) AS file_count FROM files GROUP BY publish_status ORDER BY 1;
SELECT COUNT(*) AS ready_files_missing_pieces
FROM files f WHERE f.publish_status = 'READY' AND f.piece_count <>
       (SELECT COUNT(*) FROM file_pieces fp WHERE fp.file_id = f.file_id);
