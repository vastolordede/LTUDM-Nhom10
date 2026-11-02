package vn.edu.p2p.common.dto;

import java.util.List;

/** Tracker source list includes a catalog SHA-256 trust anchor for P2P metadata. */
public record FileSourcesResponse(long fileId, List<FileSourceInfo> sources, String fileHash) {
    public FileSourcesResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (fileHash != null && !fileHash.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Catalog fileHash must be SHA-256 hex");
        }
    }

    /** Legacy in-memory tests; GUI download requires the catalog hash. */
    public FileSourcesResponse(long fileId, List<FileSourceInfo> sources) {
        this(fileId, sources, null);
    }
}
