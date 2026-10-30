package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.SearchRequest;
import vn.edu.p2p.common.dto.SearchResponse;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.common.dto.ShareFileRequest;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.common.dto.UnshareFileRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T33: Share -> Search end-to-end, exercised against FileCatalogService with
 * an in-memory fake instead of a live PostgreSQL instance (none available in
 * this dev/CI environment). This still walks the exact sequence a real run
 * goes through - publish() persists metadata + logs SHARE_START, search()
 * finds it by (partial) name with the right source_count, unshare() drops it
 * from active results and logs SHARE_STOP - covering T11-T25 together.
 *
 * If a real Postgres test database becomes available later, swap
 * InMemoryCatalog for real Jdbc*Repository instances pointed at a test DB;
 * the test bodies themselves don't need to change.
 */
class FileCatalogServiceIntegrationTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PEER_ID = UUID.randomUUID();

    private InMemoryCatalog catalog;
    private FileCatalogService service;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        SessionPeerResolver resolver = sessionId -> {
            assertEquals(SESSION_ID, sessionId, "test only wires up one active session");
            return PEER_ID;
        };
        service = new FileCatalogService(resolver, catalog, catalog, catalog);
    }

    @Test
    void shareThenSearch_findsTheFileByName() throws Exception {
        FileMetadata metadata = sampleMetadata("bao-cao-cuoi-ky.pdf");

        ShareFileResponse shareResponse = service.publish(
                new ShareFileRequest(SESSION_ID.toString(), metadata)
        );
        assertTrue(shareResponse.isNewFile());
        assertEquals("COMPLETE", shareResponse.availabilityStatus());
        assertEquals(List.of("SHARE_START"), catalog.eventTypesFor(shareResponse.fileId()));

        SearchResponse searchResponse = service.search(
                new SearchRequest(SESSION_ID.toString(), "cuoi-ky")
        );

        assertEquals(1, searchResponse.results().size());
        SearchResultItem found = searchResponse.results().get(0);
        assertEquals(shareResponse.fileId(), found.fileId());
        assertEquals(metadata.fileName(), found.fileName());
        assertEquals(1, found.sourceCount(), "exactly one peer is actively sharing right after publish()");
    }

    @Test
    void searchingPercentAndUnderscoreDoesNotTreatThemAsWildcards() throws Exception {
        service.search(new SearchRequest(SESSION_ID.toString(), "50%_done"));
        assertEquals("%50\\%\\_done%", catalog.lastLikePattern);
    }

    @Test
    void sharingIdenticalContentTwice_reusesTheSameFileId() throws Exception {
        FileMetadata metadata = sampleMetadata("original.bin");
        FileMetadata sameContentDifferentName = new FileMetadata(
                "copy-of-original.bin",
                metadata.fileSize(),
                metadata.fileHash(),
                metadata.pieceSize(),
                metadata.pieceCount(),
                metadata.pieces()
        );

        ShareFileResponse first = service.publish(new ShareFileRequest(SESSION_ID.toString(), metadata));
        ShareFileResponse second = service.publish(
                new ShareFileRequest(SESSION_ID.toString(), sameContentDifferentName)
        );

        assertTrue(first.isNewFile());
        assertFalse(second.isNewFile(), "T13: identical SHA-256 must not insert a second catalog row");
        assertEquals(first.fileId(), second.fileId());
    }

    @Test
    void unshare_removesTheFileFromActiveSearchResults() throws Exception {
        FileMetadata metadata = sampleMetadata("temp-share.txt");
        ShareFileResponse shareResponse = service.publish(
                new ShareFileRequest(SESSION_ID.toString(), metadata)
        );

        SearchResponse beforeUnshare = service.search(
                new SearchRequest(SESSION_ID.toString(), "temp-share")
        );
        assertEquals(1, beforeUnshare.results().get(0).sourceCount());

        service.unshare(new UnshareFileRequest(SESSION_ID.toString(), shareResponse.fileId()));

        SearchResponse afterUnshare = service.search(
                new SearchRequest(SESSION_ID.toString(), "temp-share")
        );
        assertEquals(
                0, afterUnshare.results().get(0).sourceCount(),
                "file stays in the catalog but no longer counts as an active source"
        );
        assertEquals(
                List.of("SHARE_START", "SHARE_STOP"),
                catalog.eventTypesFor(shareResponse.fileId())
        );
    }

    private static FileMetadata sampleMetadata(String fileName) {
        List<PieceInfo> pieces = List.of(new PieceInfo(0, 100, "a".repeat(64)));
        return new FileMetadata(fileName, 100, "b".repeat(64), 1024, 1, pieces);
    }

    /** In-memory stand-in for files + peer_files + share_events, used only by this test. */
    private static final class InMemoryCatalog
            implements FileRepository, PeerFileRepository, ShareEventRepository {

        private String lastLikePattern;
        private final Map<String, Long> fileIdByHash = new HashMap<>();
        private final Map<Long, FileMetadata> filesById = new HashMap<>();
        private final Map<String, Boolean> sharingByPeerFile = new HashMap<>();
        private final List<String> shareEvents = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(1);

        @Override
        public Optional<Long> findFileIdByHash(String fileHash) {
            return Optional.ofNullable(fileIdByHash.get(fileHash));
        }

        @Override
        public long insert(FileMetadata metadata) {
            long id = nextId.getAndIncrement();
            fileIdByHash.put(metadata.fileHash(), id);
            filesById.put(id, metadata);
            return id;
        }

        @Override
        public List<SearchResultItem> searchByName(String likePattern) {
            lastLikePattern = likePattern;
            String needle = likePattern.replace("%", "").toLowerCase();
            List<SearchResultItem> results = new ArrayList<>();

            for (Map.Entry<Long, FileMetadata> entry : filesById.entrySet()) {
                long fileId = entry.getKey();
                FileMetadata metadata = entry.getValue();

                if (metadata.fileName().toLowerCase().contains(needle)) {
                    int sourceCount = 0;
                    for (Map.Entry<String, Boolean> sharing : sharingByPeerFile.entrySet()) {
                        if (sharing.getKey().endsWith(":" + fileId) && sharing.getValue()) {
                            sourceCount++;
                        }
                    }
                    results.add(new SearchResultItem(
                            fileId, metadata.fileName(), metadata.fileSize(),
                            metadata.fileHash(), sourceCount
                    ));
                }
            }
            return results;
        }

        @Override
        public void upsertOwnership(UUID peerId, long fileId, String availabilityStatus) {
            sharingByPeerFile.put(peerId + ":" + fileId, true);
        }

        @Override
        public void setSharing(UUID peerId, long fileId, boolean sharing) {
            sharingByPeerFile.put(peerId + ":" + fileId, sharing);
        }

        @Override
        public void insert(UUID peerId, long fileId, String eventType) {
            shareEvents.add(fileId + ":" + eventType);
        }

        List<String> eventTypesFor(long fileId) {
            List<String> types = new ArrayList<>();
            for (String event : shareEvents) {
                String[] parts = event.split(":", 2);
                if (Long.parseLong(parts[0]) == fileId) {
                    types.add(parts[1]);
                }
            }
            return types;
        }
    }
}
