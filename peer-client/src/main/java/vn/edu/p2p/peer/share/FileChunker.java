package vn.edu.p2p.peer.share;

import vn.edu.p2p.common.dto.PieceInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a file into fixed-size pieces and hashes each one.
 * Matches the file_pieces table: (piece_index, piece_length, piece_hash).
 */
public final class FileChunker {

    /** Default piece size: 256 KiB. Kept as a constant so peer-client and
     *  tracker-server can agree on a value until we make it configurable. */
    public static final int DEFAULT_PIECE_SIZE = 256 * 1024;

    /** Adaptive piece sizes, reducing metadata and RAM for large files. */
    public static int choosePieceSize(long fileSize) {
        if (fileSize <= 512L * 1024 * 1024) return 256 * 1024;
        if (fileSize <= 4L * 1024 * 1024 * 1024) return 1024 * 1024;
        if (fileSize <= 32L * 1024 * 1024 * 1024) return 4 * 1024 * 1024;
        return 16 * 1024 * 1024;
    }

    private FileChunker() {
    }

    /**
     * Number of pieces for a file of the given size.
     * An empty file (size 0) has zero pieces.
     */
    public static int pieceCount(long fileSize, int pieceSize) {
        if (pieceSize <= 0) {
            throw new IllegalArgumentException("pieceSize must be > 0");
        }
        if (fileSize == 0) {
            return 0;
        }
        long count = (fileSize / pieceSize) + (fileSize % pieceSize == 0 ? 0 : 1);
        if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("File has too many pieces");
        return (int) count;
    }

    /**
     * Reads the file sequentially and builds one PieceInfo per chunk.
     * The last piece is naturally shorter than pieceSize when fileSize
     * is not an exact multiple of pieceSize; this is computed from the
     * remaining bytes, not assumed to be a full piece.
     */
    public static List<PieceInfo> chunk(Path file, int pieceSize) throws IOException {
        long fileSize = Files.size(file);
        int count = pieceCount(fileSize, pieceSize);
        List<PieceInfo> pieces = new ArrayList<>(count);

        byte[] buffer = new byte[pieceSize];

        try (InputStream input = Files.newInputStream(file)) {
            for (int index = 0; index < count; index++) {
                int remaining = (int) Math.min(
                        pieceSize,
                        fileSize - (long) index * pieceSize
                );

                int totalRead = 0;
                while (totalRead < remaining) {
                    int read = input.read(buffer, totalRead, remaining - totalRead);
                    if (read == -1) {
                        break;
                    }
                    totalRead += read;
                }

                if (totalRead != remaining) {
                    throw new IOException(
                            "Unexpected end of file while reading piece "
                                    + index + " of " + file
                    );
                }

                String pieceHash = FileHashUtil.sha256(buffer, 0, remaining);
                pieces.add(new PieceInfo(index, remaining, pieceHash));
            }
        }

        return pieces;
    }
}
