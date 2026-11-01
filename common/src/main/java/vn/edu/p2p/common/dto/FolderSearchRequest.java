package vn.edu.p2p.common.dto;

/** Empty query lists folders; server-side sort/paging match file discovery. */
public record FolderSearchRequest(String sessionId, String query, int page, int pageSize,
                                  DiscoverySort sort) {
    public FolderSearchRequest(String sessionId, String query) {
        this(sessionId, query, 0, 50, DiscoverySort.POPULARITY);
    }
    public FolderSearchRequest(String sessionId, String query, int page, int pageSize) {
        this(sessionId, query, page, pageSize, DiscoverySort.POPULARITY);
    }
    public FolderSearchRequest {
        if (page < 0) page = 0;
        if (pageSize < 1 || pageSize > 50) pageSize = 50;
        if (sort == null) sort = DiscoverySort.POPULARITY;
    }
}
