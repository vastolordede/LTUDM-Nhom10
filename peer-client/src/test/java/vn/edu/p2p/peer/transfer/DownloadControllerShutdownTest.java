package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadControllerShutdownTest {
    @Test
    void shutdownIsIdempotentAndTerminatesExecutors() {
        DownloadController controller = new DownloadController();

        controller.shutdown();
        controller.shutdown();

        assertTrue(controller.workersTerminated());
    }
}
