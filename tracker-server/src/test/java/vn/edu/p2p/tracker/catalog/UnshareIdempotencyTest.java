package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class UnshareIdempotencyTest {
    @Test void unshareDoesNotLogASecondStop() throws Exception {
        UUID peer = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        final int[] stops={0};
        final boolean[] sharing={true};
        SessionPeerResolver resolver = ignored -> peer;
        PeerFileRepository pf = new PeerFileRepository() {
            @Override public void upsertOwnership(UUID p,long f,String s){}
            @Override public boolean isSharing(UUID p,long f){return sharing[0];}
            @Override public void setSharing(UUID p,long f,boolean active){sharing[0]=active;}
            @Override public void demoteIfPartial(UUID p,long f){}
        };
        FileRepository files = new FileRepository() {
            @Override public Optional<Long> findFileIdByHash(String h){return Optional.empty();}
            @Override public long insert(vn.edu.p2p.common.dto.FileMetadata m){return 1;}
            @Override public boolean hasCompatibleGeometry(long id,vn.edu.p2p.common.dto.FileMetadata m){return true;}
            @Override public Optional<String> findPublishStatus(long id){return Optional.of("READY");}
            @Override public void addAlias(long id,String name,UUID p){}
            @Override public void upsertPiecePage(long id,List<vn.edu.p2p.common.dto.PieceInfo> pcs){}
            @Override public java.util.List<vn.edu.p2p.common.dto.SearchResultItem> searchByName(String query){return List.of();}
        };
        ShareEventRepository events=(p,id,type) -> {if ("SHARE_STOP".equals(type)) stops[0]++;};
        FileCatalogService service = new FileCatalogService(resolver,files,pf,events,new TicketRevocationRegistry());
        assertTrue(service.unshare(new UnshareFileRequest(session.toString(),3)).stopped());
        assertFalse(service.unshare(new UnshareFileRequest(session.toString(),3)).stopped());
        assertEquals(1,stops[0]);
    }
}
