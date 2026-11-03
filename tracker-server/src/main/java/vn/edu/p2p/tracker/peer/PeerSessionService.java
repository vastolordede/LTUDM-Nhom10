package vn.edu.p2p.tracker.peer;

import vn.edu.p2p.common.dto.HeartbeatRequest;
import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.tracker.domain.PeerSessionRecord;
import vn.edu.p2p.tracker.domain.PeerStatus;
import vn.edu.p2p.tracker.repository.PeerSessionRepository;
import vn.edu.p2p.tracker.security.TicketRevocationRegistry;

import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public final class PeerSessionService {
    private final PeerSessionRepository sessionRepository;
    private final Clock clock;
    private final TicketRevocationRegistry revocationRegistry;
    private final String trackerInstanceId;

    public PeerSessionService(PeerSessionRepository sessionRepository) {
        this(sessionRepository, Clock.systemUTC(), new TicketRevocationRegistry(),
                UUID.randomUUID().toString());
    }

    PeerSessionService(PeerSessionRepository sessionRepository, Clock clock) {
        this(sessionRepository, clock, new TicketRevocationRegistry(clock),
                UUID.randomUUID().toString());
    }

    public PeerSessionService(PeerSessionRepository sessionRepository,
                              TicketRevocationRegistry revocationRegistry) {
        this(sessionRepository, Clock.systemUTC(), revocationRegistry,
                UUID.randomUUID().toString());
    }

    PeerSessionService(PeerSessionRepository sessionRepository, Clock clock,
                       TicketRevocationRegistry revocationRegistry) {
        this(sessionRepository, clock, revocationRegistry, UUID.randomUUID().toString());
    }

    public PeerSessionService(PeerSessionRepository sessionRepository,
                              TicketRevocationRegistry revocationRegistry,
                              String trackerInstanceId) {
        this(sessionRepository, Clock.systemUTC(), revocationRegistry, trackerInstanceId);
    }

    PeerSessionService(PeerSessionRepository sessionRepository, Clock clock,
                       TicketRevocationRegistry revocationRegistry, String trackerInstanceId) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
        this.revocationRegistry = revocationRegistry;
        this.trackerInstanceId = java.util.Objects.requireNonNull(
                trackerInstanceId, "trackerInstanceId");
    }

    public HeartbeatResponse heartbeat(HeartbeatRequest request) throws SessionException {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new SessionException("INVALID_SESSION", "Missing sessionId.");
        }

        UUID sessionId = parseSessionId(request.sessionId());
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        try {
            PeerSessionRecord session = requireOnlineSession(sessionId);
            if (!sessionRepository.updateLastSeen(session.sessionId(), now)) {
                throw new SessionException(
                        "SESSION_NOT_ONLINE",
                        "Session is no longer online."
                );
            }
            long cursor = request.lastRevocationSeq();
            if (request.lastTrackerInstanceId() != null
                    && !request.lastTrackerInstanceId().equals(trackerInstanceId)) {
                cursor = 0L;
            }
            TicketRevocationRegistry.Batch revocations = revocationRegistry.snapshotAfter(cursor);
            return new HeartbeatResponse(clock.instant().toEpochMilli(),
                    revocations.revocations(), revocations.latestSequence(), trackerInstanceId);
        } catch (SessionException e) {
            throw e;
        } catch (SQLException e) {
            throw new SessionException(
                    "DATABASE_ERROR",
                    "Tracker cannot update the Peer session.",
                    e
            );
        }
    }

    public PeerSessionRecord requireOnlineSession(String rawSessionId)
            throws SessionException {
        if (rawSessionId == null || rawSessionId.isBlank()) {
            throw new SessionException("INVALID_SESSION", "Missing sessionId.");
        }

        try {
            return requireOnlineSession(parseSessionId(rawSessionId));
        } catch (SessionException e) {
            throw e;
        } catch (SQLException e) {
            throw new SessionException(
                    "DATABASE_ERROR",
                    "Tracker cannot read the Peer session.",
                    e
            );
        }
    }

    private PeerSessionRecord requireOnlineSession(UUID sessionId)
            throws SessionException, SQLException {
        PeerSessionRecord session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new SessionException(
                        "INVALID_SESSION",
                        "Session does not exist."
                ));

        if (session.status() != PeerStatus.ONLINE) {
            throw new SessionException(
                    "SESSION_NOT_ONLINE",
                    "Session is not online."
            );
        }
        return session;
    }

    private static UUID parseSessionId(String raw) throws SessionException {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new SessionException(
                    "INVALID_SESSION",
                    "sessionId must be a valid UUID.",
                    e
            );
        }
    }
}
