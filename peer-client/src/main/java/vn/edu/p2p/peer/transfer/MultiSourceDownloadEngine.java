package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.BitfieldResponse;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.FileSourceInfo;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S09-S12, S17-S19, S23-S34, S41, S43:
 * Động cơ tải file đơn nguồn & đa nguồn song song bằng Thread Pool.
 */
public final class MultiSourceDownloadEngine {

    public static final int DEFAULT_WORKER_THREADS = 4;
    private static final int MAX_PIECE_RETRY_ROUNDS = 5;

    private final LocalFileRegistry fileRegistry;
    private final PeerDownloadClient downloadClient;
    private final int maxWorkerThreads;
    private final PieceScheduler.Strategy schedulingStrategy;

    public MultiSourceDownloadEngine(
            LocalFileRegistry fileRegistry,
            PeerDownloadClient downloadClient
    ) {
        this(
                fileRegistry,
                downloadClient,
                DEFAULT_WORKER_THREADS,
                PieceScheduler.Strategy.ROUND_ROBIN
        );
    }

    public MultiSourceDownloadEngine(
            LocalFileRegistry fileRegistry,
            PeerDownloadClient downloadClient,
            int maxWorkerThreads,
            PieceScheduler.Strategy schedulingStrategy
    ) {
        if (maxWorkerThreads <= 0) {
            throw new IllegalArgumentException("maxWorkerThreads must be > 0");
        }
        this.fileRegistry = Objects.requireNonNull(fileRegistry, "fileRegistry");
        this.downloadClient = Objects.requireNonNull(downloadClient, "downloadClient");
        this.maxWorkerThreads = maxWorkerThreads;
        this.schedulingStrategy = Objects.requireNonNull(schedulingStrategy, "schedulingStrategy");
    }

    /**
     * Thực hiện phiên tải file từ 1 hoặc nhiều Peer nguồn song song.
     */
    public DownloadSessionResult downloadFile(
            long fileId,
            List<FileSourceInfo> candidateSources,
            Path targetFilePath,
            DownloadProgressListener progressListener,
            AtomicBoolean cancelSignal
    ) {
        return downloadFile(fileId, candidateSources, targetFilePath, progressListener, cancelSignal, UUID.randomUUID());
    }

    /** Use the same ID for Tracker DOWNLOADING and the terminal transfer result. */
    public DownloadSessionResult downloadFile(
            long fileId,
            List<FileSourceInfo> candidateSources,
            Path targetFilePath,
            DownloadProgressListener progressListener,
            AtomicBoolean cancelSignal,
            UUID transferId
    ) {
        Objects.requireNonNull(transferId, "transferId");
        Instant startedAt = Instant.now();
        long startNano = System.nanoTime();
        AtomicLong totalBytesReceived = new AtomicLong(0);

        if (candidateSources == null || candidateSources.isEmpty()) {
            return failedResult(
                    transferId, fileId, targetFilePath, startedAt, 0L,
                    "No online peer sources available", List.of()
            );
        }

        // Bước 1: Hỏi GET_BITFIELD từ các Peer nguồn để biết mảnh mỗi Peer có và lấy FileMetadata
        FileMetadata discoveredMetadata = null;
        Map<String, BitSet> discoveredBitfields = new ConcurrentHashMap<>();
        Map<String, MutableSourceStat> sourceStatsMap = new ConcurrentHashMap<>();
        List<FileSourceInfo> reachableSources = new ArrayList<>();

        for (FileSourceInfo source : candidateSources) {
            MutableSourceStat stat = new MutableSourceStat(source.peerId(), Instant.now());
            sourceStatsMap.put(source.peerId(), stat);
            try {
                BitfieldResponse bf = downloadClient.fetchBitfield(
                        source.ipAddress(),
                        source.listeningPort(),
                        fileId
                );
                if (discoveredMetadata == null && bf.metadata() != null) {
                    discoveredMetadata = bf.metadata();
                }
                discoveredBitfields.put(source.peerId(), bf.toBitSet());
                reachableSources.add(source);
            } catch (IOException e) {
                stat.markStatus("DISCONNECTED");
                if (progressListener != null) {
                    progressListener.onSourceStatusChanged(
                            source.peerId(), "DISCONNECTED", e.getMessage()
                    );
                }
            }
        }

        try {
            DownloadPreflight.check(targetFilePath, discoveredMetadata.fileSize());
        } catch (DownloadPreflight.PreflightException e) {
            return failedResult(
                    transferId, fileId, targetFilePath, startedAt, 0L,
                    e.getMessage(), List.of()
            );
        }

        if (discoveredMetadata == null || reachableSources.isEmpty()) {
            return failedResult(
                    transferId, fileId, targetFilePath, startedAt, 0L,
                    "Could not fetch bitfield/metadata from any peer source",
                    buildSourceStats(sourceStatsMap)
            );
        }

        // Large-file sources send only a compact header in BITFIELD_RESPONSE.
        // Fetch the SHA-256 list in bounded pages from a reachable source.
        if (discoveredMetadata.pieceCount() > 0 && discoveredMetadata.pieces().isEmpty()) {
            try {
                FileMetadata header = discoveredMetadata;
                FileMetadata complete = null;
                for (FileSourceInfo source : reachableSources) {
                    try {
                        List<PieceInfo> allPieces = new ArrayList<>(header.pieceCount());
                        for (int offset = 0; offset < header.pieceCount(); offset += 400) {
                            var page = downloadClient.fetchMetadataPage(source.ipAddress(),
                                    source.listeningPort(), fileId, offset, 400);
                            if (page.pieces().isEmpty()) throw new IOException("Empty metadata page");
                            for (PieceInfo piece : page.pieces()) {
                                if (piece.pieceIndex() != allPieces.size() || piece.pieceLength() <= 0
                                        || piece.pieceHash() == null || !piece.pieceHash().matches("^[0-9A-Fa-f]{64}$")) {
                                    throw new IOException("Invalid metadata piece sequence/hash");
                                }
                                allPieces.add(piece);
                            }
                        }
                        if (allPieces.size() != header.pieceCount()) throw new IOException("Metadata is incomplete");
                        complete = new FileMetadata(header.fileName(), header.fileSize(), header.fileHash(),
                                header.pieceSize(), header.pieceCount(), allPieces);
                        break;
                    } catch (IOException ex) {
                        if (progressListener != null) progressListener.onSourceStatusChanged(
                                source.peerId(), "METADATA_ERROR", ex.getMessage());
                    }
                }
                if (complete == null) throw new IOException("No source supplied complete metadata");
                discoveredMetadata = complete;
            } catch (IOException e) {
                return failedResult(transferId, fileId, targetFilePath, startedAt, 0L,
                        e.getMessage(), buildSourceStats(sourceStatsMap));
            }
        }
        final FileMetadata metadata = discoveredMetadata;

        try {
            // Bước 2 (S41 - Resume): Kiểm tra nếu file đích đã tồn tại một phần trên đĩa,
            // quét và giữ lại các piece đã khớp SHA-256 để tải tiếp thay vì tải lại từ đầu.
            BitSet resumedPieces = inspectExistingPiecesForResume(targetFilePath, metadata);
            long alreadyHaveBytes = calculateCompletedBytes(metadata, resumedPieces);
            totalBytesReceived.set(alreadyHaveBytes);

            // S27: Đăng ký file vào LocalFileRegistry để PeerUploadServer có thể
            // phục vụ (upload) ngay các piece đã hoàn thành cho Peer khác trong lúc đang tải.
            fileRegistry.registerDownloadingFile(fileId, targetFilePath, metadata, resumedPieces);

            PieceScheduler scheduler = new PieceScheduler(
                    metadata.pieceCount(),
                    resumedPieces,
                    schedulingStrategy
            );
            for (FileSourceInfo src : reachableSources) {
                scheduler.registerSource(src, discoveredBitfields.get(src.peerId()));
            }

            if (progressListener != null) {
                progressListener.onProgress(
                        fileId,
                        scheduler.completedCount(),
                        metadata.pieceCount(),
                        totalBytesReceived.get(),
                        metadata.fileSize(),
                        0.0
                );
            }

            // Bước 3 (S17-S19, S26): Khởi tạo Thread Pool tải nhiều piece song song từ nhiều nguồn
            int workerCount = Math.min(
                    maxWorkerThreads,
                    Math.max(1, metadata.pieceCount())
            );

            ExecutorService pool = Executors.newFixedThreadPool(workerCount, runnable -> {
                Thread t = new Thread(runnable, "peer-download-worker-" + fileId);
                t.setDaemon(true);
                return t;
            });

            try {
                List<Future<?>> futures = new ArrayList<>(workerCount);
                for (int w = 0; w < workerCount; w++) {
                    futures.add(pool.submit(() -> runWorkerLoop(
                            fileId,
                            metadata,
                            scheduler,
                            sourceStatsMap,
                            totalBytesReceived,
                            startNano,
                            progressListener,
                            cancelSignal
                    )));
                }

                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            if (cancelSignal != null && cancelSignal.get()) {
                return new DownloadSessionResult(
                        transferId,
                        fileId,
                        targetFilePath,
                        "CANCELLED",
                        totalBytesReceived.get(),
                        startedAt,
                        Instant.now(),
                        "Download cancelled by user",
                        buildSourceStats(sourceStatsMap)
                );
            }

            if (!scheduler.isAllCompleted()) {
                return failedResult(
                        transferId, fileId, targetFilePath, startedAt, totalBytesReceived.get(),
                        "Download incomplete: completed " + scheduler.completedCount()
                                + "/" + metadata.pieceCount() + " pieces",
                        buildSourceStats(sourceStatsMap)
                );
            }

            // Bước 4 (S12, S29, S30): Kiểm tra toàn vẹn SHA-256 toàn bộ file sau khi ghép xong
            String actualFileHash = FileHashUtil.sha256(targetFilePath);
            if (!actualFileHash.equalsIgnoreCase(metadata.fileHash())) {
                return failedResult(
                        transferId, fileId, targetFilePath, startedAt, totalBytesReceived.get(),
                        "Full-file SHA-256 mismatch: expected " + metadata.fileHash()
                                + ", got " + actualFileHash,
                        buildSourceStats(sourceStatsMap)
                );
            }

            for (MutableSourceStat stat : sourceStatsMap.values()) {
                if ("ACTIVE".equals(stat.status)) {
                    stat.markStatus("COMPLETED");
                }
            }

            return new DownloadSessionResult(
                    transferId,
                    fileId,
                    targetFilePath,
                    "COMPLETED",
                    totalBytesReceived.get(),
                    startedAt,
                    Instant.now(),
                    null,
                    buildSourceStats(sourceStatsMap)
            );
        } catch (Exception e) {
            return failedResult(
                    transferId, fileId, targetFilePath, startedAt, totalBytesReceived.get(),
                    "Download error: " + e.getMessage(),
                    buildSourceStats(sourceStatsMap)
            );
        }
    }

    private void runWorkerLoop(
            long fileId,
            FileMetadata metadata,
            PieceScheduler scheduler,
            Map<String, MutableSourceStat> sourceStatsMap,
            AtomicLong totalBytesReceived,
            long startNano,
            DownloadProgressListener progressListener,
            AtomicBoolean cancelSignal
    ) {
        int idleSpins = 0;

        while ((cancelSignal == null || !cancelSignal.get()) && !scheduler.isAllCompleted()) {
            Optional<PieceScheduler.Assignment> assignmentOpt = scheduler.nextAssignment();
            if (assignmentOpt.isEmpty()) {
                if (!scheduler.hasRemainingWork() || idleSpins > MAX_PIECE_RETRY_ROUNDS * 10) {
                    break;
                }
                idleSpins++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            idleSpins = 0;
            PieceScheduler.Assignment assignment = assignmentOpt.get();
            int pieceIndex = assignment.pieceIndex();
            FileSourceInfo source = assignment.source();
            MutableSourceStat sourceStat = sourceStatsMap.get(source.peerId());

            try {
                PeerDownloadClient.DownloadedPiece downloaded = downloadClient.downloadPiece(
                        source.ipAddress(),
                        source.listeningPort(),
                        fileId,
                        pieceIndex
                );

                // S31 & S43: Kiểm tra SHA-256 của từng piece ngay khi nhận
                String expectedPieceHash = expectedPieceHash(metadata, pieceIndex, downloaded.header().pieceHash());
                if (expectedPieceHash != null && !expectedPieceHash.isBlank()) {
                    String actualPieceHash = FileHashUtil.sha256(
                            downloaded.data(), 0, downloaded.data().length
                    );
                    if (!actualPieceHash.equalsIgnoreCase(expectedPieceHash)) {
                        // B02: Đánh dấu Peer gửi mảnh lỗi và trả mảnh về PENDING để tải từ nguồn khác (S33-S34)
                        boolean blacklisted = scheduler.recordCorruptPieceFromPeer(source.peerId());
                        scheduler.releasePieceToPending(pieceIndex);
                        if (blacklisted && sourceStat != null) {
                            sourceStat.markStatus("FAILED");
                        }
                        continue;
                    }
                }

                // S13-S14: Ghi piece vào đúng vị trí offset bằng RandomAccessFile
                fileRegistry.writePieceBytes(fileId, pieceIndex, downloaded.data());
                scheduler.markPieceCompleted(pieceIndex);

                long currentBytes = totalBytesReceived.addAndGet(downloaded.data().length);
                if (sourceStat != null) {
                    sourceStat.recordPiece(downloaded.data().length);
                }

                if (progressListener != null) {
                    double elapsedSec = Math.max(0.001, (System.nanoTime() - startNano) / 1_000_000_000.0);
                    double speed = currentBytes / elapsedSec;
                    progressListener.onProgress(
                            fileId,
                            scheduler.completedCount(),
                            metadata.pieceCount(),
                            currentBytes,
                            metadata.fileSize(),
                            speed
                    );
                }
            } catch (IOException e) {
                // S32-S34: Peer nguồn bị ngắt kết nối -> trả piece về PENDING và tự động thử nguồn khác
                scheduler.markSourceDisconnected(source.peerId());
                scheduler.releasePieceToPending(pieceIndex);
                if (sourceStat != null) {
                    sourceStat.markStatus("DISCONNECTED");
                }
                if (progressListener != null) {
                    progressListener.onSourceStatusChanged(
                            source.peerId(), "DISCONNECTED", e.getMessage()
                    );
                }
            }
        }
    }

    /**
     * S41 (Resume): Nếu file đích đã tồn tại trên đĩa và có các mảnh đã tải từ lần trước,
     * kiểm tra SHA-256 từng mảnh để giữ lại các mảnh hợp lệ.
     */
    public static BitSet inspectExistingPiecesForResume(Path filePath, FileMetadata metadata) {
        BitSet validPieces = new BitSet(metadata.pieceCount());
        if (filePath == null || !Files.isRegularFile(filePath) || metadata.pieces().isEmpty()) {
            return validPieces;
        }

        try {
            long existingFileLength = Files.size(filePath);
            if (existingFileLength <= 0) {
                return validPieces;
            }

            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                for (PieceInfo piece : metadata.pieces()) {
                    if (piece.pieceHash() == null || piece.pieceHash().isBlank()) {
                        continue;
                    }
                    long offset = LocalFileRegistry.pieceOffset(piece.pieceIndex(), metadata.pieceSize());
                    if (offset + piece.pieceLength() > existingFileLength) {
                        continue;
                    }
                    byte[] buffer = new byte[piece.pieceLength()];
                    raf.seek(offset);
                    raf.readFully(buffer);
                    String actualHash = FileHashUtil.sha256(buffer, 0, buffer.length);
                    if (actualHash.equalsIgnoreCase(piece.pieceHash())) {
                        validPieces.set(piece.pieceIndex());
                    }
                }
            }
        } catch (IOException ignored) {
            // Nếu không đọc được file cũ thì tải lại từ đầu
        }
        return validPieces;
    }

    private static long calculateCompletedBytes(FileMetadata metadata, BitSet completedPieces) {
        long bytes = 0;
        for (int i = completedPieces.nextSetBit(0); i >= 0; i = completedPieces.nextSetBit(i + 1)) {
            bytes += LocalFileRegistry.expectedPieceLength(metadata, i);
        }
        return bytes;
    }

    private static String expectedPieceHash(
            FileMetadata metadata,
            int pieceIndex,
            String headerHash
    ) {
        if (!metadata.pieces().isEmpty() && pieceIndex < metadata.pieces().size()) {
            String fromMeta = metadata.pieces().get(pieceIndex).pieceHash();
            if (fromMeta != null && !fromMeta.isBlank()) {
                return fromMeta;
            }
        }
        return headerHash;
    }

    private static DownloadSessionResult failedResult(
            UUID transferId,
            long fileId,
            Path targetFilePath,
            Instant startedAt,
            long bytesReceived,
            String reason,
            List<DownloadSessionResult.SourceTransferStat> stats
    ) {
        return new DownloadSessionResult(
                transferId,
                fileId,
                targetFilePath,
                "FAILED",
                bytesReceived,
                startedAt,
                Instant.now(),
                reason,
                stats
        );
    }

    private static List<DownloadSessionResult.SourceTransferStat> buildSourceStats(
            Map<String, MutableSourceStat> map
    ) {
        List<DownloadSessionResult.SourceTransferStat> list = new ArrayList<>(map.size());
        for (MutableSourceStat s : map.values()) {
            list.add(s.toRecord());
        }
        return list;
    }

    private static final class MutableSourceStat {
        private final String peerId;
        private final Instant connectedAt;
        private final AtomicLong bytes = new AtomicLong(0);
        private final AtomicInteger pieces = new AtomicInteger(0);
        private volatile String status = "ACTIVE";
        private volatile Instant disconnectedAt;

        MutableSourceStat(String peerId, Instant connectedAt) {
            this.peerId = peerId;
            this.connectedAt = connectedAt;
        }

        void recordPiece(int length) {
            bytes.addAndGet(length);
            pieces.incrementAndGet();
        }

        void markStatus(String newStatus) {
            this.status = newStatus;
            if ("DISCONNECTED".equals(newStatus) || "FAILED".equals(newStatus) || "COMPLETED".equals(newStatus)) {
                this.disconnectedAt = Instant.now();
            }
        }

        DownloadSessionResult.SourceTransferStat toRecord() {
            return new DownloadSessionResult.SourceTransferStat(
                    peerId,
                    bytes.get(),
                    pieces.get(),
                    status,
                    connectedAt,
                    disconnectedAt
            );
        }
    }
}