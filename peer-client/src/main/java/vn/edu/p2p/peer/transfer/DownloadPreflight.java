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
        Path dir = target.toAbsolutePath().normalize().getParent();
        if (dir == null) throw new PreflightException("Khong xac dinh duoc thu muc dich.");
        try {
            if (Files.exists(target) && Files.isDirectory(target)) {
                throw new PreflightException("Duong dan dich la thu muc, khong phai file.");
            }
            if (!Files.exists(dir)) Files.createDirectories(dir);
            if (!Files.isDirectory(dir)) throw new PreflightException("Thu muc dich khong hop le.");
            if (!Files.isWritable(dir)) throw new PreflightException("Khong co quyen ghi vao thu muc dich.");
            if (Files.exists(target) && !Files.isWritable(target)) {
                throw new PreflightException("Khong co quyen ghi de len file dich.");
            }
            long existing = Files.exists(target) ? Files.size(target) : 0L; // resume: file đã có sẵn một phần
            long needed = Math.max(0L, fileSize - existing) + SAFETY_MARGIN;
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