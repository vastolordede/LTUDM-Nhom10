package vn.edu.p2p.tracker.auth;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptLimiterTest {
    @Test
    void fiveFailuresBlockOnlyIpForTwoMinutes() {
        AtomicLong now = new AtomicLong(1_000);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 60_000, 120_000, now::get);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allow("192.0.2.1"));
            limiter.recordFailed("192.0.2.1");
        }
        assertFalse(limiter.allow("192.0.2.1"));
        assertTrue(limiter.allow("192.0.2.2"));
        now.addAndGet(120_001);
        assertTrue(limiter.allow("192.0.2.1"));
        limiter.recordFailed("192.0.2.1");
        assertTrue(limiter.allow("192.0.2.1"));
    }

    @Test
    void windowExpiresAndSuccessfulLoginClearsFailures() {
        AtomicLong now = new AtomicLong(1_000);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 60_000, 120_000, now::get);
        limiter.recordFailed("client");
        now.addAndGet(60_001);
        assertTrue(limiter.allow("client"));
        limiter.recordFailed("client");
        limiter.recordSuccess("client");
        limiter.recordFailed("client");
        limiter.recordFailed("client");
        assertTrue(limiter.allow("client"));
        limiter.recordFailed("client");
        assertFalse(limiter.allow("client"));
    }
}
