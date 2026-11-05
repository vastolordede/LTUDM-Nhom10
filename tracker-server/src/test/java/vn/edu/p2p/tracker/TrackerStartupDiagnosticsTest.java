package vn.edu.p2p.tracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.BindException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerStartupDiagnosticsTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void classifiesStartupFailuresWithoutPrintingExceptionDetails() {
        List<Throwable> failures = List.of(
                new SQLException("Schema database not verified at C:\\private\\db; password=secret"),
                new SQLException("connection refused at private-host password=secret"),
                new IllegalStateException("Database password is empty: secret"),
                new IllegalStateException("Tracker Ed25519 signing key missing at C:\\private\\key"),
                new IllegalStateException("Tracker signing key does not match configured public key"),
                new IllegalStateException("Cannot initialize Tracker TLS context",
                        new IllegalArgumentException("bad password at C:\\private\\store")),
                new IllegalArgumentException("TLS keystore password is required: secret"),
                new BindException("C:\\private\\endpoint")
        );

        for (Throwable failure : failures) {
            String message = TrackerApplication.startupFailureMessage(failure);
            assertTrue(message.startsWith("Tracker không khởi động:"), message);
            assertFalse(message.contains("secret"), message);
            assertFalse(message.contains("C:\\private\\"), message);
        }

        assertTrue(TrackerApplication.startupFailureMessage(failures.get(0)).contains("schema DB"));
        assertTrue(TrackerApplication.startupFailureMessage(failures.get(1)).contains("PostgreSQL"));
        assertTrue(TrackerApplication.startupFailureMessage(failures.get(3)).contains("ticket"));
        assertTrue(TrackerApplication.startupFailureMessage(failures.get(5)).contains("TLS"));
        assertTrue(TrackerApplication.startupFailureMessage(failures.get(7)).contains("cổng"));
    }
}
