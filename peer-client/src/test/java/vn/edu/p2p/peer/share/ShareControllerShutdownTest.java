package vn.edu.p2p.peer.share;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareControllerShutdownTest {
    @Test
    void shareAndMyFilesExecutorsTerminateAfterRepeatedShutdown() {
        ShareController share = new ShareController();
        MyFilesController myFiles = new MyFilesController();

        share.shutdown();
        share.shutdown();
        myFiles.shutdown();
        myFiles.shutdown();

        assertTrue(share.executorTerminated());
        assertTrue(myFiles.executorTerminated());
    }
}
