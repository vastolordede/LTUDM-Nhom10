package vn.edu.p2p.tracker.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 5, unit = TimeUnit.SECONDS)
class TicketKeySetupToolPrivacyTest {
    @Test
    void setupOutputNamesKeyFilesWithoutPrintingLocalDirectoryOrKeyMaterial(@TempDir Path directory)
            throws Exception {
        PrintStream previousOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            TicketKeySetupTool.main(new String[]{directory.toString()});
        } finally {
            System.setOut(previousOut);
        }

        String text = captured.toString(StandardCharsets.UTF_8);
        String privateKey = Files.readString(directory.resolve("tracker-private.key.b64")).trim();
        String publicKey = Files.readString(directory.resolve("tracker-public.key.b64")).trim();
        assertTrue(text.contains("tracker-private.key.b64"));
        assertTrue(text.contains("tracker-public.key.b64"));
        assertFalse(text.contains(directory.toString()));
        assertFalse(text.contains(privateKey));
        assertFalse(text.contains(publicKey));
    }
}
