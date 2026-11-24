import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Use for an EXISTING Tracker. Reads only public CA and ticket signing key.
 * Usage: java scripts/CreatePublicClientBootstrap.java OUTPUT_DIR TRACKER_HOST CA_PUBLIC_PEM TICKET_PUBLIC_B64
 * Output contains no private material and must be distributed by a trusted release channel. */
public final class CreatePublicClientBootstrap {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: OUTPUT_DIR TRACKER_HOST CA_PUBLIC_PEM TICKET_PUBLIC_B64");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IOException("Output exists; refusing overwrite");
        String host = args[1].trim();
        if (!host.matches("[a-zA-Z0-9.-]{1,253}") || host.startsWith("-") || host.contains(".."))
            throw new IllegalArgumentException("Invalid Tracker host");
        Path ca = Path.of(args[2]);
        Path ticket = Path.of(args[3]);
        if (!Files.isRegularFile(ca) || !Files.isRegularFile(ticket))
            throw new IOException("Public CA certificate and ticket signing public key required");
        Path certDir = output.resolve("certs");
        Files.createDirectories(certDir);
        String publicTrustPassword = "fileshare-public-trust-v1";
        ProcessBuilder pb = new ProcessBuilder(List.of(
                Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString(),
                "-importcert", "-alias", "tracker-ca", "-storetype", "PKCS12", "-keystore",
                certDir.resolve("peer-truststore.p12").toString(),
                "-storepass", publicTrustPassword, "-file", ca.toAbsolutePath().toString(), "-noprompt"));
        Process proc = pb.redirectErrorStream(true).start();
        byte[] log = proc.getInputStream().readAllBytes();
        if (proc.waitFor() != 0) throw new IOException("Invalid public CA certificate: "
                + new String(log, StandardCharsets.UTF_8));
        Files.copy(ticket, certDir.resolve("tracker-public.key.b64"));
        Files.writeString(output.resolve(".env"),
                "P2P_PROFILE=production\nP2P_ALLOW_INSECURE_DEV=false\n"
                        + "TRACKER_HOST=" + host + "\nTRACKER_PORT=9000\n"
                        + "TRACKER_TLS_ENABLED=true\n"
                        + "TRACKER_TRUSTSTORE=certs/peer-truststore.p12\n"
                        + "TRACKER_TRUSTSTORE_PASSWORD=" + publicTrustPassword + "\n"
                        + "P2P_TLS_ENABLED=true\n"
                        + "P2P_TICKET_PUBLIC_KEY_FILE=certs/tracker-public.key.b64\n"
                        + "RELAY_ENABLED=true\nRENDEZVOUS_PORT=9001\n",
                StandardCharsets.UTF_8);
        System.out.println("Public client bootstrap ready: " + output);
        System.out.println("Publish only after verifying CA and ticket key fingerprints out of band.");
    }
}
