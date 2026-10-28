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
        if (!Files.isRegularFile(storeFile)) return new ArrayList<>();
        try {
            Entry[] arr = MAPPER.readValue(storeFile.toFile(), Entry[].class);
            return new ArrayList<>(List.of(arr));
        } catch (IOException e) {
            return new ArrayList<>(); // file hỏng: coi như chưa có gì
        }
    }

    /** Chỉ trả các file còn tồn tại và đúng kích thước đã lưu. */
    public synchronized List<Entry> loadValid() {
        List<Entry> valid = new ArrayList<>();
        for (Entry e : load()) {
            Path p = Path.of(e.path());
            try {
                if (Files.isRegularFile(p) && Files.size(p) == e.fileSize()) valid.add(e);
            } catch (IOException ignored) {
                // bỏ qua entry lỗi
            }
        }
        return valid;
    }

    public synchronized void add(Entry entry) throws IOException {
        List<Entry> all = load();
        all.removeIf(x -> x.fileHash().equalsIgnoreCase(entry.fileHash()));
        all.add(entry);
        save(all);
    }

    public synchronized void removeByHash(String fileHash) throws IOException {
        List<Entry> all = load();
        if (all.removeIf(x -> x.fileHash().equalsIgnoreCase(fileHash))) save(all);
    }

    private void save(List<Entry> entries) throws IOException {
        Path dir = storeFile.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entries);
        Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
    }
}