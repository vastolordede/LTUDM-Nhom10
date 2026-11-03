package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderControllerShutdownTest {
    @Test
    void shutdownIsIdempotentAndTerminatesExecutor() {
        FolderController controller = new FolderController();

        controller.shutdown();
        controller.shutdown();

        assertTrue(controller.workerTerminated());
    }
}
