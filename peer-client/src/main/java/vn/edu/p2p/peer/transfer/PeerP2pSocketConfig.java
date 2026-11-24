package vn.edu.p2p.peer.transfer;
import vn.edu.p2p.common.config.LocalEnv;
import vn.edu.p2p.common.security.SecurityProfile;

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

        /** Production automatically uses TLS; only opted-in isolated tests may use plain TCP. */
        public static PeerP2pSocketConfig fromEnvironment() {
        LocalEnv dotenv = LocalEnv.load();
        String enabled = read("P2P_TLS_ENABLED", dotenv);
        SecurityProfile.Mode mode = SecurityProfile.configuredMode();
        boolean enabledByConfig = "true".equalsIgnoreCase(enabled);
        if (!SecurityProfile.tlsRequiredByDefault(mode, enabledByConfig)) {
                return plainTcp();
        }
        if (mode == SecurityProfile.Mode.PRODUCTION) {
            // A new user needs no operator-provisioned, IP-bound Peer keystore.
            // The upload server generates its own short-lived X.509 certificate from
            // the existing PeerKeyStore Ed25519 key in TransferDashboardWindow.show().
            return new PeerP2pSocketConfig(true, null, null, "PKCS12",
                    null, null, "PKCS12", DEFAULT_CONNECT_TIMEOUT_MS,
                    DEFAULT_READ_TIMEOUT_MS);
        }
        String ks = read("P2P_KEYSTORE", dotenv);
        String ts = read("P2P_TRUSTSTORE", dotenv);
        String keyPassword = nullToEmpty(read("P2P_KEYSTORE_PASSWORD", dotenv));
        String trustPassword = nullToEmpty(read("P2P_TRUSTSTORE_PASSWORD", dotenv));
        if (keyPassword.isBlank() || trustPassword.isBlank()) {
            throw new IllegalStateException("P2P TLS needs a provisioned device keystore, "
                    + "trusted CA store and passwords. The administrator must provision "
                    + "the secure installation first; unencrypted fallback is forbidden.");
        }
        return tls(
                dotenv.resolvePath(ks == null ? "certs/peer-p2p.p12" : ks),
                keyPassword,
                dotenv.resolvePath(ts == null ? "certs/peer-p2p-truststore.p12" : ts),
                trustPassword
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