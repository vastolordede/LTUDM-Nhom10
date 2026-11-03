package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.BitfieldResponse;
import vn.edu.p2p.common.security.PeerAccessTickets;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.common.security.RequestReplayGuard;
import vn.edu.p2p.peer.auth.TicketRevocationList;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import vn.edu.p2p.common.dto.FileMetadata;
import vn.edu.p2p.common.dto.GetBitfieldRequest;
import vn.edu.p2p.common.dto.MetadataPageRequest;
import vn.edu.p2p.common.dto.MetadataPageResponse;
import vn.edu.p2p.common.dto.PieceInfo;
import vn.edu.p2p.common.dto.PieceRequest;
import vn.edu.p2p.common.dto.PieceResponseHeader;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.peer.share.FileHashUtil;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * S01-S06, S21-S22, S27-S28:
 * Máy chủ P2P Upload lắng nghe trên listeningPort của Peer.
 * Cho phép nhiều Peer khác kết nối đồng thời để lấy Bitfield và tải từng Piece.
 */
public final class PeerUploadServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PeerUploadServer.class.getName());
    private static final int DEFAULT_MAX_UPLOAD_WORKERS = 16;

    private final int listeningPort;
    private final LocalFileRegistry fileRegistry;
    private final PublicKey ticketVerificationKey;
    private final boolean allowInsecureLoopbackDemo;
    private final Clock clock;
    private final RequestReplayGuard replayGuard;
    private volatile TicketRevocationList ticketRevocations;
    private volatile String uploaderPeerId;
    private final PeerP2pSocketFactory socketFactory;
    private final ExecutorService clientWorkers;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalBytesUploaded = new AtomicLong(0);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public PeerUploadServer(
            int listeningPort,
            LocalFileRegistry fileRegistry,
            PeerP2pSocketFactory socketFactory
    ) {
        this(listeningPort, fileRegistry, socketFactory, DEFAULT_MAX_UPLOAD_WORKERS);
    }

    public PeerUploadServer(
            int listeningPort,
            LocalFileRegistry fileRegistry,
            PeerP2pSocketFactory socketFactory,
            int maxUploadWorkers
    ) {
        this(listeningPort, fileRegistry, socketFactory, maxUploadWorkers,
                PeerAccessTickets.optionalPeerKey(), PeerAccessTickets.localInsecureDemoEnabled(),
                Clock.systemUTC(), new RequestReplayGuard(), new TicketRevocationList());
    }

    /** Package-private key injection for deterministic tests only. */
    PeerUploadServer(int listeningPort, LocalFileRegistry fileRegistry,
                     PeerP2pSocketFactory socketFactory, int maxUploadWorkers,
                     PublicKey ticketVerificationKey, boolean allowInsecureLoopbackDemo) {
        this(listeningPort, fileRegistry, socketFactory, maxUploadWorkers, ticketVerificationKey,
                allowInsecureLoopbackDemo, Clock.systemUTC(), new RequestReplayGuard(),
                new TicketRevocationList());
    }

    PeerUploadServer(int listeningPort, LocalFileRegistry fileRegistry,
                     PeerP2pSocketFactory socketFactory, int maxUploadWorkers,
                     PublicKey ticketVerificationKey, boolean allowInsecureLoopbackDemo,
                     Clock clock, RequestReplayGuard replayGuard) {
        this(listeningPort, fileRegistry, socketFactory, maxUploadWorkers, ticketVerificationKey,
                allowInsecureLoopbackDemo, clock, replayGuard, new TicketRevocationList(clock));
    }

    PeerUploadServer(int listeningPort, LocalFileRegistry fileRegistry,
                     PeerP2pSocketFactory socketFactory, int maxUploadWorkers,
                     PublicKey ticketVerificationKey, boolean allowInsecureLoopbackDemo,
                     Clock clock, RequestReplayGuard replayGuard,
                     TicketRevocationList ticketRevocations) {
        this.listeningPort = listeningPort;
        this.fileRegistry = fileRegistry;
        this.ticketVerificationKey = ticketVerificationKey;
        this.allowInsecureLoopbackDemo = allowInsecureLoopbackDemo;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.replayGuard = java.util.Objects.requireNonNull(replayGuard, "replayGuard");
        this.ticketRevocations = java.util.Objects.requireNonNull(ticketRevocations, "ticketRevocations");
        this.socketFactory = socketFactory;
        if (maxUploadWorkers <= 0) throw new IllegalArgumentException("maxUploadWorkers must be > 0");
        this.clientWorkers = new ThreadPoolExecutor(maxUploadWorkers, maxUploadWorkers,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), runnable -> {
                    Thread thread = new Thread(runnable, "peer-upload-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** S01-S02: Bắt đầu mở cổng lắng nghe kết nối từ các Peer khác. */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        if (ticketVerificationKey == null && !allowInsecureLoopbackDemo) {
            throw new IOException("Missing Tracker Ed25519 PUBLIC key for Peer upload server. "
                    + "Copy tracker-public.key.b64 to ~/.fileshare-p2p or set "
                    + "P2P_TICKET_PUBLIC_KEY_FILE; do not disable ticket checks on LAN.");
        }
        serverSocket = socketFactory.openServerSocket(listeningPort);
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "peer-upload-acceptor-" + listeningPort);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** Must be set after login and before accepting requests; tickets target this peer. */
    public void setUploaderPeerId(String peerId) {
        if (running.get()) throw new IllegalStateException("Cannot replace peer identity while serving");
        this.uploaderPeerId = peerId;
    }

    public void setTicketRevocationList(TicketRevocationList ticketRevocations) {
        if (running.get()) throw new IllegalStateException("Cannot replace revocation list while serving");
        this.ticketRevocations = java.util.Objects.requireNonNull(ticketRevocations, "ticketRevocations");
    }

    public int listeningPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : listeningPort;
    }

    public long totalBytesUploaded() {
        return totalBytesUploaded.get();
    }

    /** D79: phục vụ một socket đã được relay nối sẵn. */
        /** D79, D81: phục vụ socket do relay nối sẵn; bọc TLS nếu P2P TLS đang bật. */
    public void serveRelayedSocket(Socket socket) {
        if (!running.get()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(socketFactory.readTimeoutMillis());
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        try {
            clientWorkers.submit(() -> {
                try {
                    handlePeerConnection(socketFactory.wrapServer(socket));
                } catch (IOException e) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
            });
        } catch (RejectedExecutionException rejected) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket peerSocket = serverSocket.accept();
                peerSocket.setTcpNoDelay(true);
                peerSocket.setSoTimeout(socketFactory.readTimeoutMillis());
                try {
                    clientWorkers.submit(() -> handlePeerConnection(peerSocket));
                } catch (RejectedExecutionException rejected) {
                    peerSocket.close();
                }
            } catch (SocketException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "Upload server socket error", e);
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "Failed to accept peer connection", e);
                }
            }
        }
    }

    private void handlePeerConnection(Socket socket) {
        try (Socket client = socket) {
            while (running.get() && !client.isClosed()) {
                final MessageEnvelope request;
                try {
                    request = ProtocolCodec.read(client.getInputStream());
                } catch (EOFException e) {
                    break;
                }

                if (request.version() != MessageEnvelope.CURRENT_VERSION) {
                    ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.error(
                            MessageType.ERROR, request.requestId(), "UNSUPPORTED_PROTOCOL_VERSION",
                            "Peer upload server supports protocol version "
                                    + MessageEnvelope.CURRENT_VERSION + ", received " + request.version()));
                    continue;
                }

                switch (request.type()) {
                    case GET_BITFIELD_REQUEST -> handleGetBitfield(client, request);
                    case METADATA_PAGE_REQUEST -> handleMetadataPage(client, request);
                    case REQUEST_PIECE -> handleRequestPiece(client, request);
                    default -> {
                        MessageEnvelope err = MessageEnvelope.error(
                                MessageType.ERROR,
                                request.requestId(),
                                "UNSUPPORTED_P2P_COMMAND",
                                "Unsupported P2P request type: " + request.type()
                        );
                        ProtocolCodec.write(client.getOutputStream(), err);
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Peer upload connection closed: " + e.getMessage(), e);
        }
    }

    /** S21-S22: Xử lý GET_BITFIELD_REQUEST và trả về BITFIELD_RESPONSE. */
    private void handleGetBitfield(Socket client, MessageEnvelope request) throws IOException {
        GetBitfieldRequest payload = ProtocolCodec.fromPayload(
                request.payload(), GetBitfieldRequest.class
        );

        if (payload == null) {
            deny(client, request, MessageType.BITFIELD_RESPONSE, "invalid request");
            return;
        }
        if (!authorizeDownloadRequest(client, request, payload.fileId(),
                payload.accessTicket(), PeerRequestProof.RequestType.BITFIELD,
                PeerRequestProof.BITFIELD_PIECE_INDEX, payload.proofTimestamp(),
                payload.proofNonce(), payload.proofSignature(), MessageType.BITFIELD_RESPONSE)) return;
        Optional<LocalFileRegistry.RegisteredFile> found = fileRegistry.find(payload.fileId());
        if (found.isEmpty()) {
            MessageEnvelope err = MessageEnvelope.error(
                    MessageType.BITFIELD_RESPONSE,
                    request.requestId(),
                    "FILE_NOT_FOUND",
                    "Peer does not have fileId=" + payload.fileId()
            );
            ProtocolCodec.write(client.getOutputStream(), err);
            return;
        }

        LocalFileRegistry.RegisteredFile reg = found.get();
        BitfieldResponse responsePayload = new BitfieldResponse(
                reg.fileId(),
                reg.bitfieldBytes(),
                reg.metadata().pieceCount() > 2000
                        ? new FileMetadata(reg.metadata().fileName(), reg.metadata().fileSize(),
                                reg.metadata().fileHash(), reg.metadata().pieceSize(),
                                reg.metadata().pieceCount(), java.util.List.of())
                        : reg.metadata()
        );

        MessageEnvelope response = MessageEnvelope.success(
                MessageType.BITFIELD_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(responsePayload)
        );
        ProtocolCodec.write(client.getOutputStream(), response);
    }

    private void handleMetadataPage(Socket client, MessageEnvelope request) throws IOException {
        MetadataPageRequest payload = ProtocolCodec.fromPayload(request.payload(), MetadataPageRequest.class);
        if (payload == null || payload.startIndex() < 0 || payload.pageSize() < 1
                || payload.pageSize() > 400) {
            ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.error(
                    MessageType.METADATA_PAGE_RESPONSE, request.requestId(),
                    "INVALID_METADATA_PAGE", "Invalid metadata page bounds"));
            return;
        }
        if (!authorizeTicket(client, request, payload.fileId(), payload.accessTicket(),
                MessageType.METADATA_PAGE_RESPONSE)) return;
        Optional<LocalFileRegistry.RegisteredFile> file = fileRegistry.find(payload.fileId());
        if (file.isEmpty() || payload.startIndex() >= file.get().metadata().pieceCount()) {
            ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.error(
                    MessageType.METADATA_PAGE_RESPONSE, request.requestId(),
                    "FILE_NOT_FOUND", "Metadata page not available"));
            return;
        }
        var pieces = file.get().metadata().pieces();
        int from = payload.startIndex();
        int to = Math.min(from + payload.pageSize(), pieces.size());
        var response = new MetadataPageResponse(payload.fileId(), from, pieces.subList(from, to));
        ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.success(
                MessageType.METADATA_PAGE_RESPONSE, request.requestId(), ProtocolCodec.toPayload(response)));
    }

    /**
     * S03-S06 & S28: Xử lý REQUEST_PIECE, kiểm tra mảnh đã hoàn thành trong BitSet,
     * đọc đúng offset từ ổ cứng và gửi PIECE_RESPONSE header + binary payload.
     */
    private void handleRequestPiece(Socket client, MessageEnvelope request) throws IOException {
        PieceRequest payload = ProtocolCodec.fromPayload(
                request.payload(), PieceRequest.class
        );

        if (payload == null) {
            deny(client, request, MessageType.PIECE_RESPONSE, "invalid request");
            return;
        }
        if (!authorizeDownloadRequest(client, request, payload.fileId(),
                payload.accessTicket(), PeerRequestProof.RequestType.PIECE, payload.pieceIndex(),
                payload.proofTimestamp(), payload.proofNonce(), payload.proofSignature(),
                MessageType.PIECE_RESPONSE)) return;
        Optional<LocalFileRegistry.RegisteredFile> found = fileRegistry.find(payload.fileId());
        if (found.isEmpty()) {
            MessageEnvelope err = MessageEnvelope.error(
                    MessageType.PIECE_RESPONSE,
                    request.requestId(),
                    "FILE_NOT_FOUND",
                    "Peer does not have fileId=" + payload.fileId()
            );
            ProtocolCodec.write(client.getOutputStream(), err);
            return;
        }

        LocalFileRegistry.RegisteredFile reg = found.get();
        if (!reg.hasPiece(payload.pieceIndex())) {
            MessageEnvelope err = MessageEnvelope.error(
                    MessageType.PIECE_RESPONSE,
                    request.requestId(),
                    "PIECE_NOT_AVAILABLE",
                    "Piece " + payload.pieceIndex() + " is not available on this peer"
            );
            ProtocolCodec.write(client.getOutputStream(), err);
            return;
        }

        byte[] pieceBytes = fileRegistry.readPieceBytes(payload.fileId(), payload.pieceIndex());
        String pieceHash = resolvePieceHash(reg.metadata(), payload.pieceIndex(), pieceBytes);

        PieceResponseHeader headerPayload = new PieceResponseHeader(
                payload.fileId(),
                payload.pieceIndex(),
                pieceBytes.length,
                pieceHash
        );

        MessageEnvelope headerEnvelope = MessageEnvelope.success(
                MessageType.PIECE_RESPONSE,
                request.requestId(),
                ProtocolCodec.toPayload(headerPayload)
        );

        ProtocolCodec.writeWithBinaryPayload(
                client.getOutputStream(),
                headerEnvelope,
                pieceBytes,
                0,
                pieceBytes.length
        );
        totalBytesUploaded.addAndGet(pieceBytes.length);
    }

    private boolean authorizeDownloadRequest(Socket client, MessageEnvelope request, long fileId,
                                             String ticket, PeerRequestProof.RequestType requestType,
                                             int pieceIndex, Long proofTimestamp, String proofNonce,
                                             String proofSignature, MessageType responseType)
            throws IOException {
        PeerAccessTickets.TicketClaims claims = ticketVerificationKey == null || uploaderPeerId == null
                ? null : PeerAccessTickets.verifyClaims(ticketVerificationKey, ticket, fileId,
                        uploaderPeerId, clock.instant());
        if (claims == null) {
            if (isInsecureLoopbackDemo(client)) return true;
            return deny(client, request, responseType, "invalid ticket");
        }
        if (ticketRevocations.isRevoked(claims, uploaderPeerId, fileId)) {
            return deny(client, request, responseType, "revoked ticket");
        }

        if (proofTimestamp == null || proofNonce == null || proofSignature == null
                || proofNonce.isBlank() || proofSignature.isBlank()) {
            return deny(client, request, responseType, "missing proof");
        }
        PeerRequestProof.Proof proof = new PeerRequestProof.Proof(
                proofTimestamp, proofNonce, proofSignature);
        if (!PeerRequestProof.verify(claims.requesterPublicKey(), requestType, uploaderPeerId,
                fileId, pieceIndex, ticket, proof, clock)) {
            return deny(client, request, responseType, "invalid proof");
        }
        if (!replayGuard.accept(claims.requesterId(), proofNonce)) {
            return deny(client, request, responseType, "replayed proof");
        }
        return true;
    }

    private boolean authorizeTicket(Socket client, MessageEnvelope request, long fileId,
                                    String ticket, MessageType responseType) throws IOException {
        PeerAccessTickets.TicketClaims claims = ticketVerificationKey != null && uploaderPeerId != null
                ? PeerAccessTickets.verifyClaims(ticketVerificationKey, ticket, fileId,
                        uploaderPeerId, clock.instant()) : null;
        boolean allowed = claims != null
                && !ticketRevocations.isRevoked(claims, uploaderPeerId, fileId);
        if (!allowed && isInsecureLoopbackDemo(client)) return true;
        if (allowed) return true;
        return deny(client, request, responseType, "invalid ticket");
    }

    private boolean isInsecureLoopbackDemo(Socket client) {
        return ticketVerificationKey == null && allowInsecureLoopbackDemo
                && client.getInetAddress().isLoopbackAddress();
    }

    private boolean deny(Socket client, MessageEnvelope request, MessageType responseType,
                         String reason) throws IOException {
        LOG.fine(() -> "Peer request denied: " + reason);
        ProtocolCodec.write(client.getOutputStream(), MessageEnvelope.error(responseType,
                request.requestId(), "ACCESS_DENIED", "A valid Tracker-issued authorization is required"));
        return false;
    }

    private static String resolvePieceHash(FileMetadata meta, int pieceIndex, byte[] pieceBytes) {
        if (!meta.pieces().isEmpty() && pieceIndex < meta.pieces().size()) {
            PieceInfo info = meta.pieces().get(pieceIndex);
            if (info.pieceHash() != null && !info.pieceHash().isBlank()) {
                return info.pieceHash();
            }
        }
        return FileHashUtil.sha256(pieceBytes, 0, pieceBytes.length);
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        clientWorkers.shutdownNow();
    }
}