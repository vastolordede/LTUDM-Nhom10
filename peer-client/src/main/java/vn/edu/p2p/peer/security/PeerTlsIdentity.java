package vn.edu.p2p.peer.security;


import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.PublicKey;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Identity-based peer TLS.  A Peer uses its existing persistent Ed25519 key pair to
 * create an ephemeral X.509 certificate (the *same* public key advertised by Tracker).
 * The downloader never trusts an arbitrary self-signed certificate: it pins that
 * certificate to the expected uploader's Tracker-authenticated Ed25519 key and ID.
 * Neither public IP nor hostname is part of the Peer identity.
 *
 * No CA/private key distribution or external crypto library is needed.  The strict
 * trust manager is intentionally ONLY used for outbound P2P sockets; Tracker TLS
 * continues to use ordinary PKIX and hostname verification.
 */
public final class PeerTlsIdentity {
    private static final byte[] ED25519_ALGORITHM = seq(oid("1.3.101.112"));
    private static final byte[] SERVER_AUTH = oid("1.3.6.1.5.5.7.3.1");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private PeerTlsIdentity() { }

    public static String dnsIdentity(String peerId) {
        UUID peer = UUID.fromString(peerId);
        return "peer-" + peer + ".fileshare.invalid";
    }

    public static SSLContext serverContext(String peerId, KeyPair identity) throws GeneralSecurityException {
        X509Certificate certificate = selfSignedCertificate(peerId, identity,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(30L * 86400));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try { store.load(null, null); }
        catch (IOException e) { throw new GeneralSecurityException("Cannot initialize peer identity", e); }
        byte[] pass = new byte[24];
        RANDOM.nextBytes(pass);
        char[] password = HexFormat.of().formatHex(pass).toCharArray();
        Arrays.fill(pass, (byte) 0);
        try {
            store.setKeyEntry("peer", identity.getPrivate(), password,
                    new java.security.cert.Certificate[] { certificate });
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, RANDOM);
            return ssl;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public static SSLContext clientContext(String expectedPeerId, PublicKey expectedPeerKey)
            throws GeneralSecurityException {
        if (expectedPeerKey == null || !isEd25519(expectedPeerKey)) {
            throw new GeneralSecurityException("Trusted Ed25519 uploader key is required");
        }
        String expectedDns = dnsIdentity(expectedPeerId);
        TrustManager[] trust = { new PinnedPeerTrust(expectedDns, expectedPeerKey) };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trust, RANDOM);
        return ssl;
    }

    /** Explicit pinning, NOT a trust-all manager. The key must come from TLS-authenticated Tracker. */
    private static final class PinnedPeerTrust implements X509TrustManager {
        private final String expectedDns;
        private final byte[] expectedKey;
        private PinnedPeerTrust(String expectedDns, PublicKey key) {
            this.expectedDns = expectedDns;
            this.expectedKey = key.getEncoded().clone();
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificate authentication is not used by P2P");
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length != 1 || chain[0] == null) {
                throw new CertificateException("Expected one pinned peer certificate");
            }
            X509Certificate cert = chain[0];
            cert.checkValidity();
            if (cert.getBasicConstraints() != -1) {
                throw new CertificateException("Peer identity certificate must not be a CA");
            }
            if (!"Ed25519".equalsIgnoreCase(cert.getSigAlgName())
                    || !isEd25519(cert.getPublicKey())) {
                throw new CertificateException("Peer certificate must use Ed25519");
            }
            if (!MessageDigest.isEqual(expectedKey, cert.getPublicKey().getEncoded())) {
                throw new CertificateException("Peer TLS public key does not match Tracker identity");
            }
            try { cert.verify(cert.getPublicKey()); }
            catch (GeneralSecurityException e) {
                throw new CertificateException("Peer certificate self-signature is invalid", e);
            }
            boolean[] usage = cert.getKeyUsage();
            if (usage == null || usage.length == 0 || !usage[0]) {
                throw new CertificateException("Missing digitalSignature key usage");
            }
            try {
                List<String> eku = cert.getExtendedKeyUsage();
                if (eku == null || !eku.contains("1.3.6.1.5.5.7.3.1")) {
                    throw new CertificateException("Missing TLS serverAuth EKU");
                }
                var sans = cert.getSubjectAlternativeNames();
                if (sans == null || sans.stream().noneMatch(san ->
                        Integer.valueOf(2).equals(san.get(0)) && expectedDns.equals(san.get(1)))) {
                    throw new CertificateException("Peer identity SAN mismatch");
                }
            } catch (CertificateException e) { throw e; }
            catch (Exception e) { throw new CertificateException("Invalid Peer identity extension", e); }
        }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /** Create an X.509v3 certificate using JCA Ed25519 and minimal strict DER encoding. */
    public static X509Certificate selfSignedCertificate(String peerId, KeyPair identity,
                                                        Instant notBefore, Instant notAfter)
            throws GeneralSecurityException {
        String dns = dnsIdentity(peerId);
        if (identity == null || !isEd25519(identity.getPublic()) || !isEd25519(identity.getPrivate())) {
            throw new GeneralSecurityException("Peer key must be Ed25519");
        }
        if (notAfter.isBefore(notBefore) || notAfter.isAfter(notBefore.plusSeconds(400L * 86400))) {
            throw new GeneralSecurityException("Invalid peer certificate validity");
        }
        byte[] serial = new byte[16];
        RANDOM.nextBytes(serial);
        BigInteger serialValue = new BigInteger(1, serial).add(BigInteger.ONE);
        var dn = new X500Principal("CN=" + dns);
        byte[] sanExtension = ext("2.5.29.17", false,
                seq(tlv(0x82, dns.getBytes(StandardCharsets.US_ASCII))));
        byte[] keyUsage = ext("2.5.29.15", true, tlv(0x03, new byte[]{ 7, (byte) 0x80 }));
        byte[] basicConstraints = ext("2.5.29.19", true, seq());
        byte[] extendedUsage = ext("2.5.29.37", false, seq(SERVER_AUTH));
        byte[] tbs = seq(
                tlv(0xa0, integer(BigInteger.valueOf(2))),
                integer(serialValue),
                ED25519_ALGORITHM,
                dn.getEncoded(),
                seq(utc(notBefore), utc(notAfter)),
                dn.getEncoded(),
                identity.getPublic().getEncoded(),
                tlv(0xa3, seq(basicConstraints, keyUsage, extendedUsage, sanExtension)));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(identity.getPrivate());
        signature.update(tbs);
        byte[] encoded = seq(tbs, ED25519_ALGORITHM, bitString(signature.sign()));
        try {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(encoded));
            cert.verify(identity.getPublic());
            cert.checkValidity();
            return cert;
        } catch (CertificateException e) { throw new GeneralSecurityException("Invalid generated Peer X509", e); }
    }

    private static boolean isEd25519(java.security.Key key) {
        if (key == null) return false;
        String algorithm = key.getAlgorithm();
        return "Ed25519".equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm);
    }
    private static byte[] ext(String type, boolean critical, byte[] content) {
        return critical ? seq(oid(type), tlv(1, new byte[]{(byte) 0xff}), tlv(4, content))
                : seq(oid(type), tlv(4, content));
    }
    private static byte[] utc(Instant value) {
        return tlv(0x17, UTC_TIME.format(value).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] integer(BigInteger value) { return tlv(2, value.toByteArray()); }
    private static byte[] bitString(byte[] input) {
        byte[] bits = new byte[input.length + 1];
        System.arraycopy(input, 0, bits, 1, input.length);
        return tlv(3, bits);
    }
    private static byte[] oid(String value) {
        String[] parts = value.split("\\.");
        int a = Integer.parseInt(parts[0]), b = Integer.parseInt(parts[1]);
        if (a < 0 || a > 2 || b < 0 || (a < 2 && b > 39)) throw new IllegalArgumentException("OID");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBase128(out, 40L*a + b);
        for (int i=2; i<parts.length; i++) writeBase128(out, Long.parseLong(parts[i]));
        return tlv(6, out.toByteArray());
    }
    private static void writeBase128(ByteArrayOutputStream out, long value) {
        if (value < 0) throw new IllegalArgumentException("OID");
        int n = 1;
        for (long v = value; v > 127; v >>= 7) n++;
        for (int j=n-1; j>=0; j--) {
            int b = (int)((value >> (j*7)) & 127);
            out.write(j == 0 ? b : b|0x80);
        }
    }
    private static byte[] seq(byte[]... items) { return tlv(0x30, concat(items)); }
    private static byte[] concat(byte[]... items) {
        int len=0; for (byte[] item: items) len += item.length;
        byte[] value = new byte[len];
        int n=0; for (byte[] item:items) { System.arraycopy(item,0,value,n,item.length); n+=item.length; }
        return value;
    }
    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (value.length < 128) out.write(value.length);
        else {
            int n=1; for (int v=value.length; v>255; v>>=8) n++;
            out.write(128|n);
            for (int j=n-1;j>=0;j--) out.write((value.length >> (8*j))&255);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
