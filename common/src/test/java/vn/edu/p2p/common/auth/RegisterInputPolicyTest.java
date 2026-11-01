package vn.edu.p2p.common.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisterInputPolicyTest {
    @Test void acceptsNormalUser() {
        assertNull(RegisterInputPolicy.validate("test_01", "strongPwd123"));
    }

    @Test void rejectsMissingWeakOrTruncatedPassword() {
        assertNotNull(RegisterInputPolicy.validate("", "12345678"));
        assertNotNull(RegisterInputPolicy.validate("test_user", "abc"));
        assertNotNull(RegisterInputPolicy.validate("test_user", "a".repeat(73)));
        assertNotNull(RegisterInputPolicy.validate("a/b", "strongPwd123"));
    }
}
