package vn.edu.p2p.tracker.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.EnumSet;

/** Run ONCE on Tracker. Private key is never written to stdout or Git. */
public final class TicketKeySetupTool {
    private TicketKeySetupTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: generateTicketKeys [output_directory]");
        Path dir = args.length == 1 ? Path.of(args[0]) :
                Path.of(System.getProperty("user.home"), ".fileshare-p2p");
        dir = dir.toAbsolutePath().normalize();
        Files.createDirectories(dir);
        if (Files.isSymbolicLink(dir)) throw new IllegalStateException("Refusing symlinked key directory");
        Path privatePath = dir.resolve("tracker-private.key.b64");
        Path publicPath = dir.resolve("tracker-public.key.b64");
        if (Files.exists(privatePath, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(publicPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Key files already exist; do not rotate keys implicitly: " + dir);
        }
        setPosixOwnerOnly(dir, true);
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String privateBase64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.writeString(privatePath, privateBase64 + System.lineSeparator(), StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        setPosixOwnerOnly(privatePath, false);
        Files.writeString(publicPath, publicBase64 + System.lineSeparator(), StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        setPosixOwnerOnly(publicPath, false);
        System.out.println("Tracker PRIVATE key stored (DO NOT share): " + privatePath);
        System.out.println("Distribute ONLY the PUBLIC file to trusted Peer machines: " + publicPath);
        System.out.println("Public key fingerprint SHA-256 (base64): " + fingerprint(pair.getPublic().getEncoded()));
        System.out.println("Copy public file to ~/.fileshare-p2p/tracker-public.key.b64 on each peer.");
        System.out.println("On Windows, verify that the private file ACL permits only your user.");
    }

    private static String fingerprint(byte[] encoded) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static void setPosixOwnerOnly(Path path, boolean directory) throws Exception {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows permissions are ACL-based; operator must inspect access rules.
        }
    }
}
