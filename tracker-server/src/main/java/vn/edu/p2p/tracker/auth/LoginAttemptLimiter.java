package vn.edu.p2p.tracker.auth;

import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** In-memory per-IP invalid-credential throttling. No username/password is logged or stored. */
public final class LoginAttemptLimiter {
    private static final int MAX_IP_STATES = 10_000;
    private final Map<String, Window> failures = new LinkedHashMap<>();
    private final int maxFailures;
    private final long intervalMillis;
    private final long lockoutMillis;
    private final LongSupplier millis;

    private record Window(int count, long started, long lockedUntil) { }

    public LoginAttemptLimiter() {
        this(5, 60_000, 120_000, System::currentTimeMillis);
    }

    public LoginAttemptLimiter(int maxFailures, long intervalMillis, long lockoutMillis,
                               LongSupplier clockMillis) {
        if (maxFailures < 1 || intervalMillis < 1 || lockoutMillis < 1) {
            throw new IllegalArgumentException("Rate limit settings must be positive");
        }
        this.maxFailures = maxFailures;
        this.intervalMillis = intervalMillis;
        this.lockoutMillis = lockoutMillis;
        this.millis = Objects.requireNonNull(clockMillis);
    }

    public synchronized boolean allow(String remoteIp) {
        Window state = failures.get(key(remoteIp));
        if (state == null) return true;
        long now = millis.getAsLong();
        if (state.lockedUntil > now) return false;
        if (state.lockedUntil > 0 || now - state.started >= intervalMillis) {
            failures.remove(key(remoteIp));
        }
        return true;
    }

    public synchronized void recordFailed(String remoteIp) {
        long now = millis.getAsLong();
        String key = key(remoteIp);
        Window state = failures.get(key);
        if (state == null || state.lockedUntil > 0
                || now - state.started >= intervalMillis) {
            state = new Window(0, now, 0);
        }
        int count = state.count + 1;
        failures.put(key, new Window(count, state.started,
                count >= maxFailures ? now + lockoutMillis : 0));
        if (failures.size() >= MAX_IP_STATES) prune(now);
    }

    public synchronized void recordSuccess(String remoteIp) {
        failures.remove(key(remoteIp));
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Window>> iter = failures.entrySet().iterator();
        while (iter.hasNext()) {
            Window state = iter.next().getValue();
            if (state.lockedUntil > 0 && state.lockedUntil <= now ||
                    state.lockedUntil == 0 && now - state.started >= intervalMillis) iter.remove();
        }
        // Bound memory even under many distinct source IPs; never drop active lockouts first.
        if (failures.size() > MAX_IP_STATES) {
            iter = failures.entrySet().iterator();
            while (failures.size() > MAX_IP_STATES && iter.hasNext()) {
                if (iter.next().getValue().lockedUntil == 0) iter.remove();
            }
            // Hard memory cap: when every entry is locked, evict oldest entries.
            iter = failures.entrySet().iterator();
            while (failures.size() > MAX_IP_STATES && iter.hasNext()) {
                iter.next();
                iter.remove();
            }
        }
    }

    private static String key(String remoteIp) {
        return remoteIp == null || remoteIp.isBlank() ? "unknown" : remoteIp;
    }
}
