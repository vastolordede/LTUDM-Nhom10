package vn.edu.p2p.peer.share;

import java.util.List;

/** Mutate the existing list only for different entries; preserve stable identity for unchanged rows. */
final class DiscoveryRows {
    private DiscoveryRows(){}
    static boolean sameKey(DiscoveryEntry a, DiscoveryEntry b){return a.isFolder()==b.isFolder()&&a.id()==b.id();}
    static void reconcile(List<DiscoveryEntry> items,List<DiscoveryEntry> rows){
        record Key(boolean folder,long id) {}
        var keys=new java.util.HashSet<Key>();
        for(var row:rows)keys.add(new Key(row.isFolder(),row.id()));
        items.removeIf(row->!keys.contains(new Key(row.isFolder(),row.id())));
        for(int i=0;i<rows.size();i++){
            var desired=rows.get(i);
            if(i<items.size()&&sameKey(items.get(i),desired)){
                if(!items.get(i).equals(desired))items.set(i,desired);
                continue;
            }
            int found=-1;
            for(int j=i+1;j<items.size();j++)if(sameKey(items.get(j),desired)){found=j;break;}
            if(found>=0){var existing=items.remove(found);items.add(i,existing.equals(desired)?existing:desired);}
            else items.add(i,desired);
        }
        if(items.size()>rows.size())items.subList(rows.size(),items.size()).clear();
    }
}
