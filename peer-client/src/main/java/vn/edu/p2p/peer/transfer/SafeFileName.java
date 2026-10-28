package vn.edu.p2p.peer.transfer;

import java.util.Locale;
import java.util.Set;

public final class SafeFileName {
    private static final String FORBIDDEN = "<>:\"/\\|?*";
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SafeFileName() {}

    /** Trả về thông báo lỗi, hoặc null nếu tên hợp lệ. */
    public static String validate(String name) {
        if (name == null || name.isBlank()) return "Ten file khong duoc de trong.";
        if (name.length() > 255) return "Ten file qua dai (toi da 255 ky tu).";
        if (name.equals(".") || name.equals("..")) return "Ten file khong hop le.";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || FORBIDDEN.indexOf(c) >= 0) {
                return "Ten file chua ky tu khong hop le.";
            }
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            return "Ten file khong duoc ket thuc bang dau cham hoac khoang trang.";
        }
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).trim().toUpperCase(Locale.ROOT);
        if (RESERVED.contains(stem)) return "Ten file trung ten thiet bi he thong cua Windows.";
        return null;
    }

    /** Biến tên từ Tracker thành tên an toàn để gợi ý mặc định trong ô nhập. */
    public static String sanitize(String name) {
        String s = name == null ? "" : name;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append((c < 32 || FORBIDDEN.indexOf(c) >= 0) ? '_' : c);
        }
        String r = sb.toString().replaceAll("[. ]+$", "");
        if (r.isEmpty() || r.equals("..")) r = "download.bin";
        if (validate(r) != null) r = "_" + r;
        return r.length() > 255 ? r.substring(0, 255) : r;
    }
}