package vn.edu.p2p.peer.network;

import vn.edu.p2p.common.config.EnvConfig;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** Optional administrator-provisioned reference identity, separate from dial address.
 * Both normal PKIX validation and HTTPS SAN validation remain enabled by callers.
 * An exact leaf pin is mandatory whenever the reference identity is overridden.
 * No network learning or trust-on-first-use. */
final class TrackerTlsIdentity {
    private record Reference(String name, String pin) {}
    private static Reference reference(String endpoint) throws SSLPeerUnverifiedException {
        String name = EnvConfig.get("TRACKER_TLS_REFERENCE_IDENTITY", "");
        String pin = EnvConfig.get("TRACKER_TLS_LEAF_SHA256", "");
        if (name.isEmpty() && pin.isEmpty()) return new Reference(endpoint, "");
        String configured = EnvConfig.get("TRACKER_HOST", "");
        if (!endpoint.equalsIgnoreCase(configured) || !name.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
                || name.contains("..") || !pin.matches("[0-9a-fA-F]{64}"))
            throw new SSLPeerUnverifiedException("Invalid administrator Tracker reference identity/pin");
        return new Reference(name, pin);
    }
    static SSLSocket wrap(SSLContext context, Socket transport, String endpoint, int port) throws IOException {
        Reference ref = reference(endpoint);
        return (SSLSocket) context.getSocketFactory().createSocket(transport, ref.name(), port, true);
    }
    static void verify(SSLSocket socket, String endpoint) throws IOException {
        Reference ref = reference(endpoint);
        if (ref.pin().isEmpty()) return;
        try {
            X509Certificate leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            leaf.checkValidity();
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded());
            if (!MessageDigest.isEqual(actual, HexFormat.of().parseHex(ref.pin())))
                throw new SSLPeerUnverifiedException("Tracker certificate fingerprint mismatch");
        } catch (GeneralSecurityException e) {
            SSLPeerUnverifiedException failure = new SSLPeerUnverifiedException("Cannot verify Tracker certificate fingerprint");
            failure.initCause(e);
            throw failure;
        }
    }
}
