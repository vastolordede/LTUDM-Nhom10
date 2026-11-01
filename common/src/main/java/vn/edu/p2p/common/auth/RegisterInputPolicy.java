package vn.edu.p2p.common.auth;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Validation shared by the GUI and Tracker; never log the raw password. */
public final class RegisterInputPolicy {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,50}");
    private RegisterInputPolicy() {}

    /** Returns a user-facing error or null for valid registration input. */
    public static String validate(String username, String password) {
        if (username == null || !USERNAME.matcher(username.trim()).matches()) {
            return "Tên đăng nhập phải dài 3–50 ký tự (chữ, số, dấu chấm, _ hoặc -).";
        }
        if (password == null || password.length() < 8) {
            return "Mật khẩu phải có ít nhất 8 ký tự.";
        }
        // BCrypt processes at most 72 UTF-8 bytes. Reject silent truncation.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return "Mật khẩu không được vượt quá 72 byte UTF-8.";
        }
        if (password.isBlank()) return "Mật khẩu không được để trống.";
        return null;
    }
}
