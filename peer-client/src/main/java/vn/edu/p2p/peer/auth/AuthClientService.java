package vn.edu.p2p.peer.auth;

import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.dto.RegisterRequest;
import vn.edu.p2p.common.dto.RegisterResponse;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.common.protocol.ResponseStatus;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.peer.auth.TicketRevocationList;
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.session.ClientSession;

import java.io.IOException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class AuthClientService implements AutoCloseable {
    private static final AtomicLong SESSION_GENERATION = new AtomicLong();

    private final TrackerConnection connection;
    private final PeerIdentity peerIdentity;
    private final PeerIdentityStore identityStore;
    private final PeerKeyStore peerKeyStore;
    private final TicketRevocationList ticketRevocations;
    private final Object sessionStateLock = new Object();
    private volatile ClientSession currentSession;
    private volatile boolean closed;

    public static long sessionGeneration() {
        return SESSION_GENERATION.get();
    }

    public static boolean isCurrentGeneration(long generation) {
        return SESSION_GENERATION.get() == generation;
    }

    public AuthClientService(
            TrackerConnection connection,
            PeerIdentity peerIdentity
    ) {
        this.connection = connection;
        this.peerIdentity = peerIdentity;
        this.identityStore = null;
        this.peerKeyStore = PeerKeyStore.defaultStore();
        this.ticketRevocations = new TicketRevocationList();
    }

    /** Desktop account switching: resolve identity per user, preserving legacy peer.id. */
    public AuthClientService(TrackerConnection connection, PeerIdentityStore identityStore,
                             PeerIdentity initialIdentity) {
        this(connection, identityStore, initialIdentity,
                new PeerKeyStore(identityStore.directory()), new TicketRevocationList());
    }

    public AuthClientService(TrackerConnection connection, PeerIdentityStore identityStore,
                             PeerIdentity initialIdentity, PeerKeyStore peerKeyStore) {
        this(connection, identityStore, initialIdentity, peerKeyStore, new TicketRevocationList());
    }

    public AuthClientService(TrackerConnection connection, PeerIdentityStore identityStore,
                             PeerIdentity initialIdentity, PeerKeyStore peerKeyStore,
                             TicketRevocationList ticketRevocations) {
        this.connection = connection;
        this.identityStore = identityStore;
        this.peerIdentity = initialIdentity;
        this.peerKeyStore = peerKeyStore;
        this.ticketRevocations = ticketRevocations;
    }

    public synchronized ClientSession login(
            String username,
            String password,
            int listeningPort
    ) throws AuthClientException {
        if (closed) {
            throw new AuthClientException("CLIENT_CLOSED", "Peer authentication service is closed.");
        }
        PeerIdentity currentIdentity = peerIdentity;
        if (identityStore != null) {
            try {
                currentIdentity = identityStore.loadOrCreateForAccount(username);
            } catch (IOException e) {
                throw new AuthClientException("LOCAL_IDENTITY_ERROR",
                        "Cannot load per-account Peer ID: " + e.getMessage(), e);
            }
        }
        MessageEnvelope response = sendLogin(username, password, listeningPort, currentIdentity);
        requireType(response, MessageType.LOGIN_RESPONSE);
        if (identityStore != null && response.status() == ResponseStatus.ERROR
                && "PEER_OWNERSHIP_MISMATCH".equals(response.errorCode())) {
            // Credentials are validated by Tracker before the mismatch. Retry only
            // this case once with a separate persistent ID for this account.
            try {
                currentIdentity = identityStore.rotateForAccount(username);
            } catch (IOException e) {
                throw new AuthClientException("LOCAL_IDENTITY_ERROR",
                        "Cannot save account-specific Peer ID: " + e.getMessage(), e);
            }
            response = sendLogin(username, password, listeningPort, currentIdentity);
            requireType(response, MessageType.LOGIN_RESPONSE);
        }
        requireSuccess(response);

        try {
            LoginResponse loginResponse = ProtocolCodec.fromPayload(
                    response.payload(),
                    LoginResponse.class
            );
            if (loginResponse == null) {
                throw new AuthClientException(
                        "INVALID_RESPONSE",
                        "Tracker returned an empty login response."
                );
            }

            ClientSession session = new ClientSession(loginResponse.peerId(),
                    loginResponse.sessionId(), loginResponse.heartbeatIntervalSeconds());
            synchronized (sessionStateLock) {
                if (closed) {
                    throw new AuthClientException("CLIENT_CLOSED",
                            "Peer authentication service closed while login was in progress.");
                }
                ticketRevocations.reset();
                currentSession = session;
                SESSION_GENERATION.incrementAndGet();
            }
            return currentSession;
        } catch (IOException e) {
            throw new AuthClientException(
                    "INVALID_RESPONSE",
                    "Cannot decode Tracker login response.",
                    e
            );
        }
    }

    private MessageEnvelope sendLogin(String username, String password, int listeningPort,
                                      PeerIdentity identity) throws AuthClientException {
        final KeyPair peerKeys;
        try {
            peerKeys = peerKeyStore.loadOrCreate(identity.peerId());
        } catch (IOException e) {
            throw new AuthClientException("LOCAL_KEY_ERROR",
                    "Cannot load or create the Peer identity key: " + e.getMessage(), e);
        }
        LoginRequest payload = new LoginRequest(username, password, listeningPort,
                identity.peerId(), identity.deviceName(),
                PeerRequestProof.encodePublicKey(peerKeys.getPublic()));
        MessageEnvelope request = MessageEnvelope.request(MessageType.LOGIN_REQUEST,
                UUID.randomUUID().toString(), ProtocolCodec.toPayload(payload));
        return send(request);
    }

    /** Register an account without logging this Peer in automatically. */
    public synchronized RegisterResponse register(String username, String password)
            throws AuthClientException {
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.REGISTER_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(new RegisterRequest(username, password)));
        MessageEnvelope response = send(request);
        requireType(response, MessageType.REGISTER_RESPONSE);
        requireSuccess(response);
        try {
            return ProtocolCodec.fromPayload(response.payload(), RegisterResponse.class);
        } catch (IOException e) {
            throw new AuthClientException("INVALID_RESPONSE", "Cannot decode registration response.", e);
        }
    }

    public void logout() throws AuthClientException {
        ClientSession session = currentSession;
        if (session == null) {
            return;
        }

        MessageEnvelope response = send(logoutRequest(session));
        requireType(response, MessageType.LOGOUT_RESPONSE);
        requireSuccess(response);
        clearSessionIfCurrent(session);
    }

    public boolean logoutBestEffort(Duration timeout) {
        ClientSession session = currentSession;
        invalidateLocalSession();
        return logoutBestEffort(session, timeout);
    }

    public boolean logoutBestEffort(ClientSession session, Duration timeout) {
        if (session == null) {
            return true;
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        FutureTask<Boolean> task = new FutureTask<>(() -> {
            MessageEnvelope response = connection.request(logoutRequest(session));
            requireType(response, MessageType.LOGOUT_RESPONSE);
            requireSuccess(response);
            return true;
        });
        Thread worker = new Thread(task, "peer-best-effort-logout");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            connection.abortCurrentRequest();
            task.cancel(true);
            return false;
        } catch (InterruptedException e) {
            connection.abortCurrentRequest();
            task.cancel(true);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    private static MessageEnvelope logoutRequest(ClientSession session) {
        MessageEnvelope request = MessageEnvelope.request(
                MessageType.LOGOUT_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(
                        new LogoutRequest(session.sessionId())
                )
        );
        return request;
    }

    private void clearSessionIfCurrent(ClientSession session) {
        if (currentSession == session
                || currentSession != null && currentSession.sessionId().equals(session.sessionId())) {
            invalidateLocalSession();
        }
    }

    public HeartbeatResponse heartbeat() throws AuthClientException {
        ClientSession session = currentSession;
        long generation = SESSION_GENERATION.get();
        if (session == null) {
            throw new AuthClientException(
                    "INVALID_SESSION",
                    "Peer is not logged in."
            );
        }

        MessageEnvelope request = MessageEnvelope.request(
                MessageType.HEARTBEAT,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(
                        new HeartbeatRequest(session.sessionId(),
                                ticketRevocations.latestSequence(),
                                ticketRevocations.trackerInstanceId())
                )
        );

        MessageEnvelope response = send(request);
        requireType(response, MessageType.HEARTBEAT_ACK);
        requireSuccess(response);

        try {
            HeartbeatResponse heartbeatResponse = ProtocolCodec.fromPayload(
                    response.payload(),
                    HeartbeatResponse.class
            );
            if (heartbeatResponse == null) {
                throw new AuthClientException(
                        "INVALID_RESPONSE",
                        "Tracker returned an empty heartbeat response."
                );
            }
            synchronized (sessionStateLock) {
                if (currentSession != session || SESSION_GENERATION.get() != generation) {
                    throw new AuthClientException("INVALID_SESSION", "Peer session has ended.");
                }
                ticketRevocations.apply(heartbeatResponse);
            }
            return heartbeatResponse;
        } catch (IOException e) {
            throw new AuthClientException(
                    "INVALID_RESPONSE",
                    "Cannot decode Tracker heartbeat response.",
                    e
            );
        }
    }

    public ClientSession currentSession() {
        return currentSession;
    }

    public TicketRevocationList ticketRevocations() {
        return ticketRevocations;
    }

    public void invalidateLocalSession() {
        synchronized (sessionStateLock) {
            currentSession = null;
            ticketRevocations.reset();
            SESSION_GENERATION.incrementAndGet();
        }
    }

    private MessageEnvelope send(MessageEnvelope request) throws AuthClientException {
        try {
            return connection.request(request);
        } catch (IOException e) {
            throw new AuthClientException(
                    "NETWORK_ERROR",
                    "Cannot connect to Tracker: " + e.getMessage(),
                    e
            );
        }
    }

    private static void requireType(
            MessageEnvelope response,
            MessageType expected
    ) throws AuthClientException {
        if (response.type() != expected) {
            throw new AuthClientException(
                    "INVALID_RESPONSE",
                    "Expected " + expected + " but received " + response.type()
            );
        }
    }

    private static void requireSuccess(MessageEnvelope response)
            throws AuthClientException {
        if (response.status() != ResponseStatus.SUCCESS) {
            throw new AuthClientException(
                    response.errorCode() == null ? "TRACKER_ERROR" : response.errorCode(),
                    response.message() == null ? "Tracker rejected the request." : response.message()
            );
        }
    }

    /**
     * Best-effort graceful shutdown used when the JavaFX application exits.
     * A network failure must never prevent the local process from closing.
     */
    public void closeGracefully() {
        logoutBestEffort(Duration.ofSeconds(3));
        close();
    }

    @Override
    public void close() {
        synchronized (sessionStateLock) {
            closed = true;
            currentSession = null;
            ticketRevocations.reset();
            SESSION_GENERATION.incrementAndGet();
        }
        connection.close();
    }
}
