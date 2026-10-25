package dev.cloudstorage.transfer;

import dev.cloudstorage.storage.ItemData;
import org.bukkit.inventory.ItemStack;

public final class Items {
    private Items() {}
    public static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() < 1; }
    public static ItemData encode(ItemStack original) {
        if (empty(original)) throw new IllegalArgumentException("空物品");
        ItemStack one = original.clone(); one.setAmount(1);
        return ItemData.of(one.getType().name(),one.serializeAsBytes());
    }
    public static ItemStack decode(ItemData item) { return ItemStack.deserializeBytes(item.bytes()); }
    public static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i=0;i<items.length;i++) copy[i] = empty(items[i]) ? null : items[i].clone();
        return copy;
    }
}
