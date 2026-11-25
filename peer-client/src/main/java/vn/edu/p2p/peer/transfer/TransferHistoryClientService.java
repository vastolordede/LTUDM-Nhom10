package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.RecordTransferRequest;
import vn.edu.p2p.common.dto.RecordTransferResponse;
import vn.edu.p2p.common.dto.TransferHistoryItemDto;
import vn.edu.p2p.common.dto.TransferHistoryRequest;
import vn.edu.p2p.common.dto.TransferHistoryResponse;
import vn.edu.p2p.common.dto.TransferSourceRecordDto;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.peer.network.TrackerConnection;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * S35-S38: Client service gửi thông tin phiên tải (transfer_sessions & transfer_sources)
 * lên Tracker và truy vấn lịch sử truyền file.
 */
public final class TransferHistoryClientService {

    private final TrackerConnection connection;

    public TransferHistoryClientService(TrackerConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * S35: Tạo transfer session ở trạng thái DOWNLOADING ngay khi bắt đầu tải file.
     */
    public RecordTransferResponse reportTransferStart(
            String sessionId,
            UUID transferId,
            long fileId
    ) throws IOException {
        RecordTransferRequest payload = new RecordTransferRequest(
                sessionId,
                transferId.toString(),
                fileId,
                "DOWNLOADING",
                0L,
                Instant.now().toEpochMilli(),
                null,
                null,
                List.of()
        );
        return sendRecordRequest(payload);
    }

    /**
     * S36-S37: Ghi nhận các source Peer tham gia transfer và cập nhật trạng thái
     * COMPLETED / FAILED / CANCELLED khi kết thúc phiên tải.
     */
    public RecordTransferResponse reportTransferResult(
            String sessionId,
            DownloadSessionResult result
    ) throws IOException {
        List<TransferSourceRecordDto> sourceDtos = new ArrayList<>();
        if (result.sourceStats() != null) {
            for (DownloadSessionResult.SourceTransferStat stat : result.sourceStats()) {
                sourceDtos.add(new TransferSourceRecordDto(
                        stat.sourcePeerId(),
                        stat.bytesReceived(),
                        stat.piecesReceived(),
                        stat.sourceStatus(),
                        stat.connectedAt() != null ? stat.connectedAt().toEpochMilli() : 0L,
                        stat.disconnectedAt() != null ? stat.disconnectedAt().toEpochMilli() : null
                ));
            }
        }

        RecordTransferRequest payload = new RecordTransferRequest(
                sessionId,
                result.transferId().toString(),
                result.fileId(),
                result.status(),
                result.bytesReceived(),
                result.startedAt() != null ? result.startedAt().toEpochMilli() : Instant.now().toEpochMilli(),
                result.completedAt() != null ? result.completedAt().toEpochMilli() : Instant.now().toEpochMilli(),
                result.failureReason(),
                sourceDtos
        );
        return sendRecordRequest(payload);
    }

    /**
     * S38: Lấy danh sách lịch sử tải file của Peer từ Tracker.
     */
    public List<TransferHistoryItemDto> fetchHistory(String sessionId) throws IOException {
        return fetchHistory(sessionId, 0);
    }

    public List<TransferHistoryItemDto> fetchHistory(String sessionId, int page) throws IOException {
        TransferHistoryRequest payload = new TransferHistoryRequest(sessionId, page);
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.TRANSFER_HISTORY_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        MessageEnvelope response = connection.request(request);
        if (response.status() == ResponseStatus.ERROR) {
            throw new IOException(errorText(response));
        }
        if (response.type() != MessageType.TRANSFER_HISTORY_RESPONSE) {
            throw new IOException("Unexpected response type: " + response.type());
        }

        TransferHistoryResponse parsed = ProtocolCodec.fromPayload(
                response.payload(), TransferHistoryResponse.class
        );
        return parsed == null ? List.of() : parsed.items();
    }

    private RecordTransferResponse sendRecordRequest(RecordTransferRequest payload)
            throws IOException {
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.RECORD_TRANSFER_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        MessageEnvelope response = connection.request(request);
        if (response.status() == ResponseStatus.ERROR) {
            throw new IOException(errorText(response));
        }
        if (response.type() != MessageType.RECORD_TRANSFER_RESPONSE) {
            throw new IOException("Unexpected response type: " + response.type());
        }

        return ProtocolCodec.fromPayload(response.payload(), RecordTransferResponse.class);
    }

    private static String errorText(MessageEnvelope response) {
        return response.errorCode() != null
                ? response.errorCode() + ": " + response.message()
                : response.message();
    }
}