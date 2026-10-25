package vn.edu.p2p.common.protocol;

/**
 * Protocol v1 message types shared by Tracker and Peer.
 *
 * Other team members should extend this enum instead of creating
 * another command format.
 */
public enum MessageType {
    LOGIN_REQUEST,
    LOGIN_RESPONSE,

    LOGOUT_REQUEST,
    LOGOUT_RESPONSE,

    HEARTBEAT,
    HEARTBEAT_ACK,

    FILE_SOURCES_REQUEST,
    FILE_SOURCES_RESPONSE,

    SHARE_FILE_REQUEST,
    SHARE_FILE_RESPONSE,

    FILE_PIECES_PAGE_REQUEST,
    FILE_PIECES_PAGE_RESPONSE,

    SEARCH_REQUEST,
    SEARCH_RESPONSE,

    UNSHARE_FILE_REQUEST,
    UNSHARE_FILE_RESPONSE,
    
    GET_BITFIELD_REQUEST,
    BITFIELD_RESPONSE,
    METADATA_PAGE_REQUEST,
    METADATA_PAGE_RESPONSE,

    REQUEST_PIECE,
    PIECE_RESPONSE,

    RECORD_TRANSFER_REQUEST,
    RECORD_TRANSFER_RESPONSE,

    TRANSFER_HISTORY_REQUEST,
    TRANSFER_HISTORY_RESPONSE,
    /** Generic protocol-level error when no request-specific response exists. */
    ERROR
}
