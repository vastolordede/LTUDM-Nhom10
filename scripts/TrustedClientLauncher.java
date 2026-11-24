import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Release launcher. Trust is shipped by the administrator, never learned from the network. */
public final class TrustedClientLauncher {
    private static final Set<String> ALLOWED = Set.of("P2P_PROFILE", "P2P_ALLOW_INSECURE_DEV",
            "TRACKER_HOST", "TRACKER_PORT", "TRACKER_TLS_ENABLED", "TRACKER_TRUSTSTORE",
            "TRACKER_TRUSTSTORE_PASSWORD", "TRACKER_TRUSTSTORE_TYPE", "P2P_TLS_ENABLED",
            "P2P_TICKET_PUBLIC_KEY_FILE", "P2P_TICKET_KEY_ID", "RELAY_ENABLED", "RENDEZVOUS_PORT",
            "TRACKER_TLS_REFERENCE_IDENTITY", "TRACKER_TLS_LEAF_SHA256");
    private static final String[] PREFIXES = {"P2P_", "TRACKER_", "DB_", "RENDEZVOUS_", "RELAY_", "HEARTBEAT_"};
    public static void main(String[] args) throws Exception {
        Path base = Path.of(TrustedClientLauncher.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toAbsolutePath().normalize().getParent();
        Path bootstrap = base.resolve("CLIENT_PUBLIC_BOOTSTRAP");
        Path envFile = bootstrap.resolve(".env");
        Map<String,String> config = validate(bootstrap);
        System.out.println("Trusted Tracker: " + config.get("TRACKER_HOST") + ":" + config.get("TRACKER_PORT") + " (TLS)");
        if (args.length == 1 && "--check-bootstrap".equals(args[0])) return;
        if (args.length == 1 && "--check-ec2-bootstrap".equals(args[0])) {
            String host = config.get("TRACKER_HOST").toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("0.0.0.0"))
                throw new IllegalArgumentException("EC2 release cannot contain local demo trust/endpoint");
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Unknown launch option");
        if (Runtime.version().feature() < 21) throw new IllegalStateException("Java 21 required");
        Path lib = base.resolve("lib");
        if (!Files.isDirectory(lib)) throw new IllegalStateException("Distribution lib directory missing");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", lib.toString() + lib.getFileSystem().getSeparator() + "*", "vn.edu.p2p.peer.PeerLauncher");
        pb.directory(base.toFile());
        Map<String,String> env = pb.environment();
        env.keySet().removeIf(TrustedClientLauncher::overridesTrust);
        env.put("P2P_ENV_FILE", envFile.toString());
        env.put("P2P_PROFILE", "production");
        env.put("P2P_ALLOW_INSECURE_DEV", "false");
        System.exit(pb.inheritIO().start().waitFor());
    }
    private static boolean overridesTrust(String key) {
        String name = key.toUpperCase(Locale.ROOT);
        return Arrays.stream(PREFIXES).anyMatch(name::startsWith)
                || Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").contains(name);
    }
    static Map<String,String> validate(Path dir) throws Exception {
        Path file = dir.resolve(".env");
        Map<String,String> c = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Invalid public bootstrap format");
            String key = line.substring(0, eq).strip(), value = line.substring(eq+1).strip();
            if (!ALLOWED.contains(key) || c.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Unexpected/duplicate public bootstrap setting: " + key);
        }
        require(c, "P2P_PROFILE", "production"); require(c, "P2P_ALLOW_INSECURE_DEV", "false");
        require(c, "TRACKER_TLS_ENABLED", "true"); require(c, "P2P_TLS_ENABLED", "true");
        require(c, "TRACKER_TRUSTSTORE", "certs/peer-truststore.p12");
        require(c, "P2P_TICKET_PUBLIC_KEY_FILE", "certs/tracker-public.key.b64");
        if (!"PKCS12".equals(c.getOrDefault("TRACKER_TRUSTSTORE_TYPE", "PKCS12")))
            throw new IllegalArgumentException("Expected PKCS12 truststore");
        String host = c.getOrDefault("TRACKER_HOST", "");
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || host.contains(".."))
            throw new IllegalArgumentException("Invalid trusted Tracker host");
        String reference = c.getOrDefault("TRACKER_TLS_REFERENCE_IDENTITY", "");
        String pin = c.getOrDefault("TRACKER_TLS_LEAF_SHA256", "");
        if (!reference.isEmpty() || !pin.isEmpty()) {
            if (!reference.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || reference.contains("..") || !pin.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("Invalid Tracker TLS reference identity/pin");
        }
        int port = Integer.parseInt(c.getOrDefault("TRACKER_PORT", "0"));
        int rendezvous = Integer.parseInt(c.getOrDefault("RENDEZVOUS_PORT", "9001"));
        if (port < 1 || port > 65535 || rendezvous < 1 || rendezvous > 65535)
            throw new IllegalArgumentException("Invalid Tracker/Rendezvous port");
        String pass = c.getOrDefault("TRACKER_TRUSTSTORE_PASSWORD", "");
        if (pass.isBlank() || !pass.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Missing/invalid public truststore password");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve(c.get("TRACKER_TRUSTSTORE")))) {
            store.load(in, pass.toCharArray());
        }
        int count = 0;
        for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements();) {
            String alias = aliases.nextElement();
            if (!store.isCertificateEntry(alias) || !(store.getCertificate(alias) instanceof X509Certificate cert))
                throw new IllegalArgumentException("Client bootstrap contains non-public material");
            cert.checkValidity();
            if (cert.getBasicConstraints() < 0) throw new IllegalArgumentException("Expected a trusted CA certificate");
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("Empty truststore");
        String key = Files.readString(dir.resolve(c.get("P2P_TICKET_PUBLIC_KEY_FILE"))).strip();
        KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
        return Map.copyOf(c);
    }
    private static void require(Map<String,String> c, String key, String value) {
        if (!value.equals(c.get(key))) throw new IllegalArgumentException("Invalid public bootstrap setting: " + key);
    }
}
