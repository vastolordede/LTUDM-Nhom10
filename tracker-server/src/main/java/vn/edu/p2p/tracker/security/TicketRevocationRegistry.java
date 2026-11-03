package vn.edu.p2p.tracker.security;

import vn.edu.p2p.common.dto.TicketRevocation;
import vn.edu.p2p.common.security.PeerAccessTickets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public final class TicketRevocationRegistry {
    public static final Duration RETENTION = Duration.ofSeconds(
            PeerAccessTickets.TTL_SECONDS + 300);
    public static final int MAX_ENTRIES = 10_000;
    public static final int MAX_BATCH_SIZE = 200;

    private static final Logger LOG = Logger.getLogger(TicketRevocationRegistry.class.getName());

    private final Clock clock;
    private final ArrayDeque<TicketRevocation> entries = new ArrayDeque<>();
    private long latestSequence;

    public record Batch(List<TicketRevocation> revocations, long latestSequence) {}

    public TicketRevocationRegistry() {
        this(Clock.systemUTC());
    }

    public TicketRevocationRegistry(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized TicketRevocation revokeRequester(String requesterPeerId) {
        requireId(requesterPeerId, "requesterPeerId");
        return append(new TicketRevocation(nextSequence(), TicketRevocation.Type.REQUESTER,
                requesterPeerId, null, 0L, clock.millis()));
    }

    public synchronized TicketRevocation revokeFile(String uploaderPeerId, long fileId) {
        requireId(uploaderPeerId, "uploaderPeerId");
        if (fileId <= 0) throw new IllegalArgumentException("fileId must be positive");
        return append(new TicketRevocation(nextSequence(), TicketRevocation.Type.FILE,
                null, uploaderPeerId, fileId, clock.millis()));
    }

    public synchronized List<TicketRevocation> entriesAfter(long sequence) {
        return snapshotAfter(sequence).revocations();
    }

    public synchronized Batch snapshotAfter(long sequence) {
        pruneExpired();
        long after = sequence > latestSequence ? 0L : Math.max(0L, sequence);
        List<TicketRevocation> result = new ArrayList<>(Math.min(entries.size(), MAX_BATCH_SIZE));
        for (TicketRevocation entry : entries) {
            if (entry.sequence() > after && result.size() < MAX_BATCH_SIZE) {
                result.add(entry);
            }
        }
        return new Batch(List.copyOf(result), latestSequence);
    }

    public synchronized long latestSequence() {
        pruneExpired();
        return latestSequence;
    }

    public synchronized int size() {
        pruneExpired();
        return entries.size();
    }

    private TicketRevocation append(TicketRevocation revocation) {
        pruneExpired();
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
            LOG.warning("Ticket revocation registry reached capacity; evicted oldest entry");
        }
        entries.addLast(revocation);
        return revocation;
    }

    private long nextSequence() {
        if (latestSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Ticket revocation sequence exhausted");
        }
        return ++latestSequence;
    }

    private void pruneExpired() {
        long cutoff = clock.millis() - RETENTION.toMillis();
        while (!entries.isEmpty()
                && entries.peekFirst().revokedBeforeEpochMillis() < cutoff) {
            entries.removeFirst();
        }
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 80
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }
}
