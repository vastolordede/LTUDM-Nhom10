package vn.edu.p2p.tracker.security;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.TicketRevocation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketRevocationRegistryTest {
    @Test
    void createsSequencedRequesterAndFileEntriesAndReturnsOnlyLaterSequences() {
        TicketRevocationRegistry registry = new TicketRevocationRegistry(
                Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC));

        TicketRevocation requester = registry.revokeRequester("peer-1");
        TicketRevocation file = registry.revokeFile("peer-2", 99L);

        assertEquals(1L, requester.sequence());
        assertEquals(TicketRevocation.Type.REQUESTER, requester.type());
        assertEquals(2L, file.sequence());
        assertEquals(TicketRevocation.Type.FILE, file.type());
        assertEquals(List.of(file), registry.entriesAfter(1L));
        assertEquals(List.of(requester, file), registry.entriesAfter(0L));
    }

    @Test
    void expiresEntriesAfterRetentionAndCapsStorage() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-03T00:00:00Z"));
        TicketRevocationRegistry registry = new TicketRevocationRegistry(clock);
        registry.revokeRequester("peer-expire");

        clock.advance(TicketRevocationRegistry.RETENTION.plusMillis(1));
        assertEquals(0, registry.size());

        for (int i = 0; i <= TicketRevocationRegistry.MAX_ENTRIES; i++) {
            registry.revokeRequester("peer-" + i);
        }
        assertEquals(TicketRevocationRegistry.MAX_ENTRIES, registry.size());
        assertEquals(TicketRevocationRegistry.MAX_BATCH_SIZE,
                registry.entriesAfter(0).size());
        assertEquals(TicketRevocationRegistry.MAX_ENTRIES + 2L, registry.latestSequence());
    }

    @Test
    void supportsConcurrentAppendsWithUniqueSequences() throws Exception {
        TicketRevocationRegistry registry = new TicketRevocationRegistry();
        int threadCount = 8;
        int perThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < threadCount; thread++) {
                int id = thread;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        registry.revokeRequester("peer-" + id + "-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(threadCount * perThread, registry.size());
        assertEquals(threadCount * perThread, registry.latestSequence());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
