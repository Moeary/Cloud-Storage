package dev.cloudstorage.command;

import dev.cloudstorage.*;
import dev.cloudstorage.storage.*;
import dev.cloudstorage.transfer.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class CloudCommand implements CommandExecutor,TabCompleter {
    private final CloudStoragePlugin plugin;
    public CloudCommand(CloudStoragePlugin plugin) { this.plugin=plugin; }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        try {
            String sub=args.length==0?"open":args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("recover")) { recover(sender,args); return true; }
            if (sub.equals("status")) {
                plugin.message(sender,"version="+plugin.getPluginMeta().getVersion()+" frozen="+plugin.ledger().frozen()+" pending="+plugin.ledger().pending().size());
                return true;
            }
            if (sub.equals("help")) { help(sender); return true; }
            if (!(sender instanceof Player player)) throw new StorageException("此命令需要玩家执行");
            // Revoked permissions/frozen storage must not prevent discarding an empty discovery shell.
            if (sub.equals("shulker") && args.length==2 && args[1].equalsIgnoreCase("off")) {
                plugin.message(player,"云潜影盒已关闭，移除="+plugin.shulkers().remove(player)+"；/cs shulker 可重新启用");
                return true;
            }
            // A revoked shared permission must not prevent returning to the player's own vault.
            if (sub.equals("mode") && args.length>1) {
                plugin.access().switchMode(player,args[1]); plugin.message(player,"mode="+args[1]); return true;
            }
            String vault=plugin.access().selected(player);
            switch (sub) {
                case "open" -> plugin.menus().open(player,"");
                case "search" -> plugin.menus().open(player,args.length>1?String.join(" ",Arrays.copyOfRange(args,1,args.length)):"");
                case "mode" -> {
                    plugin.message(player,"mode="+(vault.equals("shared")?"shared":"personal"));
                }
                case "deposit" -> {
                    int moved=0;
                    if (args.length>1 && args[1].equalsIgnoreCase("all")) {
                        for (int slot=0;slot<36;slot++) {
                            ItemStack item=player.getInventory().getItem(slot);
                            if (!Items.empty(item) && !plugin.forbidden(item)) moved+=plugin.transfers().deposit(vault,Transfers.Target.player(player),slot,2304);
                        }
                    } else moved=plugin.transfers().deposit(vault,Transfers.Target.player(player),player.getInventory().getHeldItemSlot(),args.length>1?count(args[1]):2304);
                    plugin.refreshTokens(player); plugin.message(player,"deposit="+moved);
                }
                case "withdraw" -> {
                    if (args.length<2) throw new StorageException("/cs withdraw <物品名或唯一ID前缀> [1..2304]");
                    Ledger.Entry entry=match(vault,args[1]);
                    int moved=plugin.transfers().withdraw(vault,Transfers.Target.player(player),entry.item(),args.length>2?count(args[2]):64);
                    plugin.refreshTokens(player); plugin.message(player,"withdraw="+moved);
                }
                case "list" -> {
                    List<Ledger.Entry> entries=plugin.ledger().entries(vault);
                    int page=args.length>1?Math.max(0,Integer.parseInt(args[1])-1):0;
                    plugin.message(player,"vault="+vault+" types="+entries.size()+" page="+(page+1));
                    entries.stream().skip((long)page*10).limit(10).forEach(e -> plugin.message(player,e.item().material()+"="+e.amount()+" id="+e.item().key().substring(0,12)));
                }
                case "shulker" -> {
                    if (args.length>=2 && args[1].equalsIgnoreCase("demand")) {
                        List<String> requested=new ArrayList<>();
                        for (int i=2;i<args.length;i++) for (String name:args[i].split(",")) {
                            org.bukkit.Material material=org.bukkit.Material.matchMaterial(name);
                            if (material==null || !material.isBlock() || !material.isItem()) throw new StorageException("不是可打印方块: "+name);
                            if (!requested.contains(material.name())) requested.add(material.name());
                        }
                        plugin.shulkers().demand(player,requested);
                        return true;
                    }
                    int page=-1;
                    List<String> materials=new ArrayList<>();
                    if (args.length==2 && args[1].matches("[0-9]+")) page=Integer.parseInt(args[1])-1;
                    else if (args.length>1 && !args[1].equalsIgnoreCase("auto")) {
                        for (int i=1;i<args.length;i++) for (String name:args[i].split(",")) {
                            org.bukkit.Material material=org.bukkit.Material.matchMaterial(name);
                            if (material==null || !material.isBlock() || !material.isItem() || material.name().contains("SHULKER_BOX")) throw new StorageException("不是可打印方块: "+name);
                            if (!materials.contains(material.name())) materials.add(material.name());
                        }
                    }
                    plugin.shulkers().give(player,page,materials);
                    plugin.message(player,"云潜影盒已绑定当前仓库，已放入主背包；支持旧版点击/新版 Shift 取料。目录="+(materials.isEmpty()?(page<0?"auto":"page "+(page+1)):String.join(",",materials)));
                }
                case "port" -> {
                    if (args.length<2) throw new StorageException("/cs port input|output|unbind|list");
                    switch (args[1]) {
                        case "input","output" -> { plugin.ports().bind(player,args[1]); plugin.message(player,"port="+args[1]+" bound"); }
                        case "unbind" -> { plugin.ports().unbind(player); plugin.message(player,"port=unbound"); }
                        case "list" -> plugin.ledger().ports().stream().filter(p -> p.owner().equals(player.getUniqueId().toString()))
                                .forEach(p -> plugin.message(player,p.id()+" "+p.mode()+" "+p.vault()));
                        default -> throw new StorageException("/cs port input|output|unbind|list");
                    }
                }
                default -> help(sender);
            }
        } catch (NumberFormatException e) { plugin.message(sender,"error=数量或页码必须是整数"); }
        catch (RuntimeException e) { plugin.report(sender,e); }
        return true;
    }

    private Ledger.Entry match(String vault,String query) {
        String normalized=query.replace("minecraft:","").toUpperCase(Locale.ROOT);
        List<Ledger.Entry> matches=plugin.ledger().entries(vault).stream().filter(e -> e.item().material().equals(normalized)
                || plugin.menus().names().exactChinese(e.item().material(),query)
                || (query.length()>=8 && e.item().key().startsWith(query.toLowerCase(Locale.ROOT)))).toList();
        if (matches.isEmpty()) throw new StorageException("云仓没有此物品");
        if (matches.size()>1) throw new StorageException("存在不同元数据的同名物品，请 /cs list 后使用唯一 ID 前缀");
        return matches.getFirst();
    }

    private static int count(String raw) {
        int value=Integer.parseInt(raw);
        if (value<1 || value>2304) throw new StorageException("单次数量须为 1..2304");
        return value;
    }

    private void recover(CommandSender sender,String[] args) {
        if (!sender.hasPermission("cloudstorage.admin")) throw new StorageException("需要 cloudstorage.admin");
        if (args.length==1 || args[1].equals("list")) {
            for (Ledger.Pending p:plugin.ledger().pending()) plugin.message(sender,p.id()+" delta="+p.delta()+" vault="+p.vault()+" endpoint="+p.endpoint());
            plugin.message(sender,"frozen="+plugin.ledger().frozen()+"；先备份并核对 journal before/after；非正常停机也须审计 DONE 记录");
        } else if (args[1].equals("unlock")) {
            plugin.ledger().unlock();
            plugin.getLogger().warning("Recovery unlock attested by "+sender.getName());
            plugin.message(sender,"recovery=unlocked");
        } else {
            if (args.length!=3 || !Set.of("before","after").contains(args[2])) throw new StorageException("/cs recover <完整journal UUID> before|after");
            plugin.ledger().resolve(args[1],args[2].equals("after"));
            plugin.getLogger().warning("Recovery "+args[1]+" external="+args[2]+" attested by "+sender.getName());
            plugin.message(sender,"recovery=resolved");
        }
    }

    private void help(CommandSender sender) {
        plugin.message(sender,"/cs open | search <物品名/ID> | list [页] | mode personal|shared");
        plugin.message(sender,"/cs deposit [数量|all] | withdraw <物品名/ID> [数量] | shulker [off|auto|页码|材料名...]");
        plugin.message(sender,"/cs port input|output|unbind|list | status | recover list");
    }

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> options=new ArrayList<>();
        if (args.length==1) {
            options.addAll(List.of("open","deposit","withdraw","list","search","mode","shulker","status","help"));
            if (sender.hasPermission("cloudstorage.port")) options.add("port");
            if (sender.hasPermission("cloudstorage.admin")) options.add("recover");
        } else if (args.length==2) {
            switch (args[0]) {
                case "shulker" -> options.addAll(List.of("off","auto","1"));
                case "mode" -> options.addAll(List.of("personal","shared"));
                case "deposit" -> options.addAll(List.of("all","64"));
                case "port" -> options.addAll(List.of("input","output","unbind","list"));
                case "recover" -> { if (sender.hasPermission("cloudstorage.admin")) options.addAll(List.of("list","unlock")); }
                case "withdraw" -> {
                    if (sender instanceof Player player) {
                        try { plugin.ledger().entries(plugin.access().selected(player)).stream().limit(100).forEach(e -> options.add(e.item().material().toLowerCase(Locale.ROOT))); }
                        catch (StorageException ignored) { }
                    }
                }
            }
        }
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
        return options.stream().distinct().filter(s -> s.startsWith(prefix)).toList();
    }
}
