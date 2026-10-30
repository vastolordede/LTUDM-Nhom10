package vn.edu.p2p.common.dto;

import java.util.List;

/**
 * Full metadata of a shared file, built locally by the sharing Peer
 * before it is published to the Tracker (see FileMetadataBuilder).
 *
 * Mirrors the files table: file_name, file_size, file_hash (SHA-256),
 * piece_size, piece_count, plus the per-piece list (file_pieces table).
 */
public record FileMetadata(
        String fileName,
        long fileSize,
        String fileHash,
        int pieceSize,
        int pieceCount,
        List<PieceInfo> pieces
) {
    public FileMetadata {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (fileSize < 0) {
            throw new IllegalArgumentException("fileSize must be >= 0");
        }
        if (fileHash == null || !fileHash.matches("^[0-9A-Fa-f]{64}$")) {
            throw new IllegalArgumentException("fileHash must be a 64-char SHA-256 hex string");
        }
        if (pieceSize <= 0) {
            throw new IllegalArgumentException("pieceSize must be > 0");
        }
        long expectedCount = fileSize / pieceSize + (fileSize % pieceSize == 0 ? 0 : 1);
        if (expectedCount > Integer.MAX_VALUE || pieceCount != (int) expectedCount) {
            throw new IllegalArgumentException("pieceCount does not match fileSize/pieceSize");
        }
        pieces = pieces == null ? List.of() : List.copyOf(pieces);
        // The large-file publish protocol permits an empty piece list (header only).
        // A nonempty list must describe the entire file, not an arbitrary partial set.
        if (!pieces.isEmpty() && pieces.size() != pieceCount) {
            throw new IllegalArgumentException("Nonempty metadata must contain all pieces");
        }
        for (int i = 0; i < pieces.size(); i++) {
            PieceInfo part = pieces.get(i);
            long start = (long) i * pieceSize;
            int expectedLength = (int) Math.min(pieceSize, fileSize - start);
            if (part == null || part.pieceIndex() != i || part.pieceLength() != expectedLength
                    || part.pieceHash() == null || !part.pieceHash().matches("[0-9A-Fa-f]{64}")) {
                throw new IllegalArgumentException("Invalid piece metadata at index " + i);
            }
        }
    }
}
