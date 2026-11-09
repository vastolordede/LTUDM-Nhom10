package vn.edu.p2p.peer.share;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Performs a two-way merge of the independently sorted Tracker File and Folder
 * pages. The old UI concatenated both pages (50+50), which can skip entries in
 * the global order when one category has more than 50 high-ranked results.
 */
final class MergedDiscoveryPager {
    @FunctionalInterface
    interface Fetcher {
        List<DiscoveryEntry> fetch(int page, int pageSize) throws Exception;
    }

    private static final int SOURCE_PAGE_SIZE = 50;
    private final Fetcher fileFetcher;
    private final Fetcher folderFetcher;
    private final Comparator<DiscoveryEntry> comparator;
    private final ArrayDeque<DiscoveryEntry> pendingFiles = new ArrayDeque<>();
    private final ArrayDeque<DiscoveryEntry> pendingFolders = new ArrayDeque<>();
    private int filePage;
    private int folderPage;
    private boolean moreFiles = true;
    private boolean moreFolders = true;

    MergedDiscoveryPager(Fetcher fileFetcher, Fetcher folderFetcher,
                         Comparator<DiscoveryEntry> comparator) {
        this.fileFetcher = Objects.requireNonNull(fileFetcher);
        this.folderFetcher = Objects.requireNonNull(folderFetcher);
        this.comparator = Objects.requireNonNull(comparator);
    }

    boolean hasMore() {
        return !pendingFiles.isEmpty() || !pendingFolders.isEmpty() || moreFiles || moreFolders;
    }

    /**
     * Return the next contiguous part of the global sort order. A head from
     * each non-exhausted source is required before the next item can be chosen.
     */
    List<DiscoveryEntry> nextPage(int limit) throws Exception {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid UI page size");
        List<DiscoveryEntry> result = new ArrayList<>(limit);
        while (result.size() < limit) {
            ensureHeads();
            DiscoveryEntry file = pendingFiles.peekFirst();
            DiscoveryEntry folder = pendingFolders.peekFirst();
            if (file == null && folder == null) break;
            if (folder == null || (file != null && comparator.compare(file, folder) <= 0)) {
                result.add(pendingFiles.removeFirst());
            } else {
                result.add(pendingFolders.removeFirst());
            }
        }
        return result;
    }

    private void ensureHeads() throws Exception {
        if (moreFiles && pendingFiles.isEmpty()) {
            List<DiscoveryEntry> rows = fileFetcher.fetch(filePage++, SOURCE_PAGE_SIZE);
            checkResponse(rows, false);
            pendingFiles.addAll(rows);
            moreFiles = rows.size() == SOURCE_PAGE_SIZE;
        }
        if (moreFolders && pendingFolders.isEmpty()) {
            List<DiscoveryEntry> rows = folderFetcher.fetch(folderPage++, SOURCE_PAGE_SIZE);
            checkResponse(rows, true);
            pendingFolders.addAll(rows);
            moreFolders = rows.size() == SOURCE_PAGE_SIZE;
        }
    }

    private void checkResponse(List<DiscoveryEntry> rows, boolean expectFolder) {
        if (rows == null || rows.size() > SOURCE_PAGE_SIZE) {
            throw new IllegalStateException("Unexpected Tracker discovery page");
        }
        for (DiscoveryEntry row : rows) {
            // The PostgreSQL locale may collate mixed-case names differently
            // from the JVM's case-insensitive comparator. Validate the shape,
            // but do not reject legitimate Tracker pages solely for collation.
            if (row == null || row.isFolder() != expectFolder) {
                throw new IllegalStateException("Tracker discovery source returned an invalid entry");
            }
        }
    }
}
