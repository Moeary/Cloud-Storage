package dev.cloudstorage.ui;

import dev.cloudstorage.*;
import dev.cloudstorage.storage.*;
import dev.cloudstorage.transfer.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class CloudMenu implements Listener {
    public static final class View implements InventoryHolder {
        private final UUID owner;
        private final String vault;
        private final String search;
        private final boolean printer;
        private final List<Ledger.Entry> printerItems;
        private int page;
        private final Inventory inventory;
        private final Map<Integer,Ledger.Entry> slots=new HashMap<>();
        private View(Player player,String vault,String search,boolean printer,int page,List<Ledger.Entry> printerItems) {
            this.owner=player.getUniqueId(); this.vault=vault; this.search=search; this.printer=printer; this.page=page;
            this.printerItems=List.copyOf(printerItems);
            this.inventory=printer ? Bukkit.createInventory(this,InventoryType.SHULKER_BOX,Component.text("云潜影盒"))
                    : Bukkit.createInventory(this,54,Component.text("云仓 · "+(vault.equals("shared")?"全服共享":"个人")));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final CloudStoragePlugin plugin;
    private final ItemNames names=new ItemNames();
    private record Search(String vault,long expires) {}
    private final Map<UUID,Search> searches=new java.util.concurrent.ConcurrentHashMap<>();
    public CloudMenu(CloudStoragePlugin plugin) { this.plugin=plugin; }

    public ItemNames names() { return names; }
    public void open(Player player,String search) { searches.remove(player.getUniqueId()); open(player,plugin.access().selected(player),search,false,0); }
    public void openPrinter(Player player,String vault,List<Ledger.Entry> items) {
        plugin.access().require(player,vault);
        if (!Items.empty(player.getItemOnCursor())) throw new StorageException("请先清空鼠标持物");
        View view=new View(player,vault,"",true,0,items);
        render(view); player.openInventory(view.inventory);
    }
    private void open(Player player,String vault,String search,boolean printer,int page) {
        plugin.access().require(player,vault);
        if (!Items.empty(player.getItemOnCursor())) throw new StorageException("请先清空鼠标持物");
        View view=new View(player,vault,search,printer,page,List.of());
        render(view);
        player.openInventory(view.inventory);
    }

    public List<Ledger.Entry> printable(String vault,int page) {
        return printableAll(vault).stream()
                .skip((long)Math.max(0,page)*27).limit(27).toList();
    }

    public List<Ledger.Entry> printableAll(String vault) {
        return plugin.ledger().entries(vault).stream().filter(entry -> printable(Items.decode(entry.item()))).toList();
    }

    public static boolean isPrinterView(Player player) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof View view && view.printer;
    }

    private boolean printable(ItemStack item) {
        return !Items.empty(item) && item.getType().isBlock() && !item.hasItemMeta()
                && !item.getType().name().contains("SHULKER_BOX") && !plugin.forbidden(item);
    }

    private int returnRoom(View view,ItemStack sample) {
        if (!printable(sample)) return 0;
        int room=0;
        for (ItemStack shown:view.inventory.getContents()) {
            if (Items.empty(shown)) room+=sample.getMaxStackSize();
            else if (shown.isSimilar(sample)) room+=Math.max(0,sample.getMaxStackSize()-shown.getAmount());
        }
        return room;
    }

    private void showReturned(View view,ItemData data,int count) {
        ItemStack sample=Items.decode(data);
        for (int pass=0;pass<2 && count>0;pass++) {
            for (int slot=0;slot<27 && count>0;slot++) {
                ItemStack shown=view.inventory.getItem(slot);
                if (pass==0 && !Items.empty(shown) && shown.isSimilar(sample)) {
                    int put=Math.min(count,Math.max(0,sample.getMaxStackSize()-shown.getAmount()));
                    shown.setAmount(shown.getAmount()+put); count-=put; view.inventory.setItem(slot,shown);
                } else if (pass==1 && Items.empty(shown)) {
                    int put=Math.min(count,sample.getMaxStackSize());
                    ItemStack icon=sample.clone(); icon.setAmount(put); count-=put;
                    view.inventory.setItem(slot,icon);
                    view.slots.put(slot,new Ledger.Entry(data,plugin.ledger().amount(view.vault,data.key())));
                }
            }
        }
    }

    private void render(View view) {
        view.inventory.clear(); view.slots.clear();
        List<Ledger.Entry> entries=view.printer ? view.printerItems : plugin.ledger().entries(view.vault).stream()
                .filter(e -> names.matches(e.item(),view.search)).toList();
        int size=view.printer?27:45;
        if (!view.printer) view.page=Math.min(Math.max(0,view.page),Math.max(0,(entries.size()-1)/size));
        int start=view.printer?0:view.page*size;
        for (int i=0;i<size && start+i<entries.size();i++) {
            Ledger.Entry entry=entries.get(start+i);
            ItemStack icon=Items.decode(entry.item());
            icon.setAmount((int)Math.min(entry.amount(),icon.getMaxStackSize()));
            if (!view.printer) {
                ItemMeta meta=icon.getItemMeta();
                meta.lore(List.of(Component.text(names.chinese(entry.item().material())+" · 库存: "+entry.amount()),Component.text("左键/Shift: 一组 · 右键: 一个"),Component.text("ID: "+entry.item().key().substring(0,12))));
                icon.setItemMeta(meta);
            }
            view.slots.put(i,entry); view.inventory.setItem(i,icon);
        }
        if (!view.printer) {
            view.inventory.setItem(45,button(Material.ARROW,"上一页"));
            view.inventory.setItem(48,button(Material.COMPASS,"搜索 · 点击后在聊天输入中文/英文；右键清除"));
            view.inventory.setItem(49,button(Material.CLOCK,"刷新 · 页 "+(view.page+1)));
            view.inventory.setItem(50,button(Material.BOOK,"背包 Shift 点击存入 · /cs mode 切换仓库"));
            view.inventory.setItem(53,button(Material.ARROW,"下一页"));
        }
    }

    private static ItemStack button(Material material,String title) {
        ItemStack item=new ItemStack(material); ItemMeta meta=item.getItemMeta(); meta.displayName(Component.text(title)); item.setItemMeta(meta); return item;
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        boolean alreadyCancelled=event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled || !(event.getWhoClicked() instanceof Player player)) return;
        if (!view.owner.equals(player.getUniqueId())) return;
        ClickType type=event.getClick();
        if (type!=ClickType.LEFT && type!=ClickType.RIGHT && type!=ClickType.SHIFT_LEFT && type!=ClickType.SHIFT_RIGHT) return;
        try {
            plugin.access().require(player,view.vault);
            if (view.printer && (!player.hasPermission("cloudstorage.printer") || !plugin.getConfig().getBoolean("printer.enabled"))) return;
            if (view.printer && !event.isShiftClick()) {
                printerPickup(event,player,view);
                // Do not defer a full inventory resend: legacy clients immediately send their second click.
                return;
            }
            if (!Items.empty(event.getCursor())) return;
            if (event.getClickedInventory()==player.getInventory()) {
                if (!event.isShiftClick() || event.getSlot()<0 || event.getSlot()>=36) return;
                ItemStack source=player.getInventory().getItem(event.getSlot());
                if (Items.empty(source)) return;
                ItemData data=Items.encode(source);
                int requested=view.printer?Math.min(source.getAmount(),returnRoom(view,source)):2304;
                if (requested==0) return;
                int count=plugin.transfers().deposit(view.vault,Transfers.Target.player(player),event.getSlot(),requested);
                if (view.printer && count>0) showReturned(view,data,count);
                if (count>0) plugin.message(player,"deposit="+count);
            } else if (event.getClickedInventory()==view.inventory) {
                int slot=event.getRawSlot();
                Ledger.Entry entry=view.slots.get(slot);
                if (entry!=null) {
                    ItemStack shown=view.inventory.getItem(slot);
                    if (Items.empty(shown)) return;
                    int count=plugin.transfers().withdraw(view.vault,Transfers.Target.player(player),entry.item(),
                            type==ClickType.RIGHT ? 1 : shown.getAmount());
                    if (count>0) {
                        if (view.printer) {
                            // Client printer confirms the source count DECREASE; never refill within this window.
                            int left=shown.getAmount()-count;
                            if (left<=0) { view.inventory.setItem(slot,null); view.slots.remove(slot); }
                            else { shown.setAmount(left); view.inventory.setItem(slot,shown); }
                        }
                        plugin.message(player,"withdraw="+count);
                    }
                } else if (!view.printer) {
                    if (slot==48) {
                        if (type==ClickType.RIGHT) {
                            Bukkit.getScheduler().runTask(plugin,() -> open(player,view.vault,"",false,0));
                        } else {
                            Bukkit.getScheduler().runTask(plugin,() -> {
                                if (!player.isOnline()) return;
                                player.closeInventory();
                                searches.put(player.getUniqueId(),new Search(view.vault,System.currentTimeMillis()+30_000));
                                plugin.message(player,"请在聊天输入物品中文名、英文名或 ID；30 秒内有效，输入 取消 返回。内容不会公开发送。");
                            });
                        }
                        return;
                    }
                    if (slot==45) view.page=Math.max(0,view.page-1);
                    if (slot==53) view.page++;
                }
            }
            if (!view.printer) render(view);
            plugin.refreshTokens(player);
            Bukkit.getScheduler().runTask(plugin,player::updateInventory);
        } catch (RuntimeException e) { plugin.report(player,e); }
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void searchChat(AsyncChatEvent event) {
        Search search=searches.remove(event.getPlayer().getUniqueId());
        if (search==null) return;
        event.setCancelled(true);
        String text=PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        Bukkit.getScheduler().runTask(plugin,() -> {
            Player player=event.getPlayer();
            if (!player.isOnline()) return;
            try {
                if (System.currentTimeMillis()>search.expires()) { plugin.message(player,"搜索已超时，请重新点击搜索按钮"); return; }
                if (text.length()>64) throw new StorageException("搜索关键词最多 64 个字符");
                open(player,search.vault(),text.equals("取消") || text.equalsIgnoreCase("cancel")?"":text,false,0);
            } catch (RuntimeException e) { plugin.report(player,e); }
        });
    }

    @EventHandler public void quit(PlayerQuitEvent event) { searches.remove(event.getPlayer().getUniqueId()); }

    private void printerPickup(InventoryClickEvent event,Player player,View view) {
        ItemStack cursor=player.getItemOnCursor();
        boolean right=event.getClick()==ClickType.RIGHT;
        if (event.getClickedInventory()==player.getInventory()) {
            int slot=event.getSlot();
            if (slot<0 || slot>=36) return;
            ItemStack current=player.getInventory().getItem(slot);
            if (plugin.shulkers().isToken(current) || plugin.shulkers().isToken(cursor)) return;
            // Ordinary owned item movement, never accepting the packet's claimed cursor/changed slots.
            if (Items.empty(cursor) && !Items.empty(current)) {
                int count=right?(current.getAmount()+1)/2:current.getAmount();
                ItemStack taken=current.clone(); taken.setAmount(count);
                ItemStack left=current.clone(); left.setAmount(current.getAmount()-count);
                player.getInventory().setItem(slot,Items.empty(left)?null:left);
                player.setItemOnCursor(taken); player.saveData();
            } else if (!Items.empty(cursor) && (Items.empty(current) || current.isSimilar(cursor))) {
                int existing=Items.empty(current)?0:current.getAmount();
                int count=Math.min(right?1:cursor.getAmount(),Math.max(0,cursor.getMaxStackSize()-existing));
                if (count==0) return;
                ItemStack placed=cursor.clone(); placed.setAmount(existing+count);
                ItemStack left=cursor.clone(); left.setAmount(cursor.getAmount()-count);
                player.getInventory().setItem(slot,placed);
                player.setItemOnCursor(Items.empty(left)?null:left); player.saveData();
            }
            return;
        }
        if (event.getClickedInventory()!=view.inventory) return;
        int slot=event.getRawSlot();
        if (Items.empty(cursor)) {
            Ledger.Entry entry=view.slots.get(slot);
            ItemStack shown=view.inventory.getItem(slot);
            if (entry==null || Items.empty(shown)) return;
            int count=plugin.transfers().withdrawCursor(view.vault,player,entry.item(),right?(shown.getAmount()+1)/2:shown.getAmount());
            if (count>0) {
                ItemStack left=shown.clone(); left.setAmount(shown.getAmount()-count);
                view.inventory.setItem(slot,Items.empty(left)?null:left);
                if (Items.empty(left)) view.slots.remove(slot);
            }
        } else {
            ItemStack shown=view.inventory.getItem(slot);
            if (!printable(cursor) || (!Items.empty(shown) && !shown.isSimilar(cursor))) return;
            int room=cursor.getMaxStackSize()-(Items.empty(shown)?0:shown.getAmount());
            int requested=Math.min(right?1:cursor.getAmount(),room);
            if (requested==0) return;
            ItemData data=Items.encode(cursor);
            int count=plugin.transfers().depositCursor(view.vault,player,requested);
            if (count>0) {
                ItemStack icon=Items.decode(data); icon.setAmount((Items.empty(shown)?0:shown.getAmount())+count);
                view.inventory.setItem(slot,icon); view.slots.put(slot,new Ledger.Entry(data,plugin.ledger().amount(view.vault,data.key())));
            }
        }
    }

    @EventHandler(priority=EventPriority.LOWEST)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }

    // Keep subsequent handlers from re-enabling vanilla writes to virtual display items.
    @EventHandler(priority=EventPriority.HIGHEST)
    public void protectClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void protectDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }
}
