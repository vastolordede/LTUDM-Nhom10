package vn.edu.p2p.peer.transfer;

import vn.edu.p2p.common.dto.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** R90: only files claimed by a previous P2P download are eligible for partial resume. */
final class ResumeOwnershipMarker {
    private static final String MAGIC = "P2P-RESUME-V1\n";
    private ResumeOwnershipMarker() { }

    private static Path marker(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".p2p-resume");
    }

    static boolean matches(Path target, FileMetadata metadata) throws IOException {
        Path claim = marker(target);
        if (!Files.exists(claim, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(claim) || !Files.isRegularFile(claim)) {
            throw new IOException("Unsafe download resume marker");
        }
        return Files.readString(claim).equals(MAGIC + metadata.fileHash().toLowerCase()
                + "\n" + metadata.fileSize() + "\n");
    }

    static void claim(Path target, FileMetadata metadata) throws IOException {
        Path claim = marker(target);
        if (Files.exists(claim, LinkOption.NOFOLLOW_LINKS)) {
            if (!matches(target, metadata)) {
                throw new IOException("Existing download resume marker belongs to another file");
            }
            return;
        }
        Path parent = claim.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".p2p-claim-", ".tmp");
        try {
            Files.writeString(temp, MAGIC + metadata.fileHash().toLowerCase() + "\n"
                    + metadata.fileSize() + "\n", StandardOpenOption.TRUNCATE_EXISTING);
            // Never silently replace a marker created by a different download.
            Files.move(temp, claim);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void release(Path target) throws IOException {
        Path claim = marker(target);
        if (Files.isSymbolicLink(claim)) {
            throw new IOException("Cannot delete a symlink resume marker");
        }
        Files.deleteIfExists(claim);
    }
}
