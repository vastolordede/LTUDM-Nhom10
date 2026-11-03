package vn.edu.p2p.peer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;
import vn.edu.p2p.peer.share.FileMetadataBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiSourceTransferIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDownloadFileConcurrentlyFromThreePeersAndVerifySha256() throws Exception {
        // S42: Tạo file mẫu 1.1 MB (chia thành 5 pieces: 0, 1, 2, 3, 4) tải từ 3 Peer A, B, C
        Path originalFile = tempDir.resolve("movie-3peers.bin");
        byte[] content = new byte[1100 * 1024];
        new Random(99).nextBytes(content);
        Files.write(originalFile, content);

        FileMetadata metadata = new FileMetadataBuilder().build(originalFile);
        long fileId = 202L;

        PeerP2pSocketFactory socketFactory = new PeerP2pSocketFactory(PeerP2pSocketConfig.plainTcp());

        // Peer 1 giữ Piece 0, 1
        LocalFileRegistry reg1 = new LocalFileRegistry();
        BitSet bits1 = new BitSet(5);
        bits1.set(0);
        bits1.set(1);
        Path f1 = tempDir.resolve("p1.bin");
        Files.copy(originalFile, f1);
        reg1.registerDownloadingFile(fileId, f1, metadata, bits1);

        // Peer 2 giữ Piece 2, 3
        LocalFileRegistry reg2 = new LocalFileRegistry();
        BitSet bits2 = new BitSet(5);
        bits2.set(2);
        bits2.set(3);
        Path f2 = tempDir.resolve("p2.bin");
        Files.copy(originalFile, f2);
        reg2.registerDownloadingFile(fileId, f2, metadata, bits2);

        // Peer 3 giữ Piece 3, 4
        LocalFileRegistry reg3 = new LocalFileRegistry();
        BitSet bits3 = new BitSet(5);
        bits3.set(3);
        bits3.set(4);
        Path f3 = tempDir.resolve("p3.bin");
        Files.copy(originalFile, f3);
        reg3.registerDownloadingFile(fileId, f3, metadata, bits3);

        var tickets = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (PeerUploadServer s1 = new PeerUploadServer(0, reg1, socketFactory, 8, tickets.getPublic(), false);
             PeerUploadServer s2 = new PeerUploadServer(0, reg2, socketFactory, 8, tickets.getPublic(), false);
             PeerUploadServer s3 = new PeerUploadServer(0, reg3, socketFactory, 8, tickets.getPublic(), false)) {

            s1.setUploaderPeerId("peer-1");
            s2.setUploaderPeerId("peer-2");
            s3.setUploaderPeerId("peer-3");

            s1.start();
            s2.start();
            s3.start();

            List<FileSourceInfo> sources = List.of(
                    new FileSourceInfo("peer-1", "PARTIAL", "127.0.0.1", s1.listeningPort(), System.currentTimeMillis(),
                            PeerTestTickets.issue(tickets.getPrivate(),
                                    fileId, "test-reader", "peer-1", java.time.Instant.now())),
                    new FileSourceInfo("peer-2", "PARTIAL", "127.0.0.1", s2.listeningPort(), System.currentTimeMillis(),
                            PeerTestTickets.issue(tickets.getPrivate(),
                                    fileId, "test-reader", "peer-2", java.time.Instant.now())),
                    new FileSourceInfo("peer-3", "PARTIAL", "127.0.0.1", s3.listeningPort(), System.currentTimeMillis(),
                            PeerTestTickets.issue(tickets.getPrivate(),
                                    fileId, "test-reader", "peer-3", java.time.Instant.now()))
            );

            LocalFileRegistry downloaderReg = new LocalFileRegistry();
            PeerDownloadClient downloadClient = new PeerDownloadClient(socketFactory);
            MultiSourceDownloadEngine engine = new MultiSourceDownloadEngine(
                    downloaderReg,
                    downloadClient,
                    4,
                    PieceScheduler.Strategy.RAREST_FIRST
            );

            Path targetFile = tempDir.resolve("downloaded-from-3-peers.bin");
            DownloadSessionResult result = engine.downloadFile(
                    fileId,
                    sources,
                    targetFile,
                    null,
                    new AtomicBoolean(false)
            );

            assertEquals("COMPLETED", result.status());
            assertEquals(content.length, result.bytesReceived());
            assertEquals(3, result.sourceStats().size());
            assertEquals(metadata.fileHash(), FileHashUtil.sha256(targetFile));
            assertTrue(downloaderReg.find(fileId).orElseThrow().isComplete());
        }
    }
}