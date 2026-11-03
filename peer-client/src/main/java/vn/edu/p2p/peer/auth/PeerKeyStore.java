package vn.edu.p2p.peer.auth;

import vn.edu.p2p.common.security.PeerRequestProof;
import vn.edu.p2p.common.security.PeerAccessTickets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public final class PeerKeyStore {
    private static final String PRIVATE_KEY = "private.pkcs8";
    private static final String PUBLIC_KEY = "public.x509";
    private static final byte[] KEY_PAIR_CHECK = "fileshare-peer-key-check-v1"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private final Path directory;

    public PeerKeyStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static PeerKeyStore defaultStore() {
        return new PeerKeyStore(Path.of(System.getProperty("user.home"), ".fileshare-p2p"));
    }

    public synchronized KeyPair loadOrCreate(String peerId) throws IOException {
        UUID id;
        try {
            id = UUID.fromString(peerId);
        } catch (RuntimeException e) {
            throw new IOException("Cannot load Peer key for an invalid Peer ID", e);
        }
        Path keyFile = directory.resolve("peer-" + id + ".key");
        if (Files.exists(keyFile)) return load(keyFile);
        return create(keyFile);
    }

    public String encodePublicKey(KeyPair pair) {
        return PeerRequestProof.encodePublicKey(pair.getPublic());
    }

    private KeyPair create(Path keyFile) throws IOException {
        KeyPair pair = PeerRequestProof.generateKeyPair();
        Properties properties = new Properties();
        properties.setProperty(PRIVATE_KEY,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setProperty(PUBLIC_KEY, PeerRequestProof.encodePublicKey(pair.getPublic()));

        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "peer-key-", ".tmp");
        try {
            setOwnerOnlyPermissions(temp);
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "FileShare-P2P Peer key; private material");
            }
            try {
                Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, keyFile);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                return load(keyFile);
            }
            setOwnerOnlyPermissions(keyFile);
            return pair;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return load(keyFile);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static KeyPair load(Path keyFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(keyFile)) {
            properties.load(in);
        } catch (IOException e) {
            throw new IOException("Cannot read stored Peer key file: " + keyFile, e);
        }

        try {
            String privateValue = properties.getProperty(PRIVATE_KEY);
            String publicValue = properties.getProperty(PUBLIC_KEY);
            if (privateValue == null || publicValue == null) {
                throw new IllegalArgumentException("Required key property is missing");
            }
            PrivateKey privateKey = PeerAccessTickets.decodePrivateKey(privateValue);
            java.security.PublicKey publicKey = PeerRequestProof.decodePublicKey(publicValue);
            KeyPair pair = new KeyPair(publicKey, privateKey);
            verifyPair(pair);
            return pair;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            throw new IOException("Stored Peer key file is invalid: " + keyFile, e);
        }
    }

    private static void verifyPair(KeyPair pair) throws java.security.GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(KEY_PAIR_CHECK);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update(KEY_PAIR_CHECK);
        if (!verifier.verify(signature)) {
            throw new java.security.GeneralSecurityException("Stored public/private keys do not match");
        }
    }

    private static void setOwnerOnlyPermissions(Path path) {
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // POSIX permissions are best effort on filesystems and operating systems that support them.
        }
    }
}
