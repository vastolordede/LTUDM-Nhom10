package vn.edu.p2p.tracker.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.protocol.MessageEnvelope;
import vn.edu.p2p.common.protocol.MessageType;
import vn.edu.p2p.common.protocol.ProtocolCodec;
import vn.edu.p2p.tracker.auth.AuthService;
import vn.edu.p2p.tracker.peer.PeerSessionService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 2, unit = TimeUnit.SECONDS)
class TrackerRequestLogPrivacyTest {
    @Test
    void unexpectedLoginFailureDoesNotLogSecretsOrThrowable() {
        String secret = "password-marker session-token-marker ticket-marker proof-marker C:\\keys\\private";
        AuthService auth = new AuthService() {
            @Override
            public vn.edu.p2p.common.dto.LoginResponse login(LoginRequest request, String remoteIp) {
                throw new IllegalStateException(secret);
            }

            @Override
            public void logout(vn.edu.p2p.common.dto.LogoutRequest request) {}
        };
        TrackerRequestDispatcher dispatcher = new TrackerRequestDispatcher(auth,
                new PeerSessionService(null));
        LoginRequest login = new LoginRequest("user", "password-marker", 7001,
                UUID.randomUUID().toString(), "device", "public-key");
        MessageEnvelope request = MessageEnvelope.request(MessageType.LOGIN_REQUEST,
                UUID.randomUUID().toString(), ProtocolCodec.toPayload(login));
        Logger logger = Logger.getLogger(TrackerRequestDispatcher.class.getName());
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
        try {
            MessageEnvelope response = dispatcher.dispatch(request, "127.0.0.1");
            assertEquals("SERVER_ERROR", response.errorCode());
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousParentHandlers);
            logger.setLevel(previousLevel);
        }

        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains("IllegalStateException")));
        assertFalse(records.stream().anyMatch(record -> record.getMessage().contains(secret)));
        assertFalse(records.stream().anyMatch(record -> record.getMessage().contains("password-marker")));
        assertTrue(records.stream().allMatch(record -> record.getThrown() == null));
    }
}
