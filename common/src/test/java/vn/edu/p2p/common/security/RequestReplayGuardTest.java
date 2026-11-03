package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestReplayGuardTest {
    @Test
    void defaultRetentionCoversTheEntireProofTimestampWindow() {
        assertTrue(RequestReplayGuard.DEFAULT_RETENTION.compareTo(
                Duration.ofSeconds(2 * PeerRequestProof.DEFAULT_TIMESTAMP_WINDOW_SECONDS)) > 0);
    }

    @Test
    void rejectsDuplicateNonceButScopesItToRequester() {
        RequestReplayGuard guard = new RequestReplayGuard(
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(60), 10);

        assertTrue(guard.accept("requester-1", "nonce-1"));
        assertFalse(guard.accept("requester-1", "nonce-1"));
        assertTrue(guard.accept("requester-2", "nonce-1"));
    }

    @Test
    void prunesExpiredNoncesAndRejectsNewOnesWhenAtCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-01T00:00:00Z"));
        RequestReplayGuard guard = new RequestReplayGuard(clock, Duration.ofSeconds(10), 2);

        assertTrue(guard.accept("requester-1", "nonce-1"));
        assertTrue(guard.accept("requester-1", "nonce-2"));
        for (int i = 3; i < 103; i++) {
            assertFalse(guard.accept("requester-1", "nonce-" + i));
        }
        assertEquals(2, guard.size());

        clock.advance(Duration.ofSeconds(10));
        assertTrue(guard.accept("requester-1", "nonce-3"));
        assertEquals(1, guard.size());
    }

    @Test
    void concurrentDuplicateNonceIsAcceptedExactlyOnce() throws Exception {
        int threadCount = 8;
        RequestReplayGuard guard = new RequestReplayGuard(
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(60), 20);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return guard.accept("requester-1", "same-nonce");
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            }
            assertEquals(1, accepted);
            assertEquals(1, guard.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
