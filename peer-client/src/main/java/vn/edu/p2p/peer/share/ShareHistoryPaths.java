package vn.edu.p2p.peer.share;

import java.util.*;

/** Pure relative-path helpers for display only; never resolves paths or touches the filesystem. */
final class ShareHistoryPaths {
    private ShareHistoryPaths(){}
    static String normalize(String path){return path.replace('\\','/').replaceAll("/+$", "");}
    static List<String> parts(String path){return Arrays.stream(normalize(path).split("/")).filter(s->!s.isEmpty()).toList();}
    static List<String> emptyLeafDirectories(List<String> directories,List<String> files){
        var dirs=directories.stream().map(ShareHistoryPaths::normalize).filter(s->!s.isEmpty()).distinct().toList();
        var paths=files.stream().map(ShareHistoryPaths::normalize).toList();
        return dirs.stream().filter(d->paths.stream().noneMatch(f->f.startsWith(d+"/"))&&dirs.stream().noneMatch(x->x.startsWith(d+"/"))).toList();
    }
}
