package vn.edu.p2p.peer.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Biến relative path nhận từ mạng thành đường dẫn đích an toàn bên trong thư mục đích. */
public final class FolderPathResolver {
    private FolderPathResolver() {}

    public static Path resolve(Path destRoot, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank() || relativePath.length() > 1024) {
            throw new IOException("Duong dan tuong doi khong hop le.");
        }
        if (relativePath.startsWith("/") || relativePath.contains("\\") || relativePath.contains(":")) {
            throw new IOException("Duong dan tuong doi khong duoc tuyet doi hoac chua \\ va :");
        }
        Path root = destRoot.toAbsolutePath().normalize();
        for (String part : relativePath.split("/", -1)) {
            String err = SafeFileName.validate(part);
            if (err != null) throw new IOException("Thanh phan \"" + part + "\": " + err);
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Duong dan thoat ra ngoai thu muc dich.");
        }
        // Khong cho di qua symlink dang ton tai
        Path cursor = root;
        for (Path part : root.relativize(target)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(cursor)) {
                throw new IOException("Phat hien symlink trong duong dan dich: " + cursor);
            }
        }
        return target;
    }

    /** Tên trùng: a.txt -> a (1).txt, a (2).txt ... */
    public static Path uniquify(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = target.resolveSibling(stem + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("Khong tao duoc ten file khong trung.");
    }
}