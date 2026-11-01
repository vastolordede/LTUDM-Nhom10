package vn.edu.p2p.common.dto;

/** Empty query lists the catalog; sort and pagination are evaluated server-side. */
public record SearchRequest(String sessionId, String query, int page, int pageSize, DiscoverySort sort) {
    public SearchRequest(String sessionId, String query) {
        this(sessionId, query, 0, 50, DiscoverySort.POPULARITY);
    }
    public SearchRequest(String sessionId, String query, int page, int pageSize) {
        this(sessionId, query, page, pageSize, DiscoverySort.POPULARITY);
    }
    public SearchRequest {
        if (page < 0) page = 0;
        if (pageSize < 1 || pageSize > 50) pageSize = 50;
        if (sort == null) sort = DiscoverySort.POPULARITY;
    }
}
