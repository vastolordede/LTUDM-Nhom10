package vn.edu.p2p.peer.transfer;
import vn.edu.p2p.common.config.LocalEnv;

import java.nio.file.Path;

/**
 * S07-S08: Cấu hình kết nối trực tiếp Peer <-> Peer (hỗ trợ TCP và TLS).
 */
public record PeerP2pSocketConfig(
        boolean tlsEnabled,
        Path keyStorePath,
        String keyStorePassword,
        String keyStoreType,
        Path trustStorePath,
        String trustStorePassword,
        String trustStoreType,
        int connectTimeoutMillis,
        int readTimeoutMillis
) {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    public static PeerP2pSocketConfig plainTcp() {
        return new PeerP2pSocketConfig(
                false, null, null, "PKCS12",
                null, null, "PKCS12",
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS
        );
    }

    public static PeerP2pSocketConfig tls(
            Path keyStorePath,
            String keyStorePassword,
            Path trustStorePath,
            String trustStorePassword
    ) {
        return new PeerP2pSocketConfig(
                true,
                keyStorePath,
                keyStorePassword,
                "PKCS12",
                trustStorePath,
                trustStorePassword,
                "PKCS12",
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS
        );
    }

        /** Đọc cấu hình P2P TLS từ OS env > .env. Mặc định tắt (plain TCP). */
        public static PeerP2pSocketConfig fromEnvironment() {
        LocalEnv dotenv = LocalEnv.load();
        String enabled = read("P2P_TLS_ENABLED", dotenv);
        if (!"true".equalsIgnoreCase(enabled)) {
                return plainTcp();
        }
        String ks = read("P2P_KEYSTORE", dotenv);
        String ts = read("P2P_TRUSTSTORE", dotenv);
        return tls(
                dotenv.resolvePath(ks == null ? "certs/peer-p2p.p12" : ks),
                nullToEmpty(read("P2P_KEYSTORE_PASSWORD", dotenv)),
                dotenv.resolvePath(ts == null ? "certs/peer-p2p-truststore.p12" : ts),
                nullToEmpty(read("P2P_TRUSTSTORE_PASSWORD", dotenv))
        );
        }

        private static String read(String key, LocalEnv dotenv) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) v = dotenv.get(key);
        return (v == null || v.isBlank()) ? null : v.trim();
        }

        private static String nullToEmpty(String s) {
        return s == null ? "" : s;
        }
}