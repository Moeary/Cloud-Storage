package dev.cloudstorage.transfer;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** New deposits accept ordinary stackable goods, never equipment or data-bearing variants. */
public final class StoragePolicy {
    private StoragePolicy() {}
    public static boolean forbiddenDeposit(ItemStack item) {
        return Items.empty(item) || item.getType().getMaxStackSize()<=1
                || item.getType().name().contains("SHULKER_BOX") || item.getType()==Material.PLAYER_HEAD
                || item.hasItemMeta();
    }
}
