import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Administrative offline provisioning utility for FileShare-P2P secure releases.
 * Usage: java scripts/SecureReleaseProvisioner.java OUTPUT_DIR TRACKER_HOST
 * The output contains PRIVATE KEYS and MUST NOT be uploaded to Git or to a public ZIP.
 * The public client bootstrap contains NO Peer private key. The Peer creates its own
 * persistent Ed25519 identity during login, independent of its current IP.
 * Never re-run this tool for an existing Tracker: it creates NEW CA/signing keys.
 */
public final class SecureReleaseProvisioner {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,48}");
    private static final Pattern HOST = Pattern.compile("[a-zA-Z0-9.-]{1,253}");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: java scripts/SecureReleaseProvisioner.java "
                    + "OUTPUT_DIR TRACKER_HOST");
        }
        Path destination = Path.of(args[0]).toAbsolutePath().normalize();
        String trackerHost = checkedHost(args[1]);
        if (Files.exists(destination)) {
            throw new IllegalStateException("Destination already exists; refusing to overwrite private keys: "
                    + destination);
        }

        Path admin = destination.resolve("ADMIN_KEEP_PRIVATE");
        Path tracker = destination.resolve("TRACKER_INSTALL");
        Files.createDirectories(admin);
        Files.createDirectories(tracker.resolve("certs"));
        String rootPass = password();
        String trackerPass = password();
        String trustPass = password();
        Path caStore = admin.resolve("ca-private.p12");
        Path caCert = admin.resolve("ca-public.pem");
        tool("-genkeypair", "-alias", "root-ca", "-storetype", "PKCS12",
                "-keystore", caStore.toString(), "-storepass", rootPass, "-keypass", rootPass,
                "-keyalg", "RSA", "-keysize", "4096", "-validity", "3650", "-dname",
                "CN=FileShare-P2P Offline Root CA, OU=Deployment", "-ext", "BC=ca:true", "-noprompt");
        tool("-exportcert", "-alias", "root-ca", "-keystore", caStore.toString(),
                "-storepass", rootPass, "-rfc", "-file", caCert.toString());

        Path trackerStore = tracker.resolve("certs/tracker-server.p12");
        cert(caStore, rootPass, caCert, trackerStore, trackerPass,
                "tracker", trackerHost, "serverAuth");
        KeyPair ticketPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path trackerPrivate = tracker.resolve("certs/tracker-private.key.b64");
        Path trackerPublic = tracker.resolve("certs/tracker-public.key.b64");
        write(trackerPrivate, Base64.getEncoder().encodeToString(ticketPair.getPrivate().getEncoded()));
        write(trackerPublic, Base64.getEncoder().encodeToString(ticketPair.getPublic().getEncoded()));
        restrict(trackerPrivate);
        String fingerprint = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(ticketPair.getPublic().getEncoded()));

        String trackerEnv = "P2P_PROFILE=production\nP2P_ALLOW_INSECURE_DEV=false\n"
                + "TRACKER_PORT=9000\nTRACKER_TLS_ENABLED=true\n"
                + "TRACKER_TLS_KEYSTORE=certs/tracker-server.p12\n"
                + "TRACKER_TLS_KEYSTORE_PASSWORD=" + trackerPass + "\n"
                + "P2P_TICKET_SIGNING_KEY_FILE=certs/tracker-private.key.b64\n"
                + "P2P_TICKET_PUBLIC_KEY_FILE=certs/tracker-public.key.b64\n"
                + "RELAY_ENABLED=true\nRENDEZVOUS_PORT=9001\n"
                + "# The administrator must supply DB_USER/DB_PASSWORD via environment or secure local configuration.\n";
        write(tracker.resolve(".env"), trackerEnv);
        restrict(tracker.resolve(".env"));
        Path clientPublic = destination.resolve("CLIENT_PUBLIC_BOOTSTRAP");
        Path clientCerts = clientPublic.resolve("certs");
        Files.createDirectories(clientCerts);
        makeTrustStore(caCert, clientCerts.resolve("peer-truststore.p12"), trustPass);
        Files.copy(trackerPublic, clientCerts.resolve("tracker-public.key.b64"));
        String clientEnv = "P2P_PROFILE=production\nP2P_ALLOW_INSECURE_DEV=false\n"
                + "TRACKER_HOST=" + trackerHost + "\nTRACKER_PORT=9000\n"
                + "TRACKER_TLS_ENABLED=true\nTRACKER_TRUSTSTORE=certs/peer-truststore.p12\n"
                + "TRACKER_TRUSTSTORE_PASSWORD=" + trustPass + "\n"
                + "P2P_TLS_ENABLED=true\n"
                + "P2P_TICKET_PUBLIC_KEY_FILE=certs/tracker-public.key.b64\n"
                + "RELAY_ENABLED=true\nRENDEZVOUS_PORT=9001\n";
        write(clientPublic.resolve(".env"), clientEnv);

        write(admin.resolve("SECURE_RELEASE_NOTES.txt"), "Tracker public-key fingerprint SHA-256:\n"
                + fingerprint + "\n\nWARNING: This directory has private CA material. "
                + "KEEP OFFLINE. Never include ADMIN_KEEP_PRIVATE in a client package.\n"
                + "Server certificate SAN: " + trackerHost + "\n"
                + "Clients receive ONLY a public bootstrap; Peer keys are created locally at login.\n");
        write(admin.resolve("ca-store-password.txt"), rootPass);
        restrict(admin.resolve("ca-store-password.txt"));
        System.out.println("Secure release packages provisioned: " + destination);
        System.out.println("Public ticket-key fingerprint SHA-256: " + fingerprint);
        System.out.println("Distribute ONLY CLIENT_PUBLIC_BOOTSTRAP through a trusted channel.");
        System.out.println("Never rotate existing Tracker CA/ticket keys by rerunning provisioner.");
    }

    private static String checkedHost(String host) {
        if (!HOST.matcher(host).matches() || host.startsWith("-") || host.contains("..")) {
            throw new IllegalArgumentException("Expected DNS host or IPv4 address: " + host);
        }
        return host;
    }

    private static String san(String host) {
        if (host.matches("[0-9.]+")) return "IP:" + host;
        return "DNS:" + host;
    }

    private static void cert(Path ca, String caPass, Path caCert, Path target,
                             String password, String id, String host, String eku) throws Exception {
        String alias = "node";
        Path req = target.resolveSibling(id + ".csr");
        Path crt = target.resolveSibling(id + ".pem");
        tool("-genkeypair", "-alias", alias, "-storetype", "PKCS12", "-keystore", target.toString(),
                "-storepass", password, "-keypass", password, "-keyalg", "RSA", "-keysize", "3072",
                "-validity", "825", "-dname", "CN=" + host + ", OU=FileShare-P2P", "-ext",
                "SAN=" + san(host), "-noprompt");
        tool("-certreq", "-alias", alias, "-keystore", target.toString(), "-storepass", password,
                "-file", req.toString(), "-ext", "SAN=" + san(host));
        tool("-gencert", "-alias", "root-ca", "-keystore", ca.toString(), "-storepass", caPass,
                "-infile", req.toString(), "-outfile", crt.toString(), "-rfc", "-validity", "825",
                "-ext", "SAN=" + san(host), "-ext", "EKU=" + eku,
                "-ext", "KU=digitalSignature,keyEncipherment");
        tool("-importcert", "-alias", "ca", "-keystore", target.toString(),
                "-storepass", password, "-file", caCert.toString(), "-noprompt");
        tool("-importcert", "-alias", alias, "-keystore", target.toString(),
                "-storepass", password, "-file", crt.toString(), "-noprompt");
        Files.deleteIfExists(req);
        Files.deleteIfExists(crt);
        restrict(target);
    }

    private static void makeTrustStore(Path caCert, Path output, String password) throws Exception {
        tool("-importcert", "-alias", "fileshare-ca", "-storetype", "PKCS12",
                "-keystore", output.toString(), "-storepass", password,
                "-file", caCert.toString(), "-noprompt");
        restrict(output);
    }

    private static void tool(String... args) throws Exception {
        String suffix = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "";
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool" + suffix);
        List<String> cmd = new ArrayList<>();
        cmd.add(executable.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) {
            // Do not print full keytool command, which includes store passwords.
            throw new IOException("Keytool " + args[0] + " failed (exit " + code + "): "
                    + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static String password() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void write(Path path, String value) throws IOException {
        Files.writeString(path, value + (value.endsWith("\n") ? "" : "\n"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows installer must apply ACLs (see install_secure_peer_windows.ps1).
        }
    }
}
