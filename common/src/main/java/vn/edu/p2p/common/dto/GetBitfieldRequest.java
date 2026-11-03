package vn.edu.p2p.common.dto;

/**
 * S21: Request gửi trực tiếp từ Downloader Peer tới Uploader Peer
 * để hỏi xem Uploader đang có những piece nào của fileId.
 */
public record GetBitfieldRequest(
        long fileId,
        String accessTicket,
        Long proofTimestamp,
        String proofNonce,
        String proofSignature
) {
    public GetBitfieldRequest(long fileId, String accessTicket) {
        this(fileId, accessTicket, null, null, null);
    }

    public GetBitfieldRequest(long fileId) {
        this(fileId, null, null, null, null);
    }

    public GetBitfieldRequest {
        if (fileId <= 0) {
            throw new IllegalArgumentException("fileId must be > 0");
        }
    }
}