-- =========================================================
-- fileshare_p2p
-- Core Database Schema v1
-- PostgreSQL
-- =========================================================


-- =========================================================
-- 1. USERS
-- Tài khoản đăng nhập hệ thống
-- =========================================================

CREATE TABLE users (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,

    account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (account_status IN ('ACTIVE', 'DISABLED')),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Username không phân biệt hoa/thường.
-- Không cho tồn tại "Dang" và "dang" cùng lúc.
CREATE UNIQUE INDEX uq_users_username_ci
    ON users (LOWER(username));



-- =========================================================
-- 2. PEERS
-- Một máy/client P2P thuộc về một tài khoản
-- peer_id được Java sinh bằng UUID.randomUUID()
-- =========================================================

CREATE TABLE peers (
    peer_id UUID PRIMARY KEY,

    user_id BIGINT NOT NULL,

    device_name VARCHAR(100),
    public_key TEXT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ,

    CONSTRAINT fk_peers_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_peers_user_id
    ON peers(user_id);



-- =========================================================
-- 3. PEER_SESSIONS
-- Một phiên Peer đang kết nối Tracker
-- Dùng cho:
-- - Login / Logout
-- - IP + Port
-- - Heartbeat
-- - Online / Offline
-- =========================================================

CREATE TABLE peer_sessions (
    session_id UUID PRIMARY KEY,

    peer_id UUID NOT NULL,

    ip_address VARCHAR(45) NOT NULL,

    listening_port INTEGER NOT NULL
        CHECK (listening_port BETWEEN 1 AND 65535),

    status VARCHAR(16) NOT NULL DEFAULT 'ONLINE'
        CHECK (
            status IN (
                'ONLINE',
                'OFFLINE',
                'EXPIRED',
                'LOGGED_OUT'
            )
        ),

    login_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    logout_at TIMESTAMPTZ,

    CONSTRAINT fk_peer_sessions_peer
        FOREIGN KEY (peer_id)
        REFERENCES peers(peer_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_peer_sessions_peer_id
    ON peer_sessions(peer_id);

CREATE INDEX idx_peer_sessions_status
    ON peer_sessions(status);

CREATE INDEX idx_peer_sessions_last_seen
    ON peer_sessions(last_seen);

-- Mỗi Peer chỉ được có tối đa một session ONLINE
CREATE UNIQUE INDEX uq_peer_single_online_session
    ON peer_sessions(peer_id)
    WHERE status = 'ONLINE';



-- =========================================================
-- 4. FILES
-- Tracker chỉ lưu metadata.
-- KHÔNG lưu file binary.
-- =========================================================

CREATE TABLE files (
    file_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    file_name VARCHAR(255) NOT NULL,

    file_size BIGINT NOT NULL
        CHECK (file_size >= 0),

    -- SHA-256 toàn file
    file_hash CHAR(64) NOT NULL,

    piece_size INTEGER NOT NULL
        CHECK (piece_size > 0),

    piece_count INTEGER NOT NULL
        CHECK (piece_count >= 0),

    metadata_version INTEGER NOT NULL DEFAULT 1
        CHECK (metadata_version > 0),

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_files_sha256
        CHECK (file_hash ~ '^[0-9A-Fa-f]{64}$')
);

-- Cùng nội dung file chỉ có một metadata record
CREATE UNIQUE INDEX uq_files_hash
    ON files(file_hash);

CREATE INDEX idx_files_name
    ON files(file_name);



-- =========================================================
-- 5. FILE_PIECES
-- Metadata từng chunk.
--
-- piece_hash có thể NULL trong giai đoạn đầu nếu nhóm chỉ
-- kiểm tra SHA-256 toàn file.
--
-- Khi triển khai Piece-level SHA-256 thì điền giá trị này.
-- =========================================================

CREATE TABLE file_pieces (
    file_id BIGINT NOT NULL,

    piece_index INTEGER NOT NULL
        CHECK (piece_index >= 0),

    piece_length INTEGER NOT NULL
        CHECK (piece_length > 0),

    piece_hash CHAR(64),

    PRIMARY KEY (file_id, piece_index),

    CONSTRAINT fk_file_pieces_file
        FOREIGN KEY (file_id)
        REFERENCES files(file_id)
        ON DELETE CASCADE,

    CONSTRAINT ck_piece_sha256
        CHECK (
            piece_hash IS NULL
            OR piece_hash ~ '^[0-9A-Fa-f]{64}$'
        )
);



-- =========================================================
-- 6. PEER_FILES
-- Mapping Peer <-> File
--
-- Không lưu bitfield tại Tracker DB.
--
-- PARTIAL:
-- Peer đang download nhưng đã có một số piece và có thể
-- upload chúng.
--
-- COMPLETE:
-- Peer đã có toàn bộ file.
-- =========================================================

CREATE TABLE peer_files (
    peer_id UUID NOT NULL,

    file_id BIGINT NOT NULL,

    availability_status VARCHAR(16) NOT NULL
        DEFAULT 'COMPLETE'
        CHECK (
            availability_status IN (
                'PARTIAL',
                'COMPLETE'
            )
        ),

    is_sharing BOOLEAN NOT NULL DEFAULT TRUE,

    announced_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    completed_at TIMESTAMPTZ,

    PRIMARY KEY (peer_id, file_id),

    CONSTRAINT fk_peer_files_peer
        FOREIGN KEY (peer_id)
        REFERENCES peers(peer_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_peer_files_file
        FOREIGN KEY (file_id)
        REFERENCES files(file_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_peer_files_file_id
    ON peer_files(file_id);

CREATE INDEX idx_peer_files_available
    ON peer_files(file_id, is_sharing);



-- =========================================================
-- 7. SHARE_EVENTS
-- Lịch sử chia sẻ file
-- =========================================================

CREATE TABLE share_events (
    share_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    peer_id UUID NOT NULL,

    file_id BIGINT NOT NULL,

    event_type VARCHAR(16) NOT NULL
        CHECK (
            event_type IN (
                'SHARE_START',
                'SHARE_STOP'
            )
        ),

    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_share_events_peer
        FOREIGN KEY (peer_id)
        REFERENCES peers(peer_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_share_events_file
        FOREIGN KEY (file_id)
        REFERENCES files(file_id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_share_events_peer
    ON share_events(peer_id);

CREATE INDEX idx_share_events_file
    ON share_events(file_id);

CREATE INDEX idx_share_events_time
    ON share_events(occurred_at);



-- =========================================================
-- 8. TRANSFER_SESSIONS
-- Một lần một Peer cố tải một file
-- =========================================================

CREATE TABLE transfer_sessions (
    transfer_id UUID PRIMARY KEY,

    file_id BIGINT NOT NULL,

    destination_peer_id UUID NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'DOWNLOADING'
        CHECK (
            status IN (
                'QUEUED',
                'DOWNLOADING',
                'COMPLETED',
                'FAILED',
                'CANCELLED'
            )
        ),

    bytes_received BIGINT NOT NULL DEFAULT 0
        CHECK (bytes_received >= 0),

    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    completed_at TIMESTAMPTZ,

    failure_reason TEXT,

    CONSTRAINT fk_transfer_file
        FOREIGN KEY (file_id)
        REFERENCES files(file_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_transfer_destination
        FOREIGN KEY (destination_peer_id)
        REFERENCES peers(peer_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_transfer_time
        CHECK (
            completed_at IS NULL
            OR completed_at >= started_at
        )
);

CREATE INDEX idx_transfer_file
    ON transfer_sessions(file_id);

CREATE INDEX idx_transfer_destination
    ON transfer_sessions(destination_peer_id);

CREATE INDEX idx_transfer_status
    ON transfer_sessions(status);

CREATE INDEX idx_transfer_started_at
    ON transfer_sessions(started_at);



-- =========================================================
-- 9. TRANSFER_SOURCES
-- Một Download có thể sử dụng nhiều Peer nguồn
-- =========================================================

CREATE TABLE transfer_sources (
    transfer_id UUID NOT NULL,

    source_peer_id UUID NOT NULL,

    bytes_received BIGINT NOT NULL DEFAULT 0
        CHECK (bytes_received >= 0),

    pieces_received INTEGER NOT NULL DEFAULT 0
        CHECK (pieces_received >= 0),

    source_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (
            source_status IN (
                'ACTIVE',
                'COMPLETED',
                'FAILED',
                'DISCONNECTED'
            )
        ),

    connected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    disconnected_at TIMESTAMPTZ,

    PRIMARY KEY (transfer_id, source_peer_id),

    CONSTRAINT fk_transfer_sources_transfer
        FOREIGN KEY (transfer_id)
        REFERENCES transfer_sessions(transfer_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_transfer_sources_peer
        FOREIGN KEY (source_peer_id)
        REFERENCES peers(peer_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_transfer_source_time
        CHECK (
            disconnected_at IS NULL
            OR disconnected_at >= connected_at
        )
);

CREATE INDEX idx_transfer_sources_peer
    ON transfer_sources(source_peer_id);

CREATE VIEW v_online_peers AS
SELECT
    p.peer_id,
    p.user_id,
    p.device_name,
    ps.session_id,
    ps.ip_address,
    ps.listening_port,
    ps.last_seen
FROM peers p
JOIN peer_sessions ps
    ON ps.peer_id = p.peer_id
WHERE ps.status = 'ONLINE';

CREATE VIEW v_file_sources AS
SELECT
    pf.file_id,
    pf.peer_id,
    pf.availability_status,
    op.ip_address,
    op.listening_port,
    op.last_seen
FROM peer_files pf
JOIN v_online_peers op
    ON op.peer_id = pf.peer_id
WHERE pf.is_sharing = TRUE;