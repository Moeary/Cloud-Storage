package dev.cloudstorage.printer;

import dev.cloudstorage.CloudStoragePlugin;
import dev.cloudstorage.ui.CloudMenu;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.Set;

/**
 * AxShulkers 1.24.0 opens even cancelled clicks and has no per-item opening exclusion.
 * Decorate its Bukkit registrations, keeping the original owner, priority and cancellation
 * semantics. Ordinary events delegate unchanged; cloud shells/views never reach Ax code.
 * No Ax database, config, command executor or item format is changed.
 */
public final class AxShulkersIsolation implements Listener {
    private static final String PREFIX="com.artillexstudios.axshulkers.listeners.impl.";
    private static final Set<String> LISTENERS=Set.of("ShulkerOpenListener","BlockPlaceListener",
            "InventoryClickListener","CreativeClickListener","CommandListener","PlayerDropItemListener",
            "BlockDispenseListener","EntityDeathListener","PlayerMoveListener","PlayerInteractListener");
    private final CloudStoragePlugin plugin;
    private Plugin isolated;
    private boolean supported;

    public AxShulkersIsolation(CloudStoragePlugin plugin) { this.plugin=plugin; }

    @EventHandler(priority=EventPriority.MONITOR)
    public void enabled(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("AxShulkers")) install(event.getPlugin());
    }

    public void installExisting() {
        Plugin ax=plugin.getServer().getPluginManager().getPlugin("AxShulkers");
        if (ax!=null && ax.isEnabled()) install(ax);
    }

    private void install(Plugin ax) {
        isolated=ax;
        supported=ax.getPluginMeta().getVersion().equals("1.24.0")
                && ax.getClass().getName().equals("com.artillexstudios.axshulkers.AxShulkers");
        int count=0;
        Set<String> observed=new java.util.HashSet<>();
        for (HandlerList handlers:HandlerList.getHandlerLists()) {
            for (RegisteredListener original:handlers.getRegisteredListeners()) {
                if (original.getPlugin()!=ax || original instanceof IsolatedListener) continue;
                String name=original.getListener().getClass().getName();
                observed.add(name);
                if (!name.equals("com.artillexstudios.axshulkers.utils.UpdateNotifier")
                        && (!name.startsWith(PREFIX) || !LISTENERS.contains(name.substring(PREFIX.length())))) supported=false;
                handlers.unregister(original);
                handlers.register(new IsolatedListener(original));
                count++;
            }
        }
        for (String name:LISTENERS) if (!observed.contains(PREFIX+name)) supported=false;
        if (!supported) plugin.getLogger().warning("Unrecognized AxShulkers listener profile: "+observed);
        plugin.getLogger().info("AxShulkers isolation: version="+ax.getPluginMeta().getVersion()
                +" listeners="+count+" supported="+supported+"; cloud tokens/views excluded, ordinary shulkers delegated");
    }

    /** Unknown versions stay guard-only. A late unwrapped registration also closes cloud access. */
    public boolean available() {
        Plugin ax=plugin.getServer().getPluginManager().getPlugin("AxShulkers");
        if (ax==null || !ax.isEnabled()) return true;
        if (ax!=isolated || !supported) return false;
        for (RegisteredListener listener:HandlerList.getRegisteredListeners(ax))
            if (!(listener instanceof IsolatedListener)) return false;
        return true;
    }

    private boolean token(ItemStack item) { return plugin.shulkers().isToken(item); }
    private boolean cloudView(Player player) { return player.getOpenInventory().getTopInventory().getHolder() instanceof CloudMenu.View; }

    private boolean ownsEvent(Event event) {
        if (event instanceof InventoryEvent inventory && inventory.getInventory().getHolder() instanceof CloudMenu.View) return true;
        if (event instanceof InventoryClickEvent click) {
            if (token(click.getCurrentItem()) || token(click.getCursor())) return true;
            if (click.getWhoClicked() instanceof Player player) {
                if (click.getHotbarButton()>=0 && token(player.getInventory().getItem(click.getHotbarButton()))) return true;
                if (click.getClick()==ClickType.SWAP_OFFHAND && token(player.getInventory().getItemInOffHand())) return true;
            }
        }
        if (event instanceof InventoryDragEvent drag) return token(drag.getOldCursor()) || token(drag.getCursor());
        if (event instanceof PlayerInteractEvent interact) return token(interact.getItem()) || cloudView(interact.getPlayer());
        if (event instanceof PlayerDropItemEvent drop) return token(drop.getItemDrop().getItemStack());
        if (event instanceof BlockPlaceEvent place) return token(place.getItemInHand());
        if (event instanceof BlockDispenseEvent dispense) return token(dispense.getItem());
        if (event instanceof EntityDamageEvent damage && damage.getEntity() instanceof Item item) return token(item.getItemStack());
        if (event instanceof PlayerCommandPreprocessEvent command) return cloudView(command.getPlayer());
        if (event instanceof PlayerMoveEvent move) return cloudView(move.getPlayer());
        if (event instanceof PlayerInteractEntityEvent interact) return cloudView(interact.getPlayer());
        return false;
    }

    private final class IsolatedListener extends RegisteredListener {
        private final RegisteredListener original;
        IsolatedListener(RegisteredListener original) {
            super(original.getListener(),(listener,event) -> {},original.getPriority(),original.getPlugin(),original.isIgnoringCancelled());
            this.original=original;
        }
        @Override public void callEvent(Event event) throws EventException {
            if (!ownsEvent(event)) original.callEvent(event);
        }
    }
}
