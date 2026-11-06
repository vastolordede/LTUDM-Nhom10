package vn.edu.p2p.peer.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Lưu danh sách file đang Share ra đĩa để khôi phục sau khi mở lại Peer (D70). */
public final class SharedFilesStore {

    public record Entry(String path, String fileName, long fileSize, String fileHash) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private final Path storeFile;

    private static volatile String currentUser;

    public static void setCurrentUser(String key) { currentUser = key; }

    public static SharedFilesStore current() {
        String key = currentUser;
        return key == null ? null : forUser(key);
    }

    public SharedFilesStore(Path storeFile) {
        this.storeFile = storeFile;
    }

    public static SharedFilesStore forUser(String username) {
        String safe = username.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new SharedFilesStore(
                Path.of(System.getProperty("user.home"), ".fileshare-p2p", safe + "-shared.json"));
    }

    public synchronized List<Entry> load() {
        try {
            return readStoredEntries();
        } catch (IOException e) {
            throw new IllegalStateException("Không thể đọc danh sách chia sẻ. "
                    + "Dữ liệu gốc được giữ lại; hãy kiểm tra bản sao lưu .bak.", e);
        }
    }

    private List<Entry> readStoredEntries() throws IOException {
        if (!Files.exists(storeFile)) return new ArrayList<>();
        if (Files.isSymbolicLink(storeFile) || !Files.isRegularFile(storeFile)) {
            throw new IOException("Đường dẫn danh sách chia sẻ không an toàn.");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(storeFile.toFile());
        } catch (IOException | RuntimeException e) {
            throw invalidStore(e);
        }
        if (root == null) throw invalidStore(new IOException("Empty store"));
        JsonNode entriesNode;
        if (root.isArray()) {
            entriesNode = root;
        } else if (root.isObject()) {
            JsonNode versionNode = root.get("schema_version");
            int version = versionNode == null ? 1 : versionNode.asInt(-1);
            if (version < 1 || version > CURRENT_SCHEMA_VERSION) {
                throw invalidStore(new IOException("Unsupported shared-files schema"));
            }
            entriesNode = root.get("entries");
            if (entriesNode == null || !entriesNode.isArray()) {
                throw invalidStore(new IOException("Invalid shared-files entries"));
            }
        } else {
            throw invalidStore(new IOException("Invalid shared-files root"));
        }
        try {
            Entry[] entries = MAPPER.treeToValue(entriesNode, Entry[].class);
            if (entries == null) throw new IOException("Invalid entries");
            for (Entry entry : entries) {
                if (entry == null || entry.path() == null || entry.fileName() == null
                        || entry.fileHash() == null || entry.fileSize() < 0) {
                    throw new IOException("Invalid shared-file entry");
                }
                Path.of(entry.path());
            }
            return new ArrayList<>(List.of(entries));
        } catch (IOException | RuntimeException e) {
            throw invalidStore(e);
        }
    }

    /** Chỉ trả các file còn tồn tại và đúng kích thước đã lưu. */
    public synchronized List<Entry> loadValid() {
        List<Entry> valid = new ArrayList<>();
        for (Entry e : load()) {
            Path p = Path.of(e.path());
            try {
                if (Files.isRegularFile(p) && !Files.isSymbolicLink(p)
                        && Files.size(p) == e.fileSize() && smallFileHashMatches(e, p)) valid.add(e);
            } catch (IOException ignored) {
                // bỏ qua entry lỗi
            }
        }
        return valid;
    }

    /** Verify small files on restore; large files are validated piece-by-piece when uploaded. */
    private static boolean smallFileHashMatches(Entry entry, Path file) throws IOException {
        if (entry.fileSize() > 32L * 1024 * 1024 || entry.fileHash() == null
                || !entry.fileHash().matches("[0-9a-fA-F]{64}")) return true;
        return entry.fileHash().equalsIgnoreCase(FileHashUtil.sha256(file));
    }

    public synchronized void add(Entry entry) throws IOException {
        List<Entry> all = readStoredEntries();
        all.removeIf(x -> x.fileHash().equalsIgnoreCase(entry.fileHash()));
        all.add(entry);
        save(all);
    }

    public synchronized void removeByHash(String fileHash) throws IOException {
        List<Entry> all = readStoredEntries();
        if (all.removeIf(x -> x.fileHash().equalsIgnoreCase(fileHash))) save(all);
    }

    private void save(List<Entry> entries) throws IOException {
        Path dir = storeFile.toAbsolutePath().getParent();
        if (Files.isSymbolicLink(storeFile) || (dir != null && Files.isSymbolicLink(dir))) {
            throw new IOException("Đường dẫn danh sách chia sẻ không an toàn.");
        }
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".shared-files-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entries);
            try {
                Files.move(tmp, storeFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private IOException invalidStore(Exception cause) throws IOException {
        Path backup = storeFile.resolveSibling(storeFile.getFileName() + ".bak");
        try {
            if (!Files.exists(backup)) Files.copy(storeFile, backup);
        } catch (IOException backupFailure) {
            throw new IOException("Danh sách chia sẻ bị lỗi và không thể tạo bản sao lưu an toàn.",
                    backupFailure);
        }
        return new IOException("Danh sách chia sẻ bị lỗi hoặc không tương thích. "
                + "Đã giữ nguyên tệp gốc và tạo bản sao lưu .bak.", cause);
    }
}