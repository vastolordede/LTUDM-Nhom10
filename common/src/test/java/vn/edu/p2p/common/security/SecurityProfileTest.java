package vn.edu.p2p.common.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityProfileTest {
    @Test void invalidProfileFailsClosed() {
        assertThrows(IllegalStateException.class, () -> SecurityProfile.parse("anything"));
    }
    @Test void productionTrackerMustUseTls() {
        assertThrows(IllegalStateException.class, () ->
                SecurityProfile.requireTracker(SecurityProfile.Mode.PRODUCTION, false, false));
        assertDoesNotThrow(() -> SecurityProfile.requireTracker(SecurityProfile.Mode.PRODUCTION, true, false));
    }
    @Test void insecureDemoForbiddenOnLanAndProduction() {
        assertThrows(IllegalStateException.class, () ->
                SecurityProfile.requireTracker(SecurityProfile.Mode.LAN, false, true));
        assertThrows(IllegalStateException.class, () ->
                SecurityProfile.requireTracker(SecurityProfile.Mode.PRODUCTION, true, true));
    }
    @Test void productionPeerMustVerifyTlsHostname() {
        assertThrows(IllegalStateException.class, () ->
                SecurityProfile.requirePeer(SecurityProfile.Mode.PRODUCTION, true, true, false, false));
        assertThrows(IllegalStateException.class, () ->
                SecurityProfile.requirePeer(SecurityProfile.Mode.PRODUCTION, true, false, true, false));
        assertDoesNotThrow(() ->
                SecurityProfile.requirePeer(SecurityProfile.Mode.PRODUCTION, true, true, true, false));
    }
}
