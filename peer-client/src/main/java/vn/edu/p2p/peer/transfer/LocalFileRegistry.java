package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.peer.share.FileHashUtil;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quản lý trạng thái các file nội bộ của Peer (cả file đã Share 100% lẫn file đang Download dở).
 * Giải quyết:
 * - S06: Đọc đúng offset của requested piece để phục vụ Upload.
 * - S13, S14: Tính piece offset và ghi piece bằng RandomAccessFile.
 * - S20: Lưu BitSet các piece đã hoàn thành.
 * - S27, S28: Cho phép vừa tải vừa upload nhưng chỉ upload những piece đã hoàn thành.
 */
public final class LocalFileRegistry {

    public static final class RegisteredFile {
        private final long fileId;
        private final Path filePath;
        private final FileMetadata metadata;
        private final BitSet completedPieces;
        private final Object fileLock = new Object();

        private RegisteredFile(
                long fileId,
                Path filePath,
                FileMetadata metadata,
                BitSet initialBitSet
        ) {
            this.fileId = fileId;
            this.filePath = Objects.requireNonNull(filePath, "filePath");
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.completedPieces = (BitSet) initialBitSet.clone();
        }

        public long fileId() {
            return fileId;
        }

        public Path filePath() {
            return filePath;
        }

        public FileMetadata metadata() {
            return metadata;
        }

        /** S20: Trả về bản sao thread-safe của BitSet hiện tại. */
        public synchronized BitSet bitSetSnapshot() {
            return (BitSet) completedPieces.clone();
        }

        public synchronized byte[] bitfieldBytes() {
            return completedPieces.toByteArray();
        }

        /** S28: Kiểm tra mảnh pieceIndex đã tải xong và hợp lệ chưa. */
        public synchronized boolean hasPiece(int pieceIndex) {
            return pieceIndex >= 0
                    && pieceIndex < metadata.pieceCount()
                    && completedPieces.get(pieceIndex);
        }

        public synchronized void markPieceCompleted(int pieceIndex) {
            if (pieceIndex >= 0 && pieceIndex < metadata.pieceCount()) {
                completedPieces.set(pieceIndex);
            }
        }

        /** A shared file may change after publishing; never advertise a corrupt piece. */
        public synchronized void invalidatePiece(int pieceIndex) {
            if (pieceIndex >= 0 && pieceIndex < metadata.pieceCount()) {
                completedPieces.clear(pieceIndex);
            }
        }

        public synchronized int completedPieceCount() {
            return completedPieces.cardinality();
        }

        public synchronized boolean isComplete() {
            return completedPieces.cardinality() >= metadata.pieceCount();
        }
    }

    private final Map<Long, RegisteredFile> filesById = new ConcurrentHashMap<>();

    /**
     * Đăng ký một file đã có sẵn đầy đủ 100% trên máy (khi Peer bấm Share file).
     */
    public RegisteredFile registerCompleteFile(long fileId, Path filePath, FileMetadata metadata) {
        BitSet fullBits = new BitSet(metadata.pieceCount());
        fullBits.set(0, metadata.pieceCount());
        RegisteredFile entry = new RegisteredFile(fileId, filePath, metadata, fullBits);
        filesById.put(fileId, entry);
        return entry;
    }

    /**
     * S27: Đăng ký một file bắt đầu tải xuống (PARTIAL) để có thể vừa tải vừa upload
     * các piece đã hoàn thành cho Peer khác.
     */
    public RegisteredFile registerDownloadingFile(
            long fileId,
            Path targetPath,
            FileMetadata metadata,
            BitSet existingPieces
    ) throws IOException {
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        try (RandomAccessFile raf = new RandomAccessFile(targetPath.toFile(), "rw")) {
            raf.setLength(metadata.fileSize());
        }
        BitSet initial = existingPieces == null ? new BitSet(metadata.pieceCount()) : existingPieces;
        RegisteredFile entry = new RegisteredFile(fileId, targetPath, metadata, initial);
        filesById.put(fileId, entry);
        return entry;
    }

    public void unregisterFile(long fileId) {
        filesById.remove(fileId);
    }

    public Optional<RegisteredFile> find(long fileId) {
        return Optional.ofNullable(filesById.get(fileId));
    }

    /**
     * S13: Công thức tính byte offset bắt đầu của một piece trong file.
     */
    public static long pieceOffset(int pieceIndex, int pieceSize) {
        if (pieceIndex < 0) {
            throw new IllegalArgumentException("pieceIndex must be >= 0");
        }
        if (pieceSize <= 0) {
            throw new IllegalArgumentException("pieceSize must be > 0");
        }
        return (long) pieceIndex * (long) pieceSize;
    }

    /**
     * S06 & S28: Đọc đúng mảnh pieceIndex bằng RandomAccessFile để gửi cho Peer khác.
     * Chỉ cho phép đọc nếu pieceIndex đã có trong BitSet (S28).
     */
    public byte[] readPieceBytes(long fileId, int pieceIndex) throws IOException {
        RegisteredFile entry = filesById.get(fileId);
        if (entry == null) {
            throw new IOException("File is not registered locally: fileId=" + fileId);
        }

        FileMetadata meta = entry.metadata();
        if (pieceIndex < 0 || pieceIndex >= meta.pieceCount()) {
            throw new IndexOutOfBoundsException("Invalid pieceIndex: " + pieceIndex);
        }
        if (!entry.hasPiece(pieceIndex)) {
            throw new IOException("Piece " + pieceIndex + " is not yet completed on this peer");
        }

        int expectedLength = expectedPieceLength(meta, pieceIndex);
        long offset = pieceOffset(pieceIndex, meta.pieceSize());

        synchronized (entry.fileLock) {
            try (RandomAccessFile raf = new RandomAccessFile(entry.filePath().toFile(), "r")) {
                raf.seek(offset);
                byte[] buffer = new byte[expectedLength];
                raf.readFully(buffer);
                if (!pieceHashMatches(meta, pieceIndex, buffer)) {
                    entry.invalidatePiece(pieceIndex);
                    throw new IOException("Shared file content changed or corrupted: fileId="
                            + fileId + ", piece=" + pieceIndex);
                }
                return buffer;
            }
        }
    }

    /**
     * S13 & S14: Ghi mảng byte của một piece vào đúng vị trí offset bằng RandomAccessFile,
     * sau đó đánh dấu hoàn thành mảnh đó trong BitSet (S20).
     */
    public void writePieceBytes(long fileId, int pieceIndex, byte[] data) throws IOException {
        RegisteredFile entry = filesById.get(fileId);
        if (entry == null) {
            throw new IOException("File is not registered for download: fileId=" + fileId);
        }

        FileMetadata meta = entry.metadata();
        if (pieceIndex < 0 || pieceIndex >= meta.pieceCount()) {
            throw new IndexOutOfBoundsException("Invalid pieceIndex: " + pieceIndex);
        }

        int expectedLength = expectedPieceLength(meta, pieceIndex);
        if (data.length != expectedLength) {
            throw new IOException(
                    "Piece length mismatch for piece " + pieceIndex
                            + ": expected " + expectedLength + ", got " + data.length
            );
        }

        // Validate before marking a downloaded piece as available for onward upload.
        if (!pieceHashMatches(meta, pieceIndex, data)) {
            throw new IOException("Downloaded piece hash does not match published metadata: " + pieceIndex);
        }
        long offset = pieceOffset(pieceIndex, meta.pieceSize());
        synchronized (entry.fileLock) {
            try (RandomAccessFile raf = new RandomAccessFile(entry.filePath().toFile(), "rw")) {
                raf.seek(offset);
                raf.write(data);
            }
        }
        entry.markPieceCompleted(pieceIndex);
    }

    /** Header-only metadata has no trusted piece hash and requires full metadata before upload. */
    private static boolean pieceHashMatches(FileMetadata meta, int pieceIndex, byte[] data) {
        if (meta.pieces().isEmpty() || pieceIndex >= meta.pieces().size()) return false;
        PieceInfo expected = meta.pieces().get(pieceIndex);
        return expected.pieceIndex() == pieceIndex
                && expected.pieceLength() == data.length
                && expected.pieceHash().equalsIgnoreCase(FileHashUtil.sha256(data, 0, data.length));
    }

    public static int expectedPieceLength(FileMetadata meta, int pieceIndex) {
        if (!meta.pieces().isEmpty() && pieceIndex < meta.pieces().size()) {
            PieceInfo info = meta.pieces().get(pieceIndex);
            if (info.pieceIndex() == pieceIndex) {
                return info.pieceLength();
            }
        }
        long offset = pieceOffset(pieceIndex, meta.pieceSize());
        return (int) Math.min(meta.pieceSize(), meta.fileSize() - offset);
    }
}
