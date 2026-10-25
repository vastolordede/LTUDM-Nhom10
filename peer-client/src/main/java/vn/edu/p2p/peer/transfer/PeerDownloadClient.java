package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.BitfieldResponse;
import vn.edu.p2p.common.dto.GetBitfieldRequest;
import vn.edu.p2p.common.dto.MetadataPageRequest;
import vn.edu.p2p.common.dto.MetadataPageResponse;
import vn.edu.p2p.common.dto.PieceRequest;
import vn.edu.p2p.common.dto.PieceResponseHeader;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;

/**
 * S09-S11, S21-S22: Kết nối trực tiếp Peer <-> Peer từ Downloader tới một Uploader
 * để lấy Bitfield và tải dữ liệu nhị phân của từng piece.
 */
public final class PeerDownloadClient {

    public record DownloadedPiece(
            PieceResponseHeader header,
            byte[] data
    ) {
        public DownloadedPiece {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(data, "data");
        }
    }

    private final PeerP2pSocketFactory socketFactory;

    public PeerDownloadClient(PeerP2pSocketFactory socketFactory) {
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
    }

    /**
     * S21-S22: Gửi GET_BITFIELD_REQUEST tới một Peer nguồn và nhận BITFIELD_RESPONSE.
     */
    public BitfieldResponse fetchBitfield(String host, int port, long fileId) throws IOException {
        GetBitfieldRequest payload = new GetBitfieldRequest(fileId);
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.GET_BITFIELD_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        try (Socket socket = socketFactory.openClientSocket(host, port)) {
            ProtocolCodec.write(socket.getOutputStream(), request);
            MessageEnvelope response = ProtocolCodec.read(socket.getInputStream());

            if (response.status() == ResponseStatus.ERROR) {
                throw new IOException("Peer returned error for GET_BITFIELD: "
                        + response.errorCode() + " - " + response.message());
            }
            if (response.type() != MessageType.BITFIELD_RESPONSE) {
                throw new IOException("Unexpected response type for GET_BITFIELD: " + response.type());
            }

            return ProtocolCodec.fromPayload(response.payload(), BitfieldResponse.class);
        }
    }

    public MetadataPageResponse fetchMetadataPage(String host, int port, long fileId,
                                                 int startIndex, int pageSize) throws IOException {
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.METADATA_PAGE_REQUEST, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new MetadataPageRequest(fileId, startIndex, pageSize))
        );
        try (Socket socket = socketFactory.openClientSocket(host, port)) {
            ProtocolCodec.write(socket.getOutputStream(), request);
            MessageEnvelope response = ProtocolCodec.read(socket.getInputStream());
            if (response.status() == ResponseStatus.ERROR || response.type() != MessageType.METADATA_PAGE_RESPONSE) {
                throw new IOException("Peer metadata request failed: " + response.errorCode() + ": " + response.message());
            }
            MetadataPageResponse page = ProtocolCodec.fromPayload(response.payload(), MetadataPageResponse.class);
            if (page == null || page.fileId() != fileId || page.startIndex() != startIndex) {
                throw new IOException("Invalid metadata page response");
            }
            return page;
        }
    }

    /**
     * S03-S05, S10-S11: Gửi REQUEST_PIECE tới Peer nguồn, đọc PIECE_RESPONSE header
     * và đọc tiếp mảng byte nhị phân của piece đó.
     */
    public DownloadedPiece downloadPiece(
            String host,
            int port,
            long fileId,
            int pieceIndex
    ) throws IOException {
        PieceRequest payload = new PieceRequest(fileId, pieceIndex);
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.REQUEST_PIECE,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        try (Socket socket = socketFactory.openClientSocket(host, port)) {
            ProtocolCodec.write(socket.getOutputStream(), request);
            MessageEnvelope response = ProtocolCodec.read(socket.getInputStream());

            if (response.status() == ResponseStatus.ERROR) {
                throw new IOException("Peer returned error for REQUEST_PIECE(" + pieceIndex + "): "
                        + response.errorCode() + " - " + response.message());
            }
            if (response.type() != MessageType.PIECE_RESPONSE) {
                throw new IOException("Unexpected response type for REQUEST_PIECE: " + response.type());
            }

            PieceResponseHeader header = ProtocolCodec.fromPayload(
                    response.payload(), PieceResponseHeader.class
            );
            byte[] bytes = ProtocolCodec.readBinaryPayload(
                    socket.getInputStream(),
                    header.pieceLength()
            );

            return new DownloadedPiece(header, bytes);
        }
    }
}