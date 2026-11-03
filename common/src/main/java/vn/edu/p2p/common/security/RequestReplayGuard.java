package vn.edu.p2p.common.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class RequestReplayGuard {
    public static final Duration DEFAULT_RETENTION = Duration.ofSeconds(
            2 * PeerRequestProof.DEFAULT_TIMESTAMP_WINDOW_SECONDS + 1);
    public static final int DEFAULT_MAX_ENTRIES = 100_000;

    private record ReplayKey(String requesterId, String nonce) {}

    private final Clock clock;
    private final Duration retention;
    private final int maxEntries;
    private final Map<ReplayKey, Instant> expiresAt = new HashMap<>();

    public RequestReplayGuard() {
        this(Clock.systemUTC(), DEFAULT_RETENTION, DEFAULT_MAX_ENTRIES);
    }

    public RequestReplayGuard(Clock clock, Duration retention, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero() || maxEntries <= 0) {
            throw new IllegalArgumentException("Retention and maximum entries must be positive");
        }
        this.maxEntries = maxEntries;
    }

    public synchronized boolean accept(String requesterId, String nonce) {
        if (!validIdentifier(requesterId) || !validIdentifier(nonce)) return false;
        Instant now = clock.instant();
        removeExpired(now);
        ReplayKey key = new ReplayKey(requesterId, nonce);
        if (expiresAt.containsKey(key) || expiresAt.size() >= maxEntries) return false;
        expiresAt.put(key, now.plus(retention));
        return true;
    }

    public synchronized int size() {
        removeExpired(clock.instant());
        return expiresAt.size();
    }

    private void removeExpired(Instant now) {
        expiresAt.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private static boolean validIdentifier(String value) {
        return value != null && !value.isEmpty() && value.length() <= 128
                && value.matches("[A-Za-z0-9_-]+");
    }
}
