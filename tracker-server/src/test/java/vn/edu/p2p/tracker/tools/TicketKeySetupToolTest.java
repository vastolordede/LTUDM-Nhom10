package vn.edu.p2p.tracker.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.edu.p2p.common.security.PeerAccessTickets;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketKeySetupToolTest {
    @Test
    void printsGroupedFingerprintButNeverKeyContents(@TempDir Path directory) throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            TicketKeySetupTool.main(new String[] {directory.toString()});
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        String publicKey = Files.readString(directory.resolve("tracker-public.key.b64")).trim();
        String privateKey = Files.readString(directory.resolve("tracker-private.key.b64")).trim();
        String expectedFingerprint = PeerAccessTickets.formatFingerprint(
                PeerAccessTickets.fingerprint(PeerAccessTickets.decodePublicKey(publicKey)));
        assertTrue(output.contains(expectedFingerprint));
        assertFalse(output.contains(publicKey));
        assertFalse(output.contains(privateKey));
    }
}
