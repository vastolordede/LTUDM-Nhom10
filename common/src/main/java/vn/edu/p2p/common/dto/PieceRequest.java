package vn.edu.p2p.common.dto;

/**
 * S03: Lệnh REQUEST_PIECE yêu cầu tải một mảnh cụ thể (pieceIndex) của fileId.
 */
public record PieceRequest(
        long fileId,
        int pieceIndex,
        String accessTicket,
        Long proofTimestamp,
        String proofNonce,
        String proofSignature
) {
    public PieceRequest(long fileId, int pieceIndex) {
        this(fileId, pieceIndex, null, null, null, null);
    }

    public PieceRequest(long fileId, int pieceIndex, String accessTicket) {
        this(fileId, pieceIndex, accessTicket, null, null, null);
    }

    public PieceRequest {
        if (fileId <= 0) {
            throw new IllegalArgumentException("fileId must be > 0");
        }
        if (pieceIndex < 0) {
            throw new IllegalArgumentException("pieceIndex must be >= 0");
        }
    }
}