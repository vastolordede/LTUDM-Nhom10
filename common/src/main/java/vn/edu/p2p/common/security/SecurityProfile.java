package vn.edu.p2p.common.security;

import java.util.Locale;

/** Fail-closed profile checks; never automatically downgrade production to plain TCP. */
public final class SecurityProfile {
    private SecurityProfile() { }

    public enum Mode { DEVELOPMENT, LAN, PRODUCTION }

    public static Mode parse(String value) {
        if (value == null || value.isBlank()) return Mode.DEVELOPMENT;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "dev", "development" -> Mode.DEVELOPMENT;
            case "lan" -> Mode.LAN;
            case "prod", "production" -> Mode.PRODUCTION;
            default -> throw new IllegalStateException("Invalid P2P_PROFILE: " + value
                    + ". Expected development, lan, or production");
        };
    }

    public static void requireTracker(Mode mode, boolean trackerTls,
                                      boolean insecureLoopbackDemo) {
        if (mode != Mode.DEVELOPMENT && insecureLoopbackDemo) {
            throw new IllegalStateException("P2P_INSECURE_LOOPBACK_DEMO is forbidden in LAN/PRODUCTION profiles");
        }
        if (mode == Mode.PRODUCTION && !trackerTls) {
            throw new IllegalStateException("PRODUCTION requires TRACKER_TLS_ENABLED=true");
        }
    }

    public static void requirePeer(Mode mode, boolean trackerTls, boolean p2pTls,
                                   boolean hostnameVerified, boolean insecureLoopbackDemo) {
        requireTracker(mode, trackerTls, insecureLoopbackDemo);
        if (mode == Mode.PRODUCTION && (!p2pTls || !hostnameVerified)) {
            throw new IllegalStateException("PRODUCTION requires P2P_TLS_ENABLED=true and "
                    + "P2P_TLS_VERIFY_HOSTNAME=true with matching peer certificate SANs");
        }
    }
}
