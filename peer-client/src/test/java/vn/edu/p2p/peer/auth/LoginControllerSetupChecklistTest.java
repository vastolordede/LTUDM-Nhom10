package vn.edu.p2p.peer.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginControllerSetupChecklistTest {
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void reportsTheFirstMissingSetupStepInOrder() {
        assertEquals(LoginController.SetupStep.TRACKER_CONFIGURATION,
                LoginController.nextSetupStep(false, true, false, false, true));
        assertEquals(LoginController.SetupStep.TLS_TRUSTSTORE,
                LoginController.nextSetupStep(true, true, false, false, true));
        assertEquals(LoginController.SetupStep.PUBLIC_KEY,
                LoginController.nextSetupStep(true, true, true, false, true));
        assertEquals(LoginController.SetupStep.INVALID_PUBLIC_KEY,
                LoginController.nextSetupStep(true, false, false, false, false));
        assertEquals(LoginController.SetupStep.READY,
                LoginController.nextSetupStep(true, true, true, true, true));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void guidanceIsVietnameseAndDoesNotIncludeSecretsOrPaths() {
        for (LoginController.SetupStep step : LoginController.SetupStep.values()) {
            String guidance = LoginController.setupGuidance(step);
            assertTrue(guidance.chars().anyMatch(character -> character > 127), guidance);
            assertTrue(!guidance.contains("password") && !guidance.contains("C:\\"), guidance);
        }
        assertTrue(LoginController.setupGuidance(LoginController.SetupStep.TLS_TRUSTSTORE)
                .contains("bên ngoài ứng dụng"));
    }
}
