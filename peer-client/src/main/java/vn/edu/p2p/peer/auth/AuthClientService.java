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
import vn.edu.p2p.peer.network.TrackerConnection;
import vn.edu.p2p.peer.session.ClientSession;

import java.io.IOException;
import java.util.UUID;

public final class AuthClientService implements AutoCloseable {
    private final TrackerConnection connection;
    private final PeerIdentity peerIdentity;
    private final PeerIdentityStore identityStore;
    private ClientSession currentSession;

    public AuthClientService(
            TrackerConnection connection,
            PeerIdentity peerIdentity
    ) {
        this.connection = connection;
        this.peerIdentity = peerIdentity;
        this.identityStore = null;
    }

    /** Desktop account switching: resolve identity per user, preserving legacy peer.id. */
    public AuthClientService(TrackerConnection connection, PeerIdentityStore identityStore,
                             PeerIdentity initialIdentity) {
        this.connection = connection;
        this.identityStore = identityStore;
        this.peerIdentity = initialIdentity;
    }

    public synchronized ClientSession login(
            String username,
            String password,
            int listeningPort
    ) throws AuthClientException {
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

            currentSession = new ClientSession(
                    loginResponse.peerId(),
                    loginResponse.sessionId(),
                    loginResponse.heartbeatIntervalSeconds()
            );
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
        LoginRequest payload = new LoginRequest(username, password, listeningPort,
                identity.peerId(), identity.deviceName());
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

    public synchronized void logout() throws AuthClientException {
        if (currentSession == null) {
            return;
        }

        MessageEnvelope request = MessageEnvelope.request(
                MessageType.LOGOUT_REQUEST,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(
                        new LogoutRequest(currentSession.sessionId())
                )
        );

        MessageEnvelope response = send(request);
        requireType(response, MessageType.LOGOUT_RESPONSE);
        requireSuccess(response);
        currentSession = null;
    }

    public synchronized HeartbeatResponse heartbeat() throws AuthClientException {
        if (currentSession == null) {
            throw new AuthClientException(
                    "INVALID_SESSION",
                    "Peer is not logged in."
            );
        }

        MessageEnvelope request = MessageEnvelope.request(
                MessageType.HEARTBEAT,
                UUID.randomUUID().toString(),
                ProtocolCodec.toPayload(
                        new HeartbeatRequest(currentSession.sessionId())
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
            return heartbeatResponse;
        } catch (IOException e) {
            throw new AuthClientException(
                    "INVALID_RESPONSE",
                    "Cannot decode Tracker heartbeat response.",
                    e
            );
        }
    }

    public synchronized ClientSession currentSession() {
        return currentSession;
    }

    public synchronized void invalidateLocalSession() {
        currentSession = null;
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
    public synchronized void closeGracefully() {
        try {
            if (currentSession != null) {
                logout();
            }
        } catch (AuthClientException e) {
            System.err.println(
                    "Cannot notify Tracker about logout during shutdown: "
                            + e.getMessage()
            );
        } finally {
            close();
        }
    }

    @Override
    public synchronized void close() {
        connection.close();
        currentSession = null;
    }
}
