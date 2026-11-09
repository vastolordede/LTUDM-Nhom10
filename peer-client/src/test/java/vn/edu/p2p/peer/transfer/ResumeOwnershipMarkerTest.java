package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeOwnershipMarkerTest {
    @Test void aMatchingSinglePieceCannotOverwriteUserFile(@TempDir Path dir) throws Exception {
        byte[] expected = "AABB".getBytes();
        String hash = FileHashUtil.sha256(expected,0,expected.length);
        FileMetadata meta = new FileMetadata("file.bin",4,hash,2,2,List.of(
            new PieceInfo(0,2,FileHashUtil.sha256("AA".getBytes(),0,2)),
            new PieceInfo(1,2,FileHashUtil.sha256("BB".getBytes(),0,2))));
        Path original = dir.resolve("file.bin");
        Files.writeString(original, "AAXX");
        Method resolver = MultiSourceDownloadEngine.class.getDeclaredMethod(
            "resolveDownloadTarget", Path.class, FileMetadata.class);
        resolver.setAccessible(true);
        Path resolved = (Path)resolver.invoke(null, original, meta);
        assertNotEquals(original,resolved);
        assertEquals("AAXX",Files.readString(original));
    }

    @Test void aValidClaimAllowsExplicitResume(@TempDir Path dir) throws Exception {
        byte[] expected = "AABB".getBytes();
        String hash = FileHashUtil.sha256(expected,0,expected.length);
        FileMetadata meta = new FileMetadata("file.bin",4,hash,2,2,List.of(
            new PieceInfo(0,2,FileHashUtil.sha256("AA".getBytes(),0,2)),
            new PieceInfo(1,2,FileHashUtil.sha256("BB".getBytes(),0,2))));
        Path target=dir.resolve("file.bin");
        Files.writeString(target,"AAXX");
        ResumeOwnershipMarker.claim(target,meta);
        assertTrue(ResumeOwnershipMarker.matches(target,meta));
        ResumeOwnershipMarker.release(target);
        assertFalse(ResumeOwnershipMarker.matches(target,meta));
    }
}
