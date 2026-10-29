package vn.edu.p2p.common.config;

/** Đọc cấu hình: biến môi trường OS > .env > mặc định. */
public final class EnvConfig {
    private EnvConfig() {}

    public static String get(String key, String defaultValue) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            v = LocalEnv.load().get(key);
        }
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }

    public static boolean flag(String key, boolean defaultValue) {
        return "true".equalsIgnoreCase(get(key, Boolean.toString(defaultValue)));
    }

    public static int integer(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, Integer.toString(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}