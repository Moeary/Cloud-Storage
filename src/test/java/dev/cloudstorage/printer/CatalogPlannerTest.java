package dev.cloudstorage.printer;

import dev.cloudstorage.storage.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPlannerTest {
    private Ledger.Entry item(String name) { return new Ledger.Entry(ItemData.of(name,name.getBytes(StandardCharsets.UTF_8)),100000); }

    @Test void automaticRotationEventuallyAdvertisesEveryMaterialWithoutFillingReturnSlot() {
        List<Ledger.Entry> all=new ArrayList<>();
        for (int i=0;i<100;i++) all.add(item("BLOCK_"+i));
        Set<String> advertised=new HashSet<>();
        for (int page=0;page<4;page++) {
            List<Ledger.Entry> selected=CatalogPlanner.select(all,List.of(),List.of(),page,true);
            assertTrue(selected.size()<=26);
            selected.forEach(e -> advertised.add(e.item().key()));
        }
        assertEquals(100,advertised.size());
        assertEquals(CatalogPlanner.select(all,List.of(),List.of(),0,true),CatalogPlanner.select(all,List.of(),List.of(),4,true));
    }

    @Test void preferredMaterialsRemainAvailableOnEveryPageAndOthersStillRotate() {
        List<Ledger.Entry> all=new ArrayList<>();
        for (int i=0;i<80;i++) all.add(item("BLOCK_"+i));
        Ledger.Entry smooth=item("SMOOTH_STONE"); all.add(smooth);
        Set<String> union=new HashSet<>();
        for (int page=0;page<4;page++) {
            List<Ledger.Entry> selected=CatalogPlanner.select(all,List.of(),List.of("SMOOTH_STONE"),page,true);
            assertTrue(selected.contains(smooth));
            selected.forEach(e -> union.add(e.item().key()));
        }
        assertEquals(81,union.size());
    }

    @Test void explicitMaterialSelectionIsStableAndDoesNotExposeOtherItems() {
        List<Ledger.Entry> all=List.of(item("ACACIA_PLANKS"),item("SMOOTH_STONE"),item("SMOOTH_STONE_SLAB"));
        List<Ledger.Entry> selected=CatalogPlanner.select(all,List.of("SMOOTH_STONE","SMOOTH_STONE_SLAB"),List.of(),100,true);
        assertEquals(List.of(all.get(1),all.get(2)),selected);
        assertEquals(100000,selected.getFirst().amount());
    }

    @Test void emptyAndManualCatalogsStayBounded() {
        assertTrue(CatalogPlanner.select(List.of(),List.of(),List.of(),7,true).isEmpty());
        List<Ledger.Entry> all=new ArrayList<>(); for (int i=0;i<30;i++) all.add(item("BLOCK_"+i));
        assertEquals(4,CatalogPlanner.select(all,List.of(),List.of(),1,false).size());
        assertTrue(CatalogPlanner.select(all,List.of(),List.of(),2,false).isEmpty());
    }
}
