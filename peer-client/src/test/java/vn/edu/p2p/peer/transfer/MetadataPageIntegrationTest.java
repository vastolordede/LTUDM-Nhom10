package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataPageIntegrationTest {
    @TempDir Path dir;

    @Test
    void largeMetadataIsSentAsSmallPages() throws Exception {
        List<PieceInfo> pieces = new ArrayList<>();
        for (int i = 0; i < 2401; i++) {
            pieces.add(new PieceInfo(i, 256 * 1024, "a".repeat(64)));
        }
        FileMetadata metadata = new FileMetadata("large.bin", (long) pieces.size() * 256 * 1024,
                "b".repeat(64), 256 * 1024, pieces.size(), pieces);
        Path dummy = dir.resolve("dummy.bin");
        Files.write(dummy, new byte[1]);
        LocalFileRegistry registry = new LocalFileRegistry();
        registry.registerCompleteFile(9, dummy, metadata);
        var sockets = new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());
        try (PeerUploadServer server = new PeerUploadServer(0, registry, sockets)) {
            server.start();
            PeerDownloadClient client = new PeerDownloadClient(sockets);
            var header = client.fetchBitfield("127.0.0.1", server.listeningPort(), 9);
            assertTrue(header.metadata().pieces().isEmpty());
            int offset = 0;
            while (offset < pieces.size()) {
                var page = client.fetchMetadataPage("127.0.0.1", server.listeningPort(), 9, offset, 400);
                assertEquals(offset, page.startIndex());
                assertEquals(offset, page.pieces().get(0).pieceIndex());
                offset += page.pieces().size();
            }
            assertEquals(pieces.size(), offset);
        }
    }
}
