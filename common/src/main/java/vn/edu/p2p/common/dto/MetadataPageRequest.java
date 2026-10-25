package vn.edu.p2p.common.dto;
/** Peer-to-peer request for a bounded range of SHA-256 hashes. */
public record MetadataPageRequest(long fileId, int startIndex, int pageSize) {}
