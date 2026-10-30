package vn.edu.p2p.peer.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DownloadPreflight {
    private static final long SAFETY_MARGIN = 1L << 20; // 1 MiB

    public static class PreflightException extends Exception {
        public PreflightException(String message) { super(message); }
    }

    private DownloadPreflight() {}

    public static void check(Path target, long fileSize) throws PreflightException {
        if (target == null || fileSize < 0) throw new PreflightException("File kich thuoc hoac duong dan khong hop le.");
        Path safeTarget = target.toAbsolutePath().normalize();
        Path dir = safeTarget.getParent();
        if (dir == null) throw new PreflightException("Khong xac dinh duoc thu muc dich.");
        try {
            Path cursor = dir;
            while (cursor != null) {
                if (Files.isSymbolicLink(cursor)) {
                    throw new PreflightException("Duong dan dich di qua symlink: " + cursor);
                }
                cursor = cursor.getParent();
            }
            if (Files.isSymbolicLink(safeTarget)) {
                throw new PreflightException("File dich la symlink.");
            }
            if (Files.exists(target) && Files.isDirectory(target)) {
                throw new PreflightException("Duong dan dich la thu muc, khong phai file.");
            }
            if (!Files.exists(dir)) Files.createDirectories(dir);
            if (!Files.isDirectory(dir)) throw new PreflightException("Thu muc dich khong hop le.");
            if (!Files.isWritable(dir)) throw new PreflightException("Khong co quyen ghi vao thu muc dich.");
            if (Files.exists(target) && !Files.isWritable(target)) {
                throw new PreflightException("Khong co quyen ghi de len file dich.");
            }
            long existing = Files.exists(target) ? Files.size(target) : 0L; // resume
            long remaining = existing >= fileSize ? 0 : fileSize - existing;
            long needed = remaining > Long.MAX_VALUE - SAFETY_MARGIN
                    ? Long.MAX_VALUE : remaining + SAFETY_MARGIN;
            long usable = Files.getFileStore(dir).getUsableSpace();
            if (usable < needed) {
                throw new PreflightException("Khong du dung luong trong: can " + mb(needed)
                        + " MB, con " + mb(usable) + " MB.");
            }
        } catch (IOException e) {
            throw new PreflightException("Khong kiem tra duoc thu muc dich: " + e.getMessage());
        }
    }

    private static long mb(long bytes) { return bytes / (1024 * 1024); }
}