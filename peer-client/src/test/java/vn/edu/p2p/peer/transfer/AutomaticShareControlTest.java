package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.FileMetadata;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticShareControlTest {
    @TempDir Path temp;
    private static FileSourceInfo source(String id) { return new FileSourceInfo(id,"COMPLETE","127.0.0.1",7001,0L); }
    private static BitSet bits(int... pieces) { var b=new BitSet(); for(int i:pieces)b.set(i); return b; }
    @Test void adaptsToAvailabilityAndDisconnect() {
        var scheduler=new PieceScheduler(3,new BitSet(),PieceScheduler.Strategy.AUTO);
        scheduler.registerSource(source("a"),bits(0,1,2));
        assertEquals(PieceScheduler.Strategy.ROUND_ROBIN,scheduler.automaticStrategy());
        scheduler.registerSource(source("b"),bits(0,1,2));
        assertEquals(PieceScheduler.Strategy.ROUND_ROBIN,scheduler.automaticStrategy());
        scheduler.registerSource(source("b"),bits(0,1));
        assertEquals(PieceScheduler.Strategy.RAREST_FIRST,scheduler.automaticStrategy());
        assertEquals(2,scheduler.nextAssignment().orElseThrow().pieceIndex());
        scheduler.markSourceDisconnected("a");
        assertEquals(PieceScheduler.Strategy.ROUND_ROBIN,scheduler.automaticStrategy());
        assertEquals("b",scheduler.nextAssignment().orElseThrow().source().peerId());
    }
    @Test void ranksUnreliableSources() {
        var scheduler=new PieceScheduler(2,new BitSet(),PieceScheduler.Strategy.AUTO);
        scheduler.registerSource(source("a"),bits(0,1));
        scheduler.registerSource(source("b"),bits(0,1));
        scheduler.recordCorruptPieceFromPeer("a");
        assertEquals(PieceScheduler.Strategy.RANKED,scheduler.automaticStrategy());
    }
    @Test void stoppedUploadStaysDisabledDuringResume() throws Exception {
        var registry=new LocalFileRegistry();
        var metadata=new FileMetadata("x.bin",4,"0".repeat(64),4,1,List.of());
        Path file=temp.resolve("x.bin");
        registry.registerCompleteFile(7,file,metadata);
        assertTrue(registry.findForUpload(7).isPresent());
        registry.disableSharing(7);
        assertTrue(registry.findForUpload(7).isEmpty());
        registry.registerDownloadingFile(7,file,metadata,bits(0));
        assertTrue(registry.findForUpload(7).isEmpty());
        assertTrue(registry.find(7).isPresent());
        registry.registerCompleteFile(7,file,metadata);
        assertTrue(registry.findForUpload(7).isPresent());
    }
}
