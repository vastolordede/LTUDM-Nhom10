package vn.edu.p2p.peer.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Local history of confirmed user share operations. Never used to restore sharing rights. */
final class ShareHistoryStore {
    public record Item(String name,long size) {}
    public record Batch(String id,long sharedAt,String title,List<Item> items) {
        public Batch { items=List.copyOf(items); }
    }
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Object LOCK=new Object();
    private final Path path;
    ShareHistoryStore(Path path) { this.path=path; }
    List<Batch> load() throws IOException {
        synchronized(LOCK) {
            if(!Files.exists(path)) return List.of();
            if(Files.isSymbolicLink(path)||!Files.isRegularFile(path)) throw new IOException("Unsafe history path");
            return List.of(JSON.readValue(path.toFile(),Batch[].class));
        }
    }
    void append(long time,String title,List<Item> items) throws IOException {
        synchronized(LOCK) {
            List<Batch> batches=new ArrayList<>(load());
            batches.add(new Batch(UUID.randomUUID().toString(),time,title,items));
            Path dir=path.toAbsolutePath().getParent(); Files.createDirectories(dir);
            if(Files.isSymbolicLink(dir))throw new IOException("Unsafe history directory");
            Path tmp=Files.createTempFile(dir,".share-history-",".tmp");
            try {
                JSON.writeValue(tmp.toFile(),batches);
                try { Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
            } finally { Files.deleteIfExists(tmp); }
        }
    }
}
