package vn.edu.p2p.tracker.network;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RelayBudgetTest {
    @Test void rejectsMoreThanSessionAllowanceAcrossBothStreams() throws Exception {
        RelayBudget budget = new RelayBudget(100, 1_000_000, 5);
        budget.take(40);
        budget.take(60);
        assertEquals(0, budget.remainingBytes());
        assertThrows(IOException.class, () -> budget.take(1));
    }
    @Test void rejectsInvalidConfigAndOversizedChunk() {
        assertThrows(IllegalArgumentException.class, () -> new RelayBudget(0, 1, 1));
        RelayBudget budget = new RelayBudget(1000, 1_000_000, 2);
        assertThrows(IOException.class, () -> budget.take(64 * 1024 + 1));
    }
}
