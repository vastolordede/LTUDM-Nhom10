package vn.edu.p2p.peer.share;

import vn.edu.p2p.common.dto.DiscoverySort;
import vn.edu.p2p.common.dto.FolderSearchItem;
import vn.edu.p2p.common.dto.SearchResultItem;

import java.util.Comparator;

/** One File or Folder row in the merged, ID-free Explore catalog. */
public record DiscoveryEntry(SearchResultItem file, FolderSearchItem folder) {
    public DiscoveryEntry {
        if ((file == null) == (folder == null)) {
            throw new IllegalArgumentException("Exactly one of file or folder is required");
        }
    }
    public static DiscoveryEntry ofFile(SearchResultItem file) { return new DiscoveryEntry(file, null); }
    public static DiscoveryEntry ofFolder(FolderSearchItem folder) { return new DiscoveryEntry(null, folder); }
    public boolean isFolder() { return folder != null; }
    public String type() { return isFolder() ? "Thư mục" : "Tệp"; }
    public String name() { return isFolder() ? folder.groupName() : file.fileName(); }
    public long size() { return isFolder() ? folder.totalSize() : file.fileSize(); }
    public long completedDownloads() { return isFolder() ? folder.completedDownloads() : file.completedDownloads(); }
    public long createdAtEpochMillis() { return isFolder() ? folder.createdAtEpochMillis() : file.createdAtEpochMillis(); }
    public int sourceCount() { return isFolder() ? folder.sourceCount() : file.sourceCount(); }
    public long id() { return isFolder() ? folder.groupId() : file.fileId(); }

    /** Same stable sort as server-side paging, merged across the two result types. */
    public static Comparator<DiscoveryEntry> comparator(DiscoverySort sort) {
        // The Tracker orders ties by descending ID except for DATE_OLDEST.
        // Matching source ordering is necessary for a correct streaming merge.
        Comparator<DiscoveryEntry> tieBreak = Comparator.comparing(DiscoveryEntry::type)
                .thenComparing((a, b) -> sort == DiscoverySort.DATE_OLDEST
                        ? Long.compare(a.id(), b.id()) : Long.compare(b.id(), a.id()));
        Comparator<DiscoveryEntry> main = switch (sort) {
            case POPULARITY -> Comparator.comparingLong(DiscoveryEntry::completedDownloads).reversed()
                    .thenComparing(Comparator.comparingLong(DiscoveryEntry::createdAtEpochMillis).reversed());
            case NAME_ASC -> Comparator.comparing(DiscoveryEntry::name, String.CASE_INSENSITIVE_ORDER);
            case NAME_DESC -> Comparator.comparing(DiscoveryEntry::name, String.CASE_INSENSITIVE_ORDER).reversed();
            case DATE_NEWEST -> Comparator.comparingLong(DiscoveryEntry::createdAtEpochMillis).reversed();
            case DATE_OLDEST -> Comparator.comparingLong(DiscoveryEntry::createdAtEpochMillis);
            case SIZE_ASC -> Comparator.comparingLong(DiscoveryEntry::size);
            case SIZE_DESC -> Comparator.comparingLong(DiscoveryEntry::size).reversed();
        };
        return main.thenComparing(tieBreak);
    }
}
