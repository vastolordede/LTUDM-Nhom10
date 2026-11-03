package vn.edu.p2p.tracker.auth;

import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.dto.RegisterRequest;
import vn.edu.p2p.common.dto.RegisterResponse;
import vn.edu.p2p.common.auth.RegisterInputPolicy;
import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.tracker.domain.PeerRecord;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.domain.UserRecord;
import vn.edu.p2p.tracker.repository.PeerRepository;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;
import vn.edu.p2p.tracker.repository.UserRepository;
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;

import java.sql.SQLException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class DefaultAuthService implements AuthService {
    private static final Logger LOG = Logger.getLogger(DefaultAuthService.class.getName());

    private final UserRepository userRepository;
    private final PeerRepository peerRepository;
    private final PeerSessionRepository sessionRepository;
    private final PasswordService passwordService;
    private final int heartbeatIntervalSeconds;
    private final TicketRevocationRegistry revocationRegistry;
    private final Clock clock;

    public DefaultAuthService(
            UserRepository userRepository,
            PeerRepository peerRepository,
            PeerSessionRepository sessionRepository,
            PasswordService passwordService,
            int heartbeatIntervalSeconds
    ) {
        this(userRepository, peerRepository, sessionRepository, passwordService,
                heartbeatIntervalSeconds, new TicketRevocationRegistry(), Clock.systemUTC());
    }

    public DefaultAuthService(
            UserRepository userRepository,
            PeerRepository peerRepository,
            PeerSessionRepository sessionRepository,
            PasswordService passwordService,
            int heartbeatIntervalSeconds,
            TicketRevocationRegistry revocationRegistry,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.peerRepository = peerRepository;
        this.sessionRepository = sessionRepository;
        this.passwordService = passwordService;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.revocationRegistry = revocationRegistry;
        this.clock = clock;
    }

    @Override
    public LoginResponse login(LoginRequest request, String remoteIp) throws AuthException {
        validateLoginRequest(request);

        try {
            Optional<UserRecord> optionalUser = userRepository.findByUsername(
                    request.username().trim()
            );

            if (optionalUser.isEmpty()) {
                throw invalidCredentials();
            }

            UserRecord user = optionalUser.get();
            if (!user.isActive()
                    || !passwordService.matches(request.password(), user.passwordHash())) {
                throw invalidCredentials();
            }

            PublicKey peerPublicKey;
            try {
                peerPublicKey = PeerRequestProof.decodePublicKey(request.peerPublicKey());
            } catch (IllegalArgumentException e) {
                throw new AuthException("INVALID_PEER_PUBLIC_KEY",
                        "Peer public key is missing or is not a valid Ed25519 X.509 key.", e);
            }

            UUID peerId = parseUuid(request.peerId(), "INVALID_PEER_ID");
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

            ensurePeer(
                    peerId,
                    user.userId(),
                    request.deviceName(),
                    now
            );
            UUID sessionId = UUID.randomUUID();
            PeerSessionRecord newSession = new PeerSessionRecord(
                    sessionId,
                    peerId,
                    normalizeIp(remoteIp),
                    request.listeningPort(),
                    PeerStatus.ONLINE,
                    now,
                    now,
                    null
            );

            int replacedSessions = sessionRepository.replaceActiveForPeer(
                    newSession,
                    now
            );
            if (replacedSessions > 0) {
                revocationRegistry.revokeRequester(peerId.toString());
                LOG.info(() -> String.format(
                        "Peer %s reconnect: replaced %d previous ONLINE session(s)",
                        peerId,
                        replacedSessions
                ));
            }

            peerRepository.updatePublicKey(peerId, PeerRequestProof.encodePublicKey(peerPublicKey));
            String fingerprint = PeerRequestProof.fingerprint(peerPublicKey);
            LOG.info(() -> "Registered Peer public key fingerprint "
                    + fingerprint.substring(0, 12) + " for Peer " + peerId);

            return new LoginResponse(
                    peerId.toString(),
                    sessionId.toString(),
                    heartbeatIntervalSeconds
            );
        } catch (AuthException e) {
            throw e;
        } catch (SQLException e) {
            throw new AuthException(
                    "DATABASE_ERROR",
                    "Tracker cannot access the database.",
                    e
            );
        }
    }

    @Override
    public RegisterResponse register(RegisterRequest request) throws AuthException {
        String error = request == null
                ? "Missing registration payload."
                : RegisterInputPolicy.validate(request.username(), request.password());
        if (error != null) {
            throw new AuthException("INVALID_REGISTRATION", error);
        }

        String username = request.username().trim();
        try {
            if (userRepository.findByUsername(username).isPresent()) {
                throw new AuthException("USERNAME_TAKEN", "Tên đăng nhập đã tồn tại.");
            }
            // Repository's case-insensitive UNIQUE index is the final race guard.
            var created = userRepository.create(username, passwordService.hash(request.password()));
            return new RegisterResponse(created.username());
        } catch (AuthException e) {
            throw e;
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new AuthException("USERNAME_TAKEN", "Tên đăng nhập đã tồn tại.", e);
            }
            throw new AuthException("DATABASE_ERROR", "Tracker không thể tạo tài khoản.", e);
        }
    }

    @Override
    public void logout(LogoutRequest request) throws AuthException {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new AuthException("INVALID_SESSION", "Missing sessionId.");
        }

        UUID sessionId = parseUuid(request.sessionId(), "INVALID_SESSION");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        try {
            PeerSessionRecord session = sessionRepository.findBySessionId(sessionId)
                    .orElseThrow(() -> new AuthException(
                            "INVALID_SESSION", "Session is not online or does not exist."));
            if (!sessionRepository.markLoggedOut(sessionId, now)) {
                throw new AuthException(
                        "INVALID_SESSION",
                        "Session is not online or does not exist."
                );
            }
            revocationRegistry.revokeRequester(session.peerId().toString());
        } catch (AuthException e) {
            throw e;
        } catch (SQLException e) {
            throw new AuthException(
                    "DATABASE_ERROR",
                    "Tracker cannot update the session.",
                    e
            );
        }
    }

    private void ensurePeer(
            UUID peerId,
            long userId,
            String deviceName,
            OffsetDateTime now
    ) throws SQLException, AuthException {
        Optional<PeerRecord> existing = peerRepository.findById(peerId);
        if (existing.isPresent()) {
            verifyPeerOwnership(existing.get(), userId);
            peerRepository.updateLastLogin(peerId, now);
            return;
        }

        try {
            peerRepository.create(new PeerRecord(
                    peerId,
                    userId,
                    normalizeDeviceName(deviceName),
                    now,
                    now
            ));
        } catch (SQLException e) {
            // Concurrent first-login attempts can race on peers(peer_id). If another
            // request created the same Peer first, re-read and verify ownership.
            if (!"23505".equals(e.getSQLState())) {
                throw e;
            }

            PeerRecord concurrentPeer = peerRepository.findById(peerId)
                    .orElseThrow(() -> e);
            verifyPeerOwnership(concurrentPeer, userId);
            peerRepository.updateLastLogin(peerId, now);
        }
    }

    private static void verifyPeerOwnership(PeerRecord peer, long userId)
            throws AuthException {
        if (peer.userId() != userId) {
            throw new AuthException(
                    "PEER_OWNERSHIP_MISMATCH",
                    "Peer identity belongs to another account."
            );
        }
    }

    private static void validateLoginRequest(LoginRequest request) throws AuthException {
        if (request == null) {
            throw new AuthException("INVALID_REQUEST", "Missing login payload.");
        }
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw invalidCredentials();
        }
        if (request.listeningPort() < 1 || request.listeningPort() > 65535) {
            throw new AuthException("INVALID_PORT", "Invalid Peer listening port.");
        }
        if (request.peerId() == null || request.peerId().isBlank()) {
            throw new AuthException("INVALID_PEER_ID", "Missing Peer identity.");
        }
        if (request.peerPublicKey() == null || request.peerPublicKey().isBlank()) {
            throw new AuthException("INVALID_PEER_PUBLIC_KEY",
                    "Peer public key is required; restart the Peer application.");
        }
    }

    private static AuthException invalidCredentials() {
        return new AuthException(
                "INVALID_CREDENTIALS",
                "Invalid username or password."
        );
    }

    private static UUID parseUuid(String raw, String errorCode) throws AuthException {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new AuthException(errorCode, "Invalid UUID value.", e);
        }
    }

    private static String normalizeDeviceName(String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return "Unknown device";
        }
        String trimmed = deviceName.trim();
        return trimmed.length() <= 100 ? trimmed : trimmed.substring(0, 100);
    }

    private static String normalizeIp(String remoteIp) {
        if (remoteIp == null || remoteIp.isBlank()) {
            return "unknown";
        }
        return remoteIp.length() <= 45 ? remoteIp : remoteIp.substring(0, 45);
    }
}
