package dev.cloudstorage.printer;

import dev.cloudstorage.storage.Ledger;
import java.util.*;

/** Discovery only: leave one real window slot empty for the legacy printer's return path. */
public final class CatalogPlanner {
    public static final int SIZE=26;
    private CatalogPlanner() {}
    public static List<Ledger.Entry> select(List<Ledger.Entry> items,List<String> filter,List<String> preferred,int page,boolean automatic) {
        if (!filter.isEmpty()) return items.stream().filter(e -> filter.contains(e.item().material())).limit(SIZE).toList();
        if (!automatic) return items.stream().skip((long)Math.max(0,page)*SIZE).limit(SIZE).toList();
        List<Ledger.Entry> fixed=items.stream().filter(e -> preferred.contains(e.item().material())).limit(8).toList();
        Set<String> keys=new HashSet<>(); fixed.forEach(e -> keys.add(e.item().key()));
        List<Ledger.Entry> rotating=items.stream().filter(e -> !keys.contains(e.item().key())).toList();
        int room=SIZE-fixed.size();
        int pages=Math.max(1,(rotating.size()+room-1)/room);
        List<Ledger.Entry> result=new ArrayList<>(fixed);
        rotating.stream().skip((long)Math.floorMod(page,pages)*room).limit(room).forEach(result::add);
        return List.copyOf(result);
    }
}
