package vn.edu.p2p.tracker.auth;

import vn.edu.p2p.common.dto.LoginRequest;
import vn.edu.p2p.common.dto.LoginResponse;
import vn.edu.p2p.common.dto.LogoutRequest;
import vn.edu.p2p.common.dto.RegisterRequest;
import vn.edu.p2p.common.dto.RegisterResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request, String remoteIp) throws AuthException;

    void logout(LogoutRequest request) throws AuthException;

    default RegisterResponse register(RegisterRequest request) throws AuthException {
        throw new AuthException("FEATURE_UNAVAILABLE", "Registration is not configured.");
    }
}
