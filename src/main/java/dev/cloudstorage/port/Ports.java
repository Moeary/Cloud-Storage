package dev.cloudstorage.port;

import dev.cloudstorage.*;
import dev.cloudstorage.storage.*;
import dev.cloudstorage.transfer.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Only single barrels placed/claimed by their owner; no inferred ownership from nearby containers. */
public final class Ports implements Listener, Runnable {
    private final CloudStoragePlugin plugin;
    private final NamespacedKey placedBy;
    private int cursor;
    private boolean paused;
    public Ports(CloudStoragePlugin plugin) { this.plugin=plugin; placedBy=new NamespacedKey(plugin,"placed_by"); }
    public void pause() { paused=true; }
    public PortLimits limits() {
        return new PortLimits(plugin.getConfig().getInt("ports.max-inputs-per-player",5),plugin.getConfig().getInt("ports.max-outputs-per-player",5));
    }

    private String id(Block block) { return block.getWorld().getUID()+":"+block.getX()+":"+block.getY()+":"+block.getZ(); }
    private Optional<Ledger.Port> find(Block block) { return plugin.ledger().ports().stream().filter(p -> p.id().equals(id(block))).findFirst(); }

    public void bind(Player player,String mode) {
        if (!plugin.getConfig().getBoolean("ports.enabled") || !player.hasPermission("cloudstorage.port")) throw new StorageException("接口未启用或缺少权限");
        if (!Set.of("input","output").contains(mode)) throw new StorageException("接口模式须为 input 或 output");
        Block block=player.getTargetBlockExact(5);
        if (block==null || !(block.getState() instanceof Barrel barrel)) throw new StorageException("请对准 5 格内自己放置的木桶");
        if (find(block).isPresent()) throw new StorageException("此木桶已绑定，请先解绑");
        if (!player.getUniqueId().toString().equals(barrel.getPersistentDataContainer().get(placedBy,PersistentDataType.STRING)))
            throw new StorageException("只能绑定本插件启用后由自己放置的木桶");
        if (!barrel.getInventory().getViewers().isEmpty()) throw new StorageException("请先关闭木桶窗口");
        limits().requireRoom(plugin.ledger().ports(),player.getUniqueId().toString(),mode);
        String filter=null;
        if (mode.equals("output")) {
            ItemStack held=player.getInventory().getItemInMainHand();
            if (Items.empty(held) || plugin.forbidden(held)) throw new StorageException("输出接口须手持允许出库的物品样本");
            filter=Items.encode(held).key();
        }
        plugin.ledger().bind(new Ledger.Port(block.getWorld().getUID().toString(),block.getX(),block.getY(),block.getZ(),
                player.getUniqueId().toString(),plugin.access().selected(player),mode,filter));
    }

    public void unbind(Player player) {
        Block block=player.getTargetBlockExact(5);
        if (block==null) throw new StorageException("请对准接口木桶");
        Ledger.Port port=find(block).orElseThrow(() -> new StorageException("不是接口"));
        if (!port.owner().equals(player.getUniqueId().toString()) && !player.hasPermission("cloudstorage.admin")) throw new StorageException("接口不属于此 UUID");
        plugin.ledger().unbind(port);
    }

    @Override public void run() {
        if (paused || plugin.ledger().frozen() || !plugin.getConfig().getBoolean("ports.enabled")) return;
        List<Ledger.Port> ports=plugin.ledger().ports();
        if (ports.isEmpty()) return;
        Ledger.Port port=ports.get(Math.floorMod(cursor++,ports.size()));
        try {
            World world=Bukkit.getWorld(UUID.fromString(port.world()));
            if (world==null || !world.isChunkLoaded(port.x()>>4,port.z()>>4)) return;
            Player owner=Bukkit.getPlayer(UUID.fromString(port.owner()));
            // Require an authenticated online owner and current permissions for all automation.
            if (owner==null || !owner.hasPermission("cloudstorage.port")) return;
            plugin.access().require(owner,port.vault());
            if (!(world.getBlockAt(port.x(),port.y(),port.z()).getState() instanceof Barrel barrel)) return;
            if (!port.owner().equals(barrel.getPersistentDataContainer().get(placedBy,PersistentDataType.STRING))) return;
            Inventory inventory=barrel.getInventory();
            if (!inventory.getViewers().isEmpty()) return;
            Transfers.Target target=new Transfers.Target(inventory,"port:"+port.id(),world::save);
            int count=Math.max(1,Math.min(64,plugin.getConfig().getInt("ports.items-per-transfer",64)));
            if (port.mode().equals("input")) {
                for (int i=0;i<inventory.getSize();i++) {
                    if (!Items.empty(inventory.getItem(i)) && !plugin.forbidden(inventory.getItem(i))) {
                        plugin.transfers().deposit(port.vault(),target,i,count); break;
                    }
                }
            } else {
                plugin.ledger().item(port.filter()).ifPresent(data -> plugin.transfers().withdraw(port.vault(),target,data,count));
            }
        } catch (StorageException e) {
            // Full/empty inventories and revoked permissions are expected. SQL failures freeze in the ledger.
            if (plugin.ledger().frozen()) plugin.getLogger().severe("World port paused: "+e.getMessage());
        } catch (RuntimeException e) {
            plugin.ledger().freeze(); plugin.getLogger().log(java.util.logging.Level.SEVERE,"Unexpected port failure; cloud frozen",e);
        }
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void placed(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof TileState tile)) return;
        tile.getPersistentDataContainer().set(placedBy,PersistentDataType.STRING,event.getPlayer().getUniqueId().toString());
        tile.update();
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void broken(BlockBreakEvent event) {
        if (event.getBlock().getType()==Material.BARREL && find(event.getBlock()).isPresent()) {
            event.setCancelled(true); plugin.message(event.getPlayer(),"请先 /cs port unbind，防止接口在转移中被替换");
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.getClickedBlock()==null || event.getClickedBlock().getType()!=Material.BARREL) return;
        find(event.getClickedBlock()).ifPresent(port -> {
            if (!port.owner().equals(event.getPlayer().getUniqueId().toString()) && !event.getPlayer().hasPermission("cloudstorage.admin")) {
                event.setCancelled(true); plugin.message(event.getPlayer(),"接口属于其他 UUID");
            }
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void open(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Barrel barrel)) return;
        find(barrel.getBlock()).ifPresent(port -> {
            if (!port.owner().equals(event.getPlayer().getUniqueId().toString()) && !event.getPlayer().hasPermission("cloudstorage.admin")) event.setCancelled(true);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void hopper(InventoryMoveItemEvent event) {
        checkHopper(event,event.getSource(),event.getDestination());
        checkHopper(event,event.getDestination(),event.getSource());
    }
    private void checkHopper(InventoryMoveItemEvent event,Inventory inventory,Inventory other) {
        if (!(inventory.getHolder() instanceof Barrel barrel)) return;
        find(barrel.getBlock()).ifPresent(port -> {
            // Automation on private ports must use an adjacent tile placed by the same UUID.
            // Minecart hoppers and unowned/double inventories do not prove ownership.
            if (port.vault().equals("shared")) return;
            if (!(other.getHolder() instanceof TileState tile)
                    || !port.owner().equals(tile.getPersistentDataContainer().get(placedBy,PersistentDataType.STRING))) event.setCancelled(true);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explosion(EntityExplodeEvent event) { event.blockList().removeIf(b -> b.getType()==Material.BARREL && find(b).isPresent()); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void explosion(BlockExplodeEvent event) { event.blockList().removeIf(b -> b.getType()==Material.BARREL && find(b).isPresent()); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void piston(BlockPistonExtendEvent event) { if (event.getBlocks().stream().anyMatch(b -> b.getType()==Material.BARREL && find(b).isPresent())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void piston(BlockPistonRetractEvent event) { if (event.getBlocks().stream().anyMatch(b -> b.getType()==Material.BARREL && find(b).isPresent())) event.setCancelled(true); }
}
