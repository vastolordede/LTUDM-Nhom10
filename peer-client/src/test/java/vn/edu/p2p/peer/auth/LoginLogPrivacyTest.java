package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.peer.network.TrackerConnectionConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoginLogPrivacyTest {
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @Test
    void loginFailureLogKeepsClassificationButNotSensitiveExceptionDetails() {
        Logger logger = Logger.getLogger(LoginController.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Level previousLevel = logger.getLevel();
        boolean previousParentHandlers = logger.getUseParentHandlers();
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        String secret = "password-marker session-token-marker ticket-marker proof-marker C:\\keys\\private";
        try {
            LoginController.logAuthFailure("login", new IOException(secret),
                    new TrackerConnectionConfig("tracker", 443, true,
                            Path.of("C:\\private\\truststore.p12"), "trust-password", "PKCS12"));
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousParentHandlers);
            logger.setLevel(previousLevel);
        }

        assertFalse(records.isEmpty());
        LogRecord record = records.getFirst();
        assertFalse(record.getMessage().contains(secret));
        assertFalse(record.getMessage().contains("truststore"));
        assertNull(record.getThrown());
    }
}
