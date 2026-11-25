package vn.edu.p2p.peer.share;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;

/** Current share row. Share origins and times come from the local successful-share ledger. */
public record SharedFileEntry(long fileId, String fileName, String hash, long size,
                              Long directAt, boolean legacy, List<SharedFilesStore.Group> groups) {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    public SharedFileEntry { groups = groups == null ? List.of() : List.copyOf(groups); }
    public SharedFileEntry(long fileId, String fileName) { this(fileId, fileName, "", -1, null, true, List.of()); }
    public static SharedFileEntry describe(long id, String name, String hash, long size, List<SharedFilesStore.Entry> entries) {
        Long direct = null; boolean legacy = false; var groups = new LinkedHashMap<Long, SharedFilesStore.Group>();
        boolean found = false;
        for (var e : entries) if (e.fileHash().equalsIgnoreCase(hash)) {
            found = true;
            if (e.independent() == null) legacy = true;
            if (e.directAt() != null && (direct == null || e.directAt() > direct)) direct=e.directAt();
            for (var g:e.groups()) groups.put(g.groupId(),g);
        }
        return new SharedFileEntry(id,name,hash,size,direct,legacy || !found,List.copyOf(groups.values()));
    }
    String originText() {
        var parts = new java.util.ArrayList<String>();
        if (directAt != null) parts.add("Chia sẻ riêng · " + DATE.format(Instant.ofEpochMilli(directAt)));
        for (var g:groups) parts.add("Thư mục: " + g.name() + " · lượt #" + g.groupId() + " · " + DATE.format(Instant.ofEpochMilli(g.sharedAt())));
        if (legacy || parts.isEmpty()) parts.add("Chia sẻ cũ · chưa rõ lượt/ngày");
        return String.join("\n",parts);
    }
    String groupSortKey() { return groups.isEmpty()?"~"+fileName:groups.getFirst().name()+"#"+groups.getFirst().groupId(); }
    String sizeText() { return size<0?"":size>=1048576?String.format("%.2f MiB",size/1048576.0):size>=1024?String.format("%.1f KiB",size/1024.0):size+" B"; }
}
