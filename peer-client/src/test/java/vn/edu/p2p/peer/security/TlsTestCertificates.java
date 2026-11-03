package vn.edu.p2p.peer.security;

import vn.edu.p2p.common.security.TlsContextFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TlsTestCertificates {
    private TlsTestCertificates() {}

    public record Material(Path keyStore, Path trustStore, String password) {}

    public static Material create(Path directory, String san, String startDate) throws Exception {
        String password = UUID.randomUUID().toString();
        String alias = "tls-test-" + UUID.randomUUID();
        Path caStore = directory.resolve(alias + "-ca.p12");
        Path caCertificate = directory.resolve(alias + "-ca.cer");
        Path keyStore = directory.resolve(alias + ".p12");
        Path request = directory.resolve(alias + ".csr");
        Path signedCertificate = directory.resolve(alias + "-signed.cer");
        Path trustStore = directory.resolve(alias + "-trust.p12");

        runKeytool("-genkeypair", "-alias", "test-ca", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "3650", "-dname", "CN=Ephemeral TLS Test CA",
                "-ext", "BC=ca:true", "-ext", "KU=keyCertSign,cRLSign",
                "-storetype", "PKCS12", "-keystore", caStore.toString(),
                "-storepass", password, "-keypass", password, "-noprompt");
        runKeytool("-exportcert", "-alias", "test-ca", "-keystore", caStore.toString(),
                "-storepass", password, "-file", caCertificate.toString());
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=" + san,
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", password, "-keypass", password, "-noprompt");
        runKeytool("-certreq", "-alias", alias, "-keystore", keyStore.toString(),
                "-storepass", password, "-file", request.toString(), "-ext", "SAN=" + san);
        runKeytool("-gencert", "-alias", "test-ca", "-keystore", caStore.toString(),
                "-storepass", password, "-infile", request.toString(),
                "-outfile", signedCertificate.toString(), "-validity", "2",
                "-ext", "SAN=" + san, "-ext", "KU=digitalSignature,keyEncipherment",
                "-ext", "EKU=serverAuth",
                startDate == null ? null : "-startdate", startDate);
        runKeytool("-importcert", "-alias", "test-ca", "-file", caCertificate.toString(),
                "-keystore", keyStore.toString(), "-storepass", password, "-noprompt");
        runKeytool("-importcert", "-alias", alias, "-file", signedCertificate.toString(),
                "-keystore", keyStore.toString(), "-storepass", password, "-noprompt");
        runKeytool("-importcert", "-alias", "test-ca", "-file", caCertificate.toString(),
                "-storetype", "PKCS12", "-keystore", trustStore.toString(),
                "-storepass", password, "-noprompt");
        return new Material(keyStore, trustStore, password);
    }

    public static Material createUnrelatedTrustStore(Path directory) throws Exception {
        return create(directory, "DNS:unrelated.invalid", null);
    }

    public static SSLContext serverContext(Material material) throws Exception {
        return TlsContextFactory.createServerContext(
                material.keyStore(), material.password(), "PKCS12");
    }

    private static void runKeytool(String... arguments) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "keytool.exe" : "keytool").toString();
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(executable);
        for (String argument : arguments) {
            if (argument != null) command.add(argument);
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new IOException("keytool timed out while creating ephemeral TLS test material");
        }
        if (process.exitValue() != 0) {
            throw new IOException("keytool failed to create ephemeral TLS test material");
        }
    }
}
