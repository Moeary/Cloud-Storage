package dev.cloudstorage.ui;

import dev.cloudstorage.storage.ItemData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemNamesTest {
    @Test void chineseDictionaryAndCommonShortNameWorkTogether() {
        ItemNames names=new ItemNames();
        ItemData slab=ItemData.of("SMOOTH_STONE_SLAB",new byte[]{1});
        assertTrue(names.matches(slab,"平滑石"));
        assertTrue(names.matches(slab,"平滑石台阶"));
        assertTrue(names.exactChinese("SMOOTH_STONE_SLAB","平滑石头台阶"));
        assertTrue(names.exactChinese("SMOOTH_STONE_SLAB","平滑石台阶"));
        assertTrue(names.matches(slab,"MINECRAFT:SMOOTH_STONE_SLAB"));
        assertFalse(names.matches(slab,"漏斗"));
    }
}
