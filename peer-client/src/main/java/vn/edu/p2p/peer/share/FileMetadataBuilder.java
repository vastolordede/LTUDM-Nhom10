package vn.edu.p2p.peer.share;

import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.PieceInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads a local file chosen by the user (T02) and builds the FileMetadata
 * (T03-T09) needed before publishing to the Tracker (T11, next phase).
 *
 * This does blocking I/O (reading the whole file + hashing every piece),
 * so callers must run build(...) off the JavaFX Application Thread
 * (see ShareController, which submits this to a background executor).
 */
public final class FileMetadataBuilder {

    private final int pieceSize;

    public FileMetadataBuilder() {
        this(0);
    }

    public FileMetadataBuilder(int pieceSize) {
        if (pieceSize < 0) {
            throw new IllegalArgumentException("pieceSize must be >= 0 (0 = automatic)");
        }
        this.pieceSize = pieceSize;
    }

    public FileMetadata build(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Not a regular file: " + file);
        }

        long fileSize = Files.size(file);
        String fileName = file.getFileName().toString();

        // T05: SHA-256 of the whole file.
        String fileHash = FileHashUtil.sha256(file);

        // T06-T08: split into pieces (size, count, and a correctly sized last piece).
        int actualPieceSize = pieceSize == 0 ? FileChunker.choosePieceSize(fileSize) : pieceSize;
        List<PieceInfo> pieces = FileChunker.chunk(file, actualPieceSize);
        int pieceCount = pieces.size();

        // T09: assemble the FileMetadata object.
        return new FileMetadata(
                fileName,
                fileSize,
                fileHash,
                actualPieceSize,
                pieceCount,
                pieces
        );
    }
}
