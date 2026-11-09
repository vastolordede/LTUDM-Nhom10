package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FolderFileInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FolderFullDownloadConflictTest {
    @Test void fullDownloadCannotSilentlyRenameAnExistingFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("report.txt"), "wrong");
        byte[] correct="good!".getBytes();
        FolderFileInfo f=new FolderFileInfo(5,"report.txt",correct.length,
                FileHashUtil.sha256(correct,0,correct.length));
        assertThrows(java.io.IOException.class,()->FolderDownloadPlan.full(root,List.of(f),List.of()));
        assertEquals("wrong",Files.readString(root.resolve("report.txt")));
    }
}
