package vn.edu.p2p.peer.share;

import vn.edu.p2p.common.dto.DiscoverySort;

/** FX thread owns pending/running; worker may read the volatile revision to abort stale scans. */
final class LatestDiscoveryRequest {
    record Request(long revision, String query, String type, DiscoverySort sort, int page, boolean explicit) {}
    private volatile long revision;
    private Request pending, running;
    void offer(String query,String type,DiscoverySort sort,int page,boolean explicit) {
        pending=new Request(++revision,query,type,sort,page,explicit);
    }
    void invalidate() { ++revision; pending=null; }
    Request pending() { return pending; }
    Request take() { running=pending; pending=null; return running; }
    boolean isCurrent(Request r) { return r!=null && r.revision()==revision; }
    boolean hasCurrentExplicit() { return (isCurrent(pending)&&pending.explicit()) || (isCurrent(running)&&running.explicit()); }
    void completed(Request r) { if(running==r) running=null; }
}
