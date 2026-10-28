package vn.edu.p2p.peer.share;

import vn.edu.p2p.peer.transfer.SafeFileName;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Quét thư mục để Share: bỏ symlink, giới hạn độ sâu/số file, chặn tên trùng. */
public final class FolderScanner {
    public static final int MAX_FILES = 5000;
    public static final int MAX_DEPTH = 32;

    public record Entry(Path absolutePath, String relativePath, long size) {}

    private FolderScanner() {}

    public static List<Entry> scan(Path root) throws IOException {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IOException("Khong phai thu muc hop le: " + root);
        }
        Path base = root.toRealPath();
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(base) && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                if (base.relativize(dir).getNameCount() > MAX_DEPTH) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                StringBuilder rel = new StringBuilder();
                for (Path part : base.relativize(file)) {
                    String err = SafeFileName.validate(part.toString());
                    if (err != null) {
                        throw new IOException("Ten khong hop le \"" + part + "\": " + err);
                    }
                    if (rel.length() > 0) rel.append('/');
                    rel.append(part);
                }
                String relative = rel.toString();
                if (!seen.add(relative.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Trung duong dan (khong phan biet hoa thuong): " + relative);
                }
                if (result.size() >= MAX_FILES) {
                    throw new IOException("Thu muc co qua " + MAX_FILES + " file.");
                }
                result.add(new Entry(file, relative, attrs.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // file khong doc duoc thi bo qua
            }
        });
        return result;
    }
}