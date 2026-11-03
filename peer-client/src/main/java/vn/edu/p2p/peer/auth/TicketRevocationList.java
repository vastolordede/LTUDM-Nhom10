package vn.edu.p2p.peer.auth;

import vn.edu.p2p.common.dto.HeartbeatResponse;
import vn.edu.p2p.common.dto.TicketRevocation;
import vn.edu.p2p.common.security.PeerAccessTickets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TicketRevocationList {
    private static final int MAX_ENTRIES = 10_000;
    private static final long RETENTION_MILLIS = Duration.ofSeconds(
            PeerAccessTickets.TTL_SECONDS + 300).toMillis();

    private final List<TicketRevocation> entries = new ArrayList<>();
    private final Clock clock;
    private long latestSequence;
    private String trackerInstanceId;

    public TicketRevocationList() {
        this(Clock.systemUTC());
    }

    public TicketRevocationList(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized long latestSequence() {
        pruneExpired();
        return latestSequence;
    }

    public synchronized void apply(HeartbeatResponse response) {
        Objects.requireNonNull(response, "response");
        pruneExpired();
        Long responseSequence = response.latestRevocationSeq();
        String responseInstanceId = response.trackerInstanceId();
        if (responseInstanceId != null) {
            if (trackerInstanceId != null && !trackerInstanceId.equals(responseInstanceId)) {
                entries.clear();
                latestSequence = 0L;
            }
            trackerInstanceId = responseInstanceId;
        }
        if (responseSequence != null && responseSequence < latestSequence) {
            entries.clear();
            latestSequence = 0L;
        }

        for (TicketRevocation revocation : response.revocations()) {
            if (revocation != null && revocation.sequence() > latestSequence) {
                entries.add(revocation);
                latestSequence = revocation.sequence();
                if (entries.size() > MAX_ENTRIES) entries.remove(0);
            }
        }

        if (response.revocations().isEmpty() && responseSequence != null
                && responseSequence >= latestSequence) {
            latestSequence = responseSequence;
        }
    }

    public synchronized String trackerInstanceId() {
        return trackerInstanceId;
    }

    public synchronized boolean isRevoked(PeerAccessTickets.TicketClaims claims,
                                          String uploaderPeerId, long fileId) {
        pruneExpired();
        if (claims == null) return true;
        long issuedAtMillis;
        try {
            issuedAtMillis = Math.multiplyExact(claims.issuedAtEpochSecond(), 1000L);
        } catch (ArithmeticException overflow) {
            return true;
        }
        for (TicketRevocation revocation : entries) {
            if (revocation.type() == TicketRevocation.Type.REQUESTER
                    && claims.requesterId().equals(revocation.requesterPeerId())
                    && issuedAtMillis <= revocation.revokedBeforeEpochMillis()) {
                return true;
            }
            if (revocation.type() == TicketRevocation.Type.FILE
                    && uploaderPeerId.equals(revocation.uploaderPeerId())
                    && fileId == revocation.fileId()
                    && issuedAtMillis <= revocation.revokedBeforeEpochMillis()) {
                return true;
            }
        }
        return false;
    }

    private void pruneExpired() {
        long cutoff = clock.millis() - RETENTION_MILLIS;
        entries.removeIf(entry -> entry.revokedBeforeEpochMillis() < cutoff);
    }
}
