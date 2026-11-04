package vn.edu.p2p.tracker.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.dto.FilePiecesPageRequest;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.RecordTransferRequest;
import vn.edu.p2p.common.dto.SearchRequest;
import vn.edu.p2p.common.dto.SearchResponse;
import vn.edu.p2p.common.dto.SearchResultItem;
import vn.edu.p2p.common.dto.ShareFileRequest;
import vn.edu.p2p.common.dto.ShareFileResponse;
import vn.edu.p2p.common.dto.TransferHistoryItemDto;
import vn.edu.p2p.common.dto.TransferHistoryRequest;
import vn.edu.p2p.common.dto.TransferSourceRecordDto;
import vn.edu.p2p.common.dto.UnshareFileRequest;
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;
import vn.edu.p2p.tracker.transfer.TransferHistoryRepository;
import vn.edu.p2p.tracker.transfer.TransferHistoryService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
@Timeout(value = 10, unit = java.util.concurrent.TimeUnit.SECONDS)
class FileCatalogServiceIntegrationTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PEER_ID = UUID.randomUUID();

    private InMemoryCatalog catalog;
    private FileCatalogService service;
    private TicketRevocationRegistry revocations;

    @BeforeEach
    void setUp() {
        catalog = new InMemoryCatalog();
        SessionPeerResolver resolver = sessionId -> {
            assertEquals(SESSION_ID, sessionId, "test only wires up one active session");
            return PEER_ID;
        };
        revocations = new TicketRevocationRegistry(
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC));
        service = new FileCatalogService(resolver, catalog, catalog, catalog, revocations);
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
    void characterization_samePeerHashUsesOneFileIdAndSearchesBothAliases() throws Exception {
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
        assertEquals(1, service.search(new SearchRequest(SESSION_ID.toString(), "original"))
                .results().size());
        assertEquals("original.bin", service.search(
                new SearchRequest(SESSION_ID.toString(), "original")).results().getFirst().fileName());
        assertEquals("copy-of-original.bin", service.search(
                new SearchRequest(SESSION_ID.toString(), "copy-of-original"))
                .results().getFirst().fileName());
        assertEquals(Set.of("original.bin", "copy-of-original.bin"),
                catalog.aliasesFor(first.fileId()));
    }

    @Test
    void sameHashAcrossPeersKeepsAliasesSourcesAndCompletionCountByFileId() throws Exception {
        UUID secondSession = UUID.randomUUID();
        UUID secondPeer = UUID.randomUUID();
        FileCatalogService secondPeerService = serviceFor(secondSession, secondPeer);
        FileMetadata firstName = sampleMetadata("peer-a-name.bin");
        FileMetadata secondName = new FileMetadata(
                "peer-b-name.bin", firstName.fileSize(), firstName.fileHash(),
                firstName.pieceSize(), firstName.pieceCount(), firstName.pieces());

        ShareFileResponse first = service.publish(
                new ShareFileRequest(SESSION_ID.toString(), firstName));
        ShareFileResponse second = secondPeerService.publish(
                new ShareFileRequest(secondSession.toString(), secondName));

        assertEquals(first.fileId(), second.fileId());
        assertEquals(Set.of("peer-a-name.bin", "peer-b-name.bin"),
                catalog.aliasesFor(first.fileId()));
        SearchResultItem firstAlias = service.search(
                new SearchRequest(SESSION_ID.toString(), "peer-a-name")).results().getFirst();
        SearchResultItem secondAlias = service.search(
                new SearchRequest(SESSION_ID.toString(), "peer-b-name")).results().getFirst();
        assertEquals("peer-a-name.bin", firstAlias.fileName());
        assertEquals("peer-b-name.bin", secondAlias.fileName());
        assertEquals(2, firstAlias.sourceCount());
        assertEquals(2, secondAlias.sourceCount());

        service.unshare(new UnshareFileRequest(SESSION_ID.toString(), first.fileId()));
        SearchResultItem afterUnshare = service.search(
                new SearchRequest(SESSION_ID.toString(), "peer-b-name")).results().getFirst();
        assertEquals(1, afterUnshare.sourceCount(),
                "Stopping peer A must not remove peer B as a source for the shared fileId");
        assertTrue(catalog.isSharing(secondPeer, first.fileId()));
        assertFalse(catalog.isSharing(PEER_ID, first.fileId()));

        UUID completedTransfer = UUID.randomUUID();
        TransferHistoryService transferHistory = new TransferHistoryService(
                sessionId -> {
                    assertEquals(secondSession, sessionId);
                    return secondPeer;
                }, catalog, catalog);
        RecordTransferRequest completed = new RecordTransferRequest(
                secondSession.toString(), completedTransfer.toString(), first.fileId(),
                "COMPLETED", firstName.fileSize(), 1L, 2L, null,
                List.of(new TransferSourceRecordDto(PEER_ID.toString(), 50L, 1,
                                "COMPLETED", 1L, 2L),
                        new TransferSourceRecordDto(secondPeer.toString(), 50L, 1,
                                "COMPLETED", 1L, 2L)));
        transferHistory.recordTransfer(completed);
        transferHistory.recordTransfer(completed);
        assertEquals(Set.of(PEER_ID.toString(), secondPeer.toString()),
                catalog.transferSourcesFor(completedTransfer));
        assertEquals(1, service.search(
                new SearchRequest(SESSION_ID.toString(), "peer-a-name"))
                .results().getFirst().completedDownloads());
        assertEquals(1, service.search(
                new SearchRequest(SESSION_ID.toString(), "peer-b-name"))
                .results().getFirst().completedDownloads());
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
        assertEquals(1, revocations.size());
        assertEquals(PEER_ID.toString(), revocations.entriesAfter(0).get(0).uploaderPeerId());

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

    @Test
    void transferHistoryReturnsOnlyTheAuthenticatedPeersRows() throws Exception {
        UUID otherSession = UUID.randomUUID();
        UUID otherPeer = UUID.randomUUID();
        TransferHistoryItemDto ownItem = new TransferHistoryItemDto(
                UUID.randomUUID().toString(), 1, "own.txt", 1, "COMPLETED", 1,
                1L, 2L, null, 1);
        TransferHistoryItemDto otherItem = new TransferHistoryItemDto(
                UUID.randomUUID().toString(), 2, "other-private.txt", 2, "FAILED", 0,
                3L, null, "private failure", 1);
        TransferHistoryRepository history = new TransferHistoryRepository() {
            @Override
            public void upsertTransferSession(UUID transferId, long fileId, UUID destinationPeerId,
                                              String status, long bytesReceived, Instant startedAt,
                                              Instant completedAt, String failureReason) {}

            @Override
            public void upsertTransferSources(UUID transferId,
                                              List<TransferSourceRecordDto> sources) {}

            @Override
            public List<TransferHistoryItemDto> findByDestinationPeer(UUID destinationPeerId) {
                return destinationPeerId.equals(PEER_ID) ? List.of(ownItem) : List.of(otherItem);
            }
        };
        TransferHistoryService historyService = new TransferHistoryService(sessionId -> {
            if (sessionId.equals(SESSION_ID)) return PEER_ID;
            if (sessionId.equals(otherSession)) return otherPeer;
            throw new FileCatalogException("Unknown test session");
        }, history, null);

        var response = historyService.getHistory(
                new TransferHistoryRequest(SESSION_ID.toString()));

        assertEquals(List.of(ownItem), response.items());
        assertFalse(response.items().contains(otherItem));
    }

    @Test
    @Timeout(value = 2, unit = java.util.concurrent.TimeUnit.SECONDS)
    void nonOwnerCannotAppendMetadataPageToAnotherPeersShare() throws Exception {
        ShareFileResponse published = service.publish(new ShareFileRequest(
                SESSION_ID.toString(), sampleMetadata("owner-only.bin")));
        UUID otherSession = UUID.randomUUID();
        UUID otherPeer = UUID.randomUUID();
        FileCatalogService nonOwnerService = new FileCatalogService(
                sessionId -> {
                    assertEquals(otherSession, sessionId);
                    return otherPeer;
                }, catalog, catalog, catalog, revocations);

        assertThrows(FileCatalogException.class, () -> nonOwnerService.appendPiecePage(
                new FilePiecesPageRequest(otherSession.toString(), published.fileId(),
                        List.of(new PieceInfo(0, 100, "c".repeat(64))))));
        assertFalse(catalog.isSharing(otherPeer, published.fileId()));
        assertTrue(catalog.isSharing(PEER_ID, published.fileId()));
    }

    private static FileMetadata sampleMetadata(String fileName) {
        List<PieceInfo> pieces = List.of(new PieceInfo(0, 100, "a".repeat(64)));
        return new FileMetadata(fileName, 100, "b".repeat(64), 1024, 1, pieces);
    }

    private FileCatalogService serviceFor(UUID session, UUID peer) {
        return new FileCatalogService(
                sessionId -> {
                    assertEquals(session, sessionId);
                    return peer;
                }, catalog, catalog, catalog, revocations);
    }

    /** In-memory stand-in for files + peer_files + share_events, used only by this test. */
    private static final class InMemoryCatalog
            implements FileRepository, PeerFileRepository, ShareEventRepository, TransferHistoryRepository {

        private String lastLikePattern;
        private final Map<String, Long> fileIdByHash = new HashMap<>();
        private final Map<Long, FileMetadata> filesById = new HashMap<>();
        private final Map<Long, Set<String>> aliasesByFileId = new HashMap<>();
        private final Map<Long, Set<UUID>> completedDownloadsByFileId = new HashMap<>();
        private final Map<UUID, Long> fileIdByTransferId = new HashMap<>();
        private final Map<UUID, Set<String>> sourcePeerIdsByTransferId = new HashMap<>();
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
                String matchedName = aliasesByFileId.getOrDefault(fileId, Set.of(metadata.fileName()))
                        .stream()
                        .filter(name -> name.toLowerCase().contains(needle))
                        .findFirst()
                        .orElse(null);
                if (matchedName != null) {
                    int sourceCount = 0;
                    for (Map.Entry<String, Boolean> sharing : sharingByPeerFile.entrySet()) {
                        if (sharing.getKey().endsWith(":" + fileId) && sharing.getValue()) {
                            sourceCount++;
                        }
                    }
                    results.add(new SearchResultItem(
                            fileId, matchedName, metadata.fileSize(),
                            metadata.fileHash(), sourceCount,
                            completedDownloadsByFileId.getOrDefault(fileId, Set.of()).size(), 0L
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
        public boolean isSharing(UUID peerId, long fileId) {
            return sharingByPeerFile.getOrDefault(peerId + ":" + fileId, false);
        }

        @Override
        public void insert(UUID peerId, long fileId, String eventType) {
            shareEvents.add(fileId + ":" + eventType);
        }

        @Override
        public void addAlias(long fileId, String fileName, UUID peerId) {
            aliasesByFileId.computeIfAbsent(fileId, ignored -> new LinkedHashSet<>()).add(fileName);
        }

        Set<String> aliasesFor(long fileId) {
            return Set.copyOf(aliasesByFileId.getOrDefault(fileId, Set.of()));
        }

        Set<String> transferSourcesFor(UUID transferId) {
            return Set.copyOf(sourcePeerIdsByTransferId.getOrDefault(transferId, Set.of()));
        }

        @Override
        public void upsertTransferSession(UUID transferId, long fileId, UUID destinationPeerId,
                                          String status, long bytesReceived, Instant startedAt,
                                          Instant completedAt, String failureReason) throws SQLException {
            Long existingFileId = fileIdByTransferId.putIfAbsent(transferId, fileId);
            if (existingFileId != null && existingFileId != fileId) {
                throw new SQLException("Transfer UUID is already associated with another file");
            }
            if ("COMPLETED".equals(status)) {
                completedDownloadsByFileId.computeIfAbsent(fileId, ignored -> new LinkedHashSet<>())
                        .add(transferId);
            } else {
                Set<UUID> completed = completedDownloadsByFileId.get(fileId);
                if (completed != null) completed.remove(transferId);
            }
        }

        @Override
        public void upsertTransferSources(UUID transferId, List<TransferSourceRecordDto> sources) {
            Set<String> peerIds = sourcePeerIdsByTransferId.computeIfAbsent(
                    transferId, ignored -> new LinkedHashSet<>());
            sources.forEach(source -> peerIds.add(source.sourcePeerId()));
        }

        @Override
        public List<TransferHistoryItemDto> findByDestinationPeer(UUID destinationPeerId) {
            return List.of();
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
