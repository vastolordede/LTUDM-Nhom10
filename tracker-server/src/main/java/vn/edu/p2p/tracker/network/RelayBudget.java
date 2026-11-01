package vn.edu.p2p.tracker.network;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, bidirectional relay stream budget; never loads a file into RAM. */
public final class RelayBudget {
    private final AtomicLong remaining;
    private final long bytesPerSecond;
    private final long deadlineNanos;
    private long nextAllowedNanos;

    public RelayBudget(long maxBytes, long bytesPerSecond, long maxSeconds) {
        if (maxBytes < 1 || bytesPerSecond < 1 || maxSeconds < 1) {
            throw new IllegalArgumentException("Relay budget values must be positive");
        }
        remaining = new AtomicLong(maxBytes);
        this.bytesPerSecond = bytesPerSecond;
        nextAllowedNanos = System.nanoTime();
        deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxSeconds);
    }

    /** Shared by both relay directions; block the copy worker instead of unbounded buffering. */
    public synchronized void take(int bytes) throws IOException {
        if (bytes <= 0 || bytes > 64 * 1024) throw new IOException("Invalid relay chunk size");
        if (remaining.addAndGet(-bytes) < 0) throw new IOException("Relay session byte budget exhausted");
        long now = System.nanoTime();
        if (now - deadlineNanos >= 0) throw new IOException("Relay session time limit exceeded");
        long delay = Math.max(0L, nextAllowedNanos - now);
        long duration = (long) Math.ceil(bytes * 1_000_000_000.0 / bytesPerSecond);
        if (delay > TimeUnit.SECONDS.toNanos(30)) throw new IOException("Relay throttle stalled");
        if (delay > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Relay throttled copy interrupted", e);
            }
        }
        now = System.nanoTime();
        if (now - deadlineNanos >= 0) throw new IOException("Relay session expired");
        nextAllowedNanos = Math.max(now, nextAllowedNanos) + duration;
    }
    public long remainingBytes() { return Math.max(0, remaining.get()); }
}
