package vn.edu.p2p.peer.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.FileSourceInfo;

class PieceSchedulerRankedTest {
    @Test
    void rankedSpreadsLoadAcrossSources() {
        PieceScheduler s = new PieceScheduler(4, null, PieceScheduler.Strategy.RANKED);
        BitSet all = new BitSet();
        all.set(0, 4);
        s.registerSource(new FileSourceInfo("A", "COMPLETE", "127.0.0.1", 7001, 0L), all);
        s.registerSource(new FileSourceInfo("B", "COMPLETE", "127.0.0.1", 7002, 0L), all);
        var first = s.nextAssignment().orElseThrow();
        var second = s.nextAssignment().orElseThrow();
        assertNotEquals(first.source().peerId(), second.source().peerId());
    }
}