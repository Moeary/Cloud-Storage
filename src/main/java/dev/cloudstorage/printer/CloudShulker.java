package dev.cloudstorage.printer;

import dev.cloudstorage.*;
import dev.cloudstorage.storage.*;
import dev.cloudstorage.transfer.*;
import dev.cloudstorage.ui.CloudMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** The block-state inventory is a discovery index, never an authority or physical inventory. */
public final class CloudShulker implements Listener {
    private final CloudStoragePlugin plugin;
    private final NamespacedKey ownerKey,idKey,vaultKey,pageKey,filterKey,versionKey;
    private final Set<UUID> opening=new HashSet<>();
    private final Map<UUID,List<String>> demands=new HashMap<>();
    private final Map<UUID,Integer> demandTicks=new HashMap<>();

    /** Fabric string codec: bounded VarInt length followed by UTF-8. No inventory data is trusted. */
    public void payload(Player player,byte[] bytes) {
        if (!Bukkit.isPrimaryThread()) { Bukkit.getScheduler().runTask(plugin,() -> payload(player,bytes.clone())); return; }
        if (!player.isOnline() || bytes.length>515) return;
        try {
            int length=0,offset=0;
            for (int shift=0;shift<=14;shift+=7) {
                if (offset>=bytes.length) return;
                int part=bytes[offset++]&255;
                length|=(part&127)<<shift;
                if ((part&128)==0) break;
                if (shift==14) return;
            }
            if (length<1 || length>512 || offset+length!=bytes.length) return;
            String raw=new String(bytes,offset,length,java.nio.charset.StandardCharsets.UTF_8);
            List<String> materials=new ArrayList<>();
            for (String name:raw.split(",",-1)) {
                Material material=Material.matchMaterial(name);
                if (material==null || !material.isItem() || !material.isBlock()) return;
                if (!materials.contains(material.name())) materials.add(material.name());
                if (materials.size()>8) return;
            }
            demand(player,materials);
        } catch (StorageException ignored) { /* Revoked access or unavailable storage never transfers stock. */ }
    }

    /** Select a discovery entry only. Inventory and ledger mutations still require validated clicks. */
    public void demand(Player player,List<String> materials) {
        if (!plugin.ready()) throw new StorageException("云仓暂不可用");
        if (!player.hasPermission("cloudstorage.printer") || !plugin.getConfig().getBoolean("printer.enabled")) throw new StorageException("云潜影盒未启用或缺少权限");
        ItemStack owned=null;
        for (ItemStack item:player.getInventory().getStorageContents()) if (owns(player,item)) { owned=item; break; }
        if (owned==null) return;
        plugin.access().require(player,Objects.requireNonNull(owned.getItemMeta().getPersistentDataContainer().get(vaultKey,PersistentDataType.STRING)));
        if (materials.isEmpty() || materials.size()>8) throw new StorageException("按需目录须指定 1..8 种候选材料");
        int tick=Bukkit.getCurrentTick();
        Integer previous=demandTicks.get(player.getUniqueId());
        if (previous!=null && tick-previous<2) return;
        demandTicks.put(player.getUniqueId(),tick);
        demands.put(player.getUniqueId(),List.copyOf(materials));
        refresh(player);
    }

    private boolean conflictingOpener() { return plugin.getServer().getPluginManager().getPlugin("AxShulkers")!=null; }

    public CloudShulker(CloudStoragePlugin plugin) {
        this.plugin=plugin;
        ownerKey=new NamespacedKey(plugin,"owner"); idKey=new NamespacedKey(plugin,"token");
        vaultKey=new NamespacedKey(plugin,"vault"); pageKey=new NamespacedKey(plugin,"page");
        filterKey=new NamespacedKey(plugin,"materials"); versionKey=new NamespacedKey(plugin,"catalog_version");
    }

    public boolean isToken(ItemStack item) {
        return !Items.empty(item) && item.hasItemMeta() && (item.getItemMeta().getPersistentDataContainer().has(idKey)
                || item.getItemMeta().getPersistentDataContainer().has(ownerKey));
    }

    private boolean owns(Player player,ItemStack item) {
        return isToken(item) && item.getAmount()==1 && player.getUniqueId().toString().equals(
                item.getItemMeta().getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING));
    }

    /** Remove only this player's discovery shells; their displayed contents never enter the ledger. */
    public int remove(Player player) {
        opening.remove(player.getUniqueId());
        demands.remove(player.getUniqueId()); demandTicks.remove(player.getUniqueId());
        int removed=0;
        for (int slot=0;slot<player.getInventory().getSize();slot++) {
            ItemStack item=player.getInventory().getItem(slot);
            if (!ownedShell(player,item)) continue;
            removed+=item.getAmount();
            player.getInventory().setItem(slot,null);
        }
        if (ownedShell(player,player.getItemOnCursor())) {
            removed+=player.getItemOnCursor().getAmount();
            player.setItemOnCursor(null);
        }
        // Free shell slots first so Paper can return any already-withdrawn real cursor stack.
        if (CloudMenu.isPrinterView(player)) player.closeInventory();
        player.saveData();
        player.updateInventory();
        return removed;
    }

    private boolean ownedShell(Player player,ItemStack item) {
        return isToken(item) && player.getUniqueId().toString().equals(
                item.getItemMeta().getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING));
    }

    public void give(Player player,int page) {
        give(player,page,List.of());
    }

    public void give(Player player,int page,List<String> materials) {
        if (conflictingOpener()) throw new StorageException("检测到 AxShulkers；请勿让其他开盒插件处理云目录，移除冲突插件后重启");
        if (!plugin.getConfig().getBoolean("printer.enabled") || !player.hasPermission("cloudstorage.printer")) throw new StorageException("云潜影盒未启用或缺少权限");
        if (page< -1 || page>157) throw new StorageException("页码须为 1..158，或使用 auto");
        if (materials.size()>CatalogPlanner.SIZE) throw new StorageException("指定目录最多 26 种材料");
        String vault=plugin.access().selected(player);
        if (!Items.empty(player.getItemOnCursor())) throw new StorageException("请先清空鼠标持物");
        player.closeInventory(); demands.remove(player.getUniqueId());
        // Reuse a token owned by this player, including changing its catalog page/vault.
        for (int i=0;i<36;i++) {
            ItemStack current=player.getInventory().getItem(i);
            if (!owns(player,current)) continue;
            ItemMeta meta=current.getItemMeta();
            meta.getPersistentDataContainer().set(vaultKey,PersistentDataType.STRING,vault);
            meta.getPersistentDataContainer().set(pageKey,PersistentDataType.INTEGER,page);
            meta.getPersistentDataContainer().set(filterKey,PersistentDataType.STRING,String.join(",",materials));
            meta.getPersistentDataContainer().set(versionKey,PersistentDataType.INTEGER,3);
            current.setItemMeta(meta); refresh(player,current); player.getInventory().setItem(i,current);
            ensureMainStorage(player); player.saveData(); return;
        }
        int destination=emptyMainSlot(player);
        if (destination<0) throw new StorageException("主背包（快捷栏上方三排）需要一个空位");
        ItemStack token=new ItemStack(Material.PURPLE_SHULKER_BOX);
        BlockStateMeta meta=(BlockStateMeta)token.getItemMeta();
        meta.displayName(Component.text("云潜影盒 · "+player.getName()));
        meta.lore(List.of(Component.text("UUID 绑定 · 主背包右键开盒"),Component.text("按需取料 · /cs shulker <材料...> 固定材料"),Component.text("目录不是实体物品 · 禁止放置或转交")));
        meta.getPersistentDataContainer().set(ownerKey,PersistentDataType.STRING,player.getUniqueId().toString());
        meta.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,UUID.randomUUID().toString());
        meta.getPersistentDataContainer().set(vaultKey,PersistentDataType.STRING,vault);
        meta.getPersistentDataContainer().set(pageKey,PersistentDataType.INTEGER,page);
        meta.getPersistentDataContainer().set(filterKey,PersistentDataType.STRING,String.join(",",materials));
        meta.getPersistentDataContainer().set(versionKey,PersistentDataType.INTEGER,3);
        token.setItemMeta(meta); refresh(player,token);
        player.getInventory().setItem(destination,token); player.saveData();
    }

    private int emptyMainSlot(Player player) {
        for (int slot=9;slot<36;slot++) if (Items.empty(player.getInventory().getItem(slot))) return slot;
        return -1;
    }

    private void ensureMainStorage(Player player) {
        if (player.getOpenInventory().getTopInventory().getType()!=InventoryType.CRAFTING || !Items.empty(player.getItemOnCursor())) return;
        for (int slot=0;slot<9;slot++) {
            ItemStack token=player.getInventory().getItem(slot);
            if (!owns(player,token)) continue;
            int destination=emptyMainSlot(player);
            if (destination<0) return;
            player.getInventory().setItem(destination,token.clone()); player.getInventory().setItem(slot,null);
            player.saveData();
            plugin.message(player,"云潜影盒已移入主背包：三改打印机会跳过快捷栏");
        }
    }

    private void refresh(Player player,ItemStack token) {
        if (!(token.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof ShulkerBox box)) return;
        box.getInventory().clear();
        try {
            if (!plugin.ready() || conflictingOpener() || !owns(player,token) || !player.hasPermission("cloudstorage.printer") || !plugin.getConfig().getBoolean("printer.enabled")) {
                meta.setBlockState(box); token.setItemMeta(meta); return;
            }
            String vault=meta.getPersistentDataContainer().get(vaultKey,PersistentDataType.STRING);
            plugin.access().require(player,Objects.requireNonNull(vault));
            meta.getPersistentDataContainer().set(versionKey,PersistentDataType.INTEGER,3);
            int page=meta.getPersistentDataContainer().getOrDefault(pageKey,PersistentDataType.INTEGER,-1);
            String raw=meta.getPersistentDataContainer().getOrDefault(filterKey,PersistentDataType.STRING,"");
            List<String> filter=raw.isBlank()?List.of():List.of(raw.split(","));
            List<Ledger.Entry> entries;
            if (page<0 && filter.isEmpty()) {
                entries=new ArrayList<>();
                for (String name:demands.getOrDefault(player.getUniqueId(),List.of())) {
                    Material material=Material.matchMaterial(name);
                    if (material==null || !material.isItem() || !material.isBlock()) continue;
                    ItemStack plain=new ItemStack(material);
                    if (plugin.forbidden(plain)) continue;
                    ItemData data=Items.encode(plain);
                    long amount=plugin.ledger().amount(vault,data.key());
                    if (amount>0) { entries.add(new Ledger.Entry(data,amount)); break; }
                }
            } else entries=CatalogPlanner.select(plugin.menus().printableAll(vault),filter,
                    List.of(),Math.max(0,page),false);
            for (int i=0;i<entries.size();i++) {
                ItemStack shown=Items.decode(entries.get(i).item());
                shown.setAmount((int)Math.min(entries.get(i).amount(),shown.getMaxStackSize()));
                box.getInventory().setItem(i,shown);
            }
        } catch (StorageException | NullPointerException ignored) { /* Revoked access clears the index. */ }
        meta.setBlockState(box); token.setItemMeta(meta);
    }

    public void refresh(Player player) {
        if (CloudMenu.isPrinterView(player) || opening.contains(player.getUniqueId())) return;
        for (int i=0;i<player.getInventory().getSize();i++) {
            ItemStack token=player.getInventory().getItem(i);
            if (!isToken(token)) continue;
            ItemStack copy=token.clone(); refresh(player,copy);
            if (!copy.equals(token)) player.getInventory().setItem(i,copy);
        }
    }

    private void openLater(Player player,int slot) {
        if (!opening.add(player.getUniqueId())) return;
        ItemStack selected=player.getInventory().getItem(slot);
        if (!isToken(selected)) { opening.remove(player.getUniqueId()); return; }
        String id=selected.getItemMeta().getPersistentDataContainer().get(idKey,PersistentDataType.STRING);
        List<Ledger.Entry> snapshot=new ArrayList<>();
        if (selected.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            String vault=meta.getPersistentDataContainer().get(vaultKey,PersistentDataType.STRING);
            for (ItemStack shown:box.getInventory().getContents()) {
                if (Items.empty(shown)) continue;
                ItemData data=Items.encode(shown);
                long amount=plugin.ready()?plugin.ledger().amount(vault,data.key()):0;
                if (amount>0) snapshot.add(new Ledger.Entry(data,Math.min(amount,shown.getAmount())));
            }
        }
        Bukkit.getScheduler().runTask(plugin,() -> {
            opening.remove(player.getUniqueId());
            try {
                ItemStack now=player.getInventory().getItem(slot);
                if (!player.isOnline() || !owns(player,now) || !Items.empty(player.getItemOnCursor())) return;
                if (!Objects.equals(id,now.getItemMeta().getPersistentDataContainer().get(idKey,PersistentDataType.STRING))) return;
                if (!plugin.ready()) throw new StorageException("云仓不可用，保护模式已阻止原版开盒");
                if (conflictingOpener()) throw new StorageException("其他开盒插件与云目录冲突，已阻止云开盒");
                if (!plugin.getConfig().getBoolean("printer.enabled") || !player.hasPermission("cloudstorage.printer")) throw new StorageException("无云潜影盒使用权限");
                String vault=now.getItemMeta().getPersistentDataContainer().get(vaultKey,PersistentDataType.STRING);
                plugin.access().require(player,Objects.requireNonNull(vault));
                plugin.menus().openPrinter(player,vault,snapshot);
            } catch (RuntimeException e) { plugin.report(player,e); }
        });
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack hotbar=event.getHotbarButton()>=0 ? player.getInventory().getItem(event.getHotbarButton()) : null;
        boolean involved=isToken(event.getCurrentItem()) || isToken(event.getCursor()) || isToken(hotbar)
                || (event.getClick()==ClickType.SWAP_OFFHAND && isToken(player.getInventory().getItemInOffHand()));
        if (!involved) return;
        if (event.getView().getTopInventory().getHolder() instanceof CloudMenu.View) { event.setCancelled(true); return; }
        boolean wasCancelled=event.isCancelled();
        InventoryType top=event.getView().getTopInventory().getType();
        boolean own=top==InventoryType.CRAFTING && event.getClickedInventory()==player.getInventory() && event.getSlot()>=0 && event.getSlot()<36;
        // Only normal movement within the player's storage slots is safe.
        boolean move=own && (event.getClick()==ClickType.LEFT || event.isShiftClick()) && player.getGameMode()!=GameMode.CREATIVE
                && (!isToken(event.getCurrentItem()) || owns(player,event.getCurrentItem()))
                && (!isToken(event.getCursor()) || owns(player,event.getCursor()));
        if (move) return;
        event.setCancelled(true);
        if (!wasCancelled && own && event.getClick()==ClickType.RIGHT && Items.empty(event.getCursor()) && owns(player,event.getCurrentItem()))
            openLater(player,event.getSlot());
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void preventOtherOpener(InventoryClickEvent event) {
        if (isToken(event.getCurrentItem()) && event.getClick()==ClickType.RIGHT) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void interact(PlayerInteractEvent event) {
        if (!isToken(event.getItem())) return;
        event.setCancelled(true);
        if ((event.getAction()==Action.RIGHT_CLICK_AIR || event.getAction()==Action.RIGHT_CLICK_BLOCK)
                && event.getHand()==EquipmentSlot.HAND) openLater(event.getPlayer(),event.getPlayer().getInventory().getHeldItemSlot());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void preventInteract(PlayerInteractEvent event) { if (isToken(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void place(BlockPlaceEvent event) { if (isToken(event.getItemInHand())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drop(PlayerDropItemEvent event) { if (isToken(event.getItemDrop().getItemStack())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) { if (isToken(event.getOldCursor()) || isToken(event.getCursor())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void creative(InventoryCreativeEvent event) { if (isToken(event.getCursor()) || isToken(event.getCurrentItem())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void hopper(InventoryMoveItemEvent event) { if (isToken(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void pickup(InventoryPickupItemEvent event) { if (isToken(event.getItem().getItemStack())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void dispense(BlockDispenseEvent event) { if (isToken(event.getItem())) event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) { event.getDrops().removeIf(this::isToken); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void craft(PrepareItemCraftEvent event) {
        for (ItemStack item:event.getInventory().getMatrix()) if (isToken(item)) { event.getInventory().setResult(null); return; }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void open(InventoryOpenEvent event) {
        if (event.getInventory().getType()!=InventoryType.SHULKER_BOX || event.getInventory().getHolder() instanceof CloudMenu.View) return;
        if (opening.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler public void join(PlayerJoinEvent event) { ensureMainStorage(event.getPlayer()); refresh(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        opening.remove(event.getPlayer().getUniqueId());
        demands.remove(event.getPlayer().getUniqueId()); demandTicks.remove(event.getPlayer().getUniqueId());
    }
}
