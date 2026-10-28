-- D69: chỉ coi là nguồn khi file đã đủ metadata (READY)
CREATE OR REPLACE VIEW v_file_sources AS
SELECT pf.file_id, pf.peer_id, pf.availability_status,
       op.ip_address, op.listening_port, op.last_seen
FROM peer_files pf
JOIN files f ON f.file_id = pf.file_id AND f.publish_status = 'READY'
JOIN v_online_peers op ON op.peer_id = pf.peer_id
WHERE pf.is_sharing = TRUE;