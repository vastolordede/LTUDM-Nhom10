package vn.edu.p2p.peer.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Lưu danh sách file đang Share ra đĩa để khôi phục sau khi mở lại Peer (D70). */
public final class SharedFilesStore {

    public record Entry(String path, String fileName, long fileSize, String fileHash) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
            // The UI can stay open, but writes must NOT overwrite a corrupt store.
            return new ArrayList<>();
        }
    }

    private List<Entry> readStoredEntries() throws IOException {
        if (!Files.exists(storeFile)) return new ArrayList<>();
        if (Files.isSymbolicLink(storeFile) || !Files.isRegularFile(storeFile)) {
            throw new IOException("Unsafe shared files store: " + storeFile);
        }
        try {
            Entry[] arr = MAPPER.readValue(storeFile.toFile(), Entry[].class);
            if (arr == null) throw new IOException("Invalid shared files store: " + storeFile);
            return new ArrayList<>(List.of(arr));
        } catch (RuntimeException e) {
            throw new IOException("Invalid shared files store: " + storeFile, e);
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
            throw new IOException("Unsafe shared files store path: " + storeFile);
        }
        if (dir != null) Files.createDirectories(dir);
        Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entries);
        Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
    }
}