package vn.edu.p2p.tracker.auth;

import org.junit.jupiter.api.Test;
import vn.edu.p2p.common.dto.RegisterRequest;
import vn.edu.p2p.tracker.domain.AccountStatus;
import vn.edu.p2p.tracker.domain.UserRecord;
import vn.edu.p2p.tracker.repository.UserRepository;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationTest {
    @Test void createsHashedUserAndRejectsCaseInsensitiveDuplicate() throws Exception {
        InMemoryUsers users = new InMemoryUsers();
        PasswordService passwords = new BCryptPasswordService(4);
        DefaultAuthService service = new DefaultAuthService(users, null, null, passwords, 15);
        assertEquals("Sample_1", service.register(new RegisterRequest(" Sample_1 ", "secret123")).username());
        assertTrue(passwords.matches("secret123", users.findByUsername("sample_1").orElseThrow().passwordHash()));
        assertNotEquals("secret123", users.findByUsername("sample_1").orElseThrow().passwordHash());
        AuthException ex = assertThrows(AuthException.class,
                () -> service.register(new RegisterRequest("SAMPLE_1", "anotherPass123")));
        assertEquals("USERNAME_TAKEN", ex.errorCode());
    }

    private static final class InMemoryUsers implements UserRepository {
        private final Map<String, UserRecord> data = new HashMap<>();
        @Override public Optional<UserRecord> findByUsername(String username) {
            return Optional.ofNullable(data.get(username.toLowerCase(java.util.Locale.ROOT)));
        }
        @Override public UserRecord create(String username, String hashed) throws SQLException {
            String key = username.toLowerCase(java.util.Locale.ROOT);
            if (data.containsKey(key)) throw new SQLException("duplicate", "23505");
            UserRecord user = new UserRecord(1L, username, hashed, AccountStatus.ACTIVE, OffsetDateTime.now());
            data.put(key, user);
            return user;
        }
    }
}
