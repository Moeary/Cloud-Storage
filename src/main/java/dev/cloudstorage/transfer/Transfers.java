package dev.cloudstorage.transfer;

import dev.cloudstorage.storage.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.function.Predicate;

public final class Transfers {
    public record Target(Inventory inventory, String id, Runnable save) {
        public static Target player(Player player) { return new Target(player.getInventory(),"player:"+player.getUniqueId(),player::saveData); }
    }
    private final Ledger ledger;
    private final Predicate<ItemStack> forbidden;
    private final Predicate<ItemStack> forbiddenWithdrawal;
    private boolean stopped;

    public Transfers(Ledger ledger, Predicate<ItemStack> forbidden,Predicate<ItemStack> forbiddenWithdrawal) {
        this.ledger=ledger; this.forbidden=forbidden; this.forbiddenWithdrawal=forbiddenWithdrawal;
    }
    public void stop() { stopped=true; }

    public int deposit(String vault, Target target, int slot, int requested) {
        check();
        ItemStack[] before=Items.copy(target.inventory().getStorageContents());
        if (slot < 0 || slot >= before.length || Items.empty(before[slot])) return 0;
        ItemStack original=before[slot];
        if (forbidden.test(original)) throw new StorageException("此物品不能存入云仓");
        int count=Math.min(positive(requested),original.getAmount());
        ItemStack[] after=Items.copy(before);
        after[slot].setAmount(original.getAmount()-count);
        if (after[slot].getAmount()==0) after[slot]=null;
        apply(vault,Items.encode(original),count,target,before,after);
        return count;
    }

    public int withdraw(String vault, Target target, ItemData data, int requested) {
        check(); positive(requested);
        ItemStack sample=Items.decode(data);
        if (forbiddenWithdrawal.test(sample)) throw new StorageException("此物品不能取出");
        int remaining=(int)Math.min((long)requested,ledger.amount(vault,data.key()));
        if (remaining<=0) return 0;
        ItemStack[] before=Items.copy(target.inventory().getStorageContents());
        ItemStack[] after=Items.copy(before);
        int available=remaining;
        int limit=Math.min(sample.getMaxStackSize(),target.inventory().getMaxStackSize());
        for (int i=0;i<after.length && remaining>0;i++) {
            if (!Items.empty(after[i]) && after[i].isSimilar(sample)) {
                int put=Math.min(remaining,Math.max(0,limit-after[i].getAmount()));
                after[i].setAmount(after[i].getAmount()+put); remaining-=put;
            }
        }
        for (int i=0;i<after.length && remaining>0;i++) {
            if (Items.empty(after[i])) {
                int put=Math.min(remaining,limit);
                after[i]=sample.clone(); after[i].setAmount(put); remaining-=put;
            }
        }
        int moved=available-remaining;
        if (moved>0) apply(vault,data,-moved,target,before,after);
        return moved;
    }

    /** Legacy printers use ordinary PICKUP, then a second click into a player slot. */
    public int withdrawCursor(String vault,Player player,ItemData data,int requested) {
        check(); positive(requested);
        ItemStack sample=Items.decode(data);
        if (forbiddenWithdrawal.test(sample)) throw new StorageException("此物品不能取出");
        ItemStack cursor=player.getItemOnCursor();
        if (!Items.empty(cursor) && !cursor.isSimilar(sample)) return 0;
        int existing=Items.empty(cursor)?0:cursor.getAmount();
        int count=(int)Math.min(Math.min((long)requested,ledger.amount(vault,data.key())),Math.max(0,sample.getMaxStackSize()-existing));
        if (count==0) return 0;
        ItemStack[] before=cursorSnapshot(player),after=Items.copy(before);
        ItemStack result=sample.clone(); result.setAmount(existing+count); after[36]=result;
        apply(vault,data,-count,"cursor:"+player.getUniqueId(),before,after,() -> player.setItemOnCursor(result),player::saveData);
        return count;
    }

    public int depositCursor(String vault,Player player,int requested) {
        check(); positive(requested);
        ItemStack cursor=player.getItemOnCursor();
        if (Items.empty(cursor)) return 0;
        if (forbidden.test(cursor)) throw new StorageException("此物品不能存入云仓");
        int count=Math.min(requested,cursor.getAmount());
        ItemStack[] before=cursorSnapshot(player),after=Items.copy(before);
        ItemStack result=cursor.clone(); result.setAmount(cursor.getAmount()-count);
        after[36]=Items.empty(result)?null:result;
        apply(vault,Items.encode(cursor),count,"cursor:"+player.getUniqueId(),before,after,() -> player.setItemOnCursor(result),player::saveData);
        return count;
    }

    private static ItemStack[] cursorSnapshot(Player player) {
        ItemStack[] snapshot=new ItemStack[37];
        System.arraycopy(Items.copy(player.getInventory().getStorageContents()),0,snapshot,0,36);
        snapshot[36]=Items.empty(player.getItemOnCursor())?null:player.getItemOnCursor().clone();
        return snapshot;
    }

    private void apply(String vault, ItemData item, long delta, Target target, ItemStack[] before, ItemStack[] after) {
        apply(vault,item,delta,target.id(),before,after,() -> target.inventory().setStorageContents(after),target.save());
    }

    private void apply(String vault,ItemData item,long delta,String endpoint,ItemStack[] before,ItemStack[] after,Runnable change,Runnable save) {
        String id=UUID.randomUUID().toString();
        ledger.prepare(id,vault,item,delta,endpoint,ItemStack.serializeItemsAsBytes(before),ItemStack.serializeItemsAsBytes(after));
        try {
            change.run();
            save.run();
            ledger.settle(id);
        } catch (RuntimeException e) {
            ledger.freeze();
            throw new StorageException("转移中断，已冻结；日志编号 "+id,e);
        }
    }

    private void check() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Inventory transfers require the server thread");
        if (stopped) throw new StorageException("服务器正在停止");
        if (ledger.frozen()) throw new StorageException("云仓已冻结，等待管理员审计");
    }
    private static int positive(int count) { if (count<1 || count>2304) throw new StorageException("单次数量须在 1..2304"); return count; }
}
