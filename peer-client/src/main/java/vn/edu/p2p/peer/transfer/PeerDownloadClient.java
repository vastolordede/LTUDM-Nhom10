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
import vn.edu.p2p.common.protocol.ProtocolCompatibilityException;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.common.security.PeerRequestProof;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.PrivateKey;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final PrivateKey requesterPrivateKey;
    private final Clock clock;
    private ConnectionPlanner planner;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public PeerDownloadClient(PeerP2pSocketFactory socketFactory, PrivateKey requesterPrivateKey) {
        this(socketFactory, requesterPrivateKey, Clock.systemUTC());
    }

    PeerDownloadClient(PeerP2pSocketFactory socketFactory, PrivateKey requesterPrivateKey,
                       Clock clock) {
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
        this.requesterPrivateKey = Objects.requireNonNull(requesterPrivateKey, "requesterPrivateKey");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PeerDownloadClient withPlanner(ConnectionPlanner planner) {
        this.planner = planner;
        return this;
    }

    private Socket open(String host, int port) throws IOException {
        if (cancelled.get()) {
            throw new java.io.InterruptedIOException("Download client was cancelled");
        }
        Socket socket = planner == null
                ? socketFactory.openClientSocket(host, port) : planner.open(host, port);
        activeSockets.add(socket);
        if (cancelled.get()) {
            activeSockets.remove(socket);
            socket.close();
            if (planner != null) planner.connectionClosed(socket);
            throw new java.io.InterruptedIOException("Download client was cancelled");
        }
        return socket;
    }

    public void cancelActiveRequests() {
        cancelled.set(true);
        for (Socket socket : activeSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            } finally {
                activeSockets.remove(socket);
                if (planner != null) planner.connectionClosed(socket);
            }
        }
    }

    private void release(Socket socket) {
        activeSockets.remove(socket);
        if (planner != null) planner.connectionClosed(socket);
    }

    /**
     * S21-S22: Gửi GET_BITFIELD_REQUEST tới một Peer nguồn và nhận BITFIELD_RESPONSE.
     */
    public BitfieldResponse fetchBitfield(String host, int port, String uploaderId, long fileId,
                                          String accessTicket) throws IOException {
        PeerRequestProof.Proof proof = PeerRequestProof.sign(requesterPrivateKey,
                PeerRequestProof.RequestType.BITFIELD, uploaderId, fileId,
                PeerRequestProof.BITFIELD_PIECE_INDEX, accessTicket, clock);
        GetBitfieldRequest payload = new GetBitfieldRequest(fileId, accessTicket,
                proof.timestampEpochMillis(), proof.requestNonce(), proof.signature());
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.GET_BITFIELD_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        Socket socket = open(host, port);
        try (socket) {
            ProtocolCodec.write(socket.getOutputStream(), request);
            MessageEnvelope response = readResponse(socket.getInputStream());

            if (response.status() == ResponseStatus.ERROR) {
                throw new IOException("Peer returned error for GET_BITFIELD: "
                        + response.errorCode() + " - " + response.message());
            }
            if (response.type() != MessageType.BITFIELD_RESPONSE) {
                throw new IOException("Unexpected response type for GET_BITFIELD: " + response.type());
            }

            return ProtocolCodec.fromPayload(response.payload(), BitfieldResponse.class);
        } finally {
            release(socket);
        }
    }

    /** Metadata pages require requester identity and a bounded, one-time proof. */
    public MetadataPageResponse fetchMetadataPage(String host, int port, String uploaderId,
                                                  long fileId, int startIndex, int pageSize,
                                                  String accessTicket) throws IOException {
        PeerRequestProof.Proof proof = PeerRequestProof.signMetadataPage(
                requesterPrivateKey, uploaderId, fileId, startIndex, pageSize, accessTicket, clock);
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.METADATA_PAGE_REQUEST, UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new MetadataPageRequest(fileId, startIndex, pageSize,
                        accessTicket, proof.timestampEpochMillis(), proof.requestNonce(), proof.signature()))
        );
        Socket socket = open(host, port);
        try (socket) {
            ProtocolCodec.write(socket.getOutputStream(), request);
            MessageEnvelope response = readResponse(socket.getInputStream());
            if (response.status() == ResponseStatus.ERROR || response.type() != MessageType.METADATA_PAGE_RESPONSE) {
                throw new IOException("Peer metadata request failed: " + response.errorCode() + ": " + response.message());
            }
            MetadataPageResponse page = ProtocolCodec.fromPayload(response.payload(), MetadataPageResponse.class);
            if (page == null || page.fileId() != fileId || page.startIndex() != startIndex) {
                throw new IOException("Invalid metadata page response");
            }
            return page;
        } finally {
            release(socket);
        }
    }

    /**
     * S03-S05, S10-S11: Gửi REQUEST_PIECE tới Peer nguồn, đọc PIECE_RESPONSE header
     * và đọc tiếp mảng byte nhị phân của piece đó.
     */
    public DownloadedPiece downloadPiece(String host, int port, String uploaderId, long fileId,
                                         int pieceIndex, String accessTicket) throws IOException {
        PeerRequestProof.Proof proof = PeerRequestProof.sign(requesterPrivateKey,
                PeerRequestProof.RequestType.PIECE, uploaderId, fileId,
                pieceIndex, accessTicket, clock);
        PieceRequest payload = new PieceRequest(fileId, pieceIndex, accessTicket,
                proof.timestampEpochMillis(), proof.requestNonce(), proof.signature());
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.REQUEST_PIECE,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(payload)
        );

        Socket socket = open(host, port);
        try (socket) {
            ProtocolCodec.write(socket.getOutputStream(), request);
            MessageEnvelope response = readResponse(socket.getInputStream());

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
        } finally {
            release(socket);
        }
    }

    private static MessageEnvelope readResponse(InputStream input) throws IOException {
        MessageEnvelope response = ProtocolCodec.read(input);
        ProtocolCompatibilityException.validateResponse(response, "Peer upload server");
        return response;
    }
}