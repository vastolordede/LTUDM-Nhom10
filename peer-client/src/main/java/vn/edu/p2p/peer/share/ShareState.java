package vn.edu.p2p.peer.share;

import javafx.collections.ObservableList;
import vn.edu.p2p.common.dto.FolderUnshareResponse;
import vn.edu.p2p.peer.transfer.LocalFileRegistry;
import java.util.*;

final class ShareState {
    private ShareState() {}
    static List<SharedFilesStore.Entry> read(SharedFilesStore store) { return store == null ? List.of() : store.load(); }
    static String stopFile(LocalFileRegistry registry, SharedFilesStore store, long id) {
        if (registry == null) return "";
        registry.disableSharing(id);
        try {
            if (store != null) {
                var file=registry.find(id);
                if (file.isPresent()) store.removeByHash(file.get().metadata().fileHash());
            }
            return "";
        } catch (Exception e) { return " Không lưu được danh sách cục bộ; Tracker đã ngừng chia sẻ."; }
    }
    static String stopFolder(LocalFileRegistry registry, SharedFilesStore store, FolderUnshareResponse result) {
        var hashes=new HashSet<String>();
        for (long id:result.stoppedFileIds()) {
            if(registry!=null) {
                registry.disableSharing(id);
                registry.find(id).ifPresent(f->hashes.add(f.metadata().fileHash().toLowerCase(Locale.ROOT)));
            }
        }
        try { if(store!=null) store.removeGroup(result.groupId(),hashes); return ""; }
        catch(Exception e){return " Không lưu được danh sách cục bộ; Tracker đã ngừng chia sẻ.";}
    }
    static void updateFolderRows(ObservableList<SharedFileEntry> rows, FolderUnshareResponse result) {
        if(rows==null)return;
        var stopped=new HashSet<>(result.stoppedFileIds());
        List<SharedFileEntry> kept=new ArrayList<>();
        for(var e:rows) if(!stopped.contains(e.fileId())) kept.add(new SharedFileEntry(e.fileId(),e.fileName(),e.hash(),e.size(),e.directAt(),e.legacy(),
                e.groups().stream().filter(g->g.groupId()!=result.groupId()).toList()));
        rows.setAll(kept);
    }
}
