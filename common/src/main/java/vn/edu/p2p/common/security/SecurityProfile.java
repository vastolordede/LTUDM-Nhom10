package vn.edu.p2p.common.security;

import java.util.Locale;
import vn.edu.p2p.common.config.EnvConfig;

/** Fail-closed profile checks; never automatically downgrade production to plain TCP. */
public final class SecurityProfile {
    private SecurityProfile() { }

    public enum Mode { DEVELOPMENT, LAN, PRODUCTION }

    public static Mode parse(String value) {
        if (value == null || value.isBlank()) return Mode.PRODUCTION;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "dev", "development" -> Mode.DEVELOPMENT;
            case "lan" -> Mode.LAN;
            case "prod", "production" -> Mode.PRODUCTION;
            default -> throw new IllegalStateException("Invalid P2P_PROFILE: " + value
                    + ". Expected development, lan, or production");
        };
    }

    /**
     * The shipped application is secure by default even with an old .env containing
     * P2P_PROFILE=development. Insecure modes require a separate, deliberate opt-in
     * and must never be used in a distributed client package.
     */
    public static Mode configuredMode() {
        Mode requested = parse(EnvConfig.get("P2P_PROFILE", "production"));
        if (requested != Mode.PRODUCTION && !EnvConfig.flag("P2P_ALLOW_INSECURE_DEV", false)) {
            throw new IllegalStateException("Insecure P2P_PROFILE requires explicit "
                    + "P2P_ALLOW_INSECURE_DEV=true for isolated developer testing");
        }
        return requested;
    }

    /** Disregard legacy false toggles in the production client/server profile. */
    public static boolean tlsRequiredByDefault(Mode mode, boolean configuredValue) {
        return mode == Mode.PRODUCTION || configuredValue;
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
