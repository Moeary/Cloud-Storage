package dev.cloudstorage;

import dev.cloudstorage.command.CloudCommand;
import dev.cloudstorage.config.SettingsMigration;
import dev.cloudstorage.lifecycle.PaperShutdownCheckpoint;
import dev.cloudstorage.port.Ports;
import dev.cloudstorage.printer.CloudShulker;
import dev.cloudstorage.storage.*;
import dev.cloudstorage.transfer.*;
import dev.cloudstorage.ui.CloudMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;

public final class CloudStoragePlugin extends JavaPlugin {
    private Ledger ledger;
    private Access access;
    private Transfers transfers;
    private CloudMenu menus;
    private CloudShulker shulkers;
    private Ports ports;
    private Set<String> blocked;
    private boolean ready;
    private PaperShutdownCheckpoint shutdownCheckpoint;

    @Override public void onEnable() {
        // Install token guards even if the database/configuration cannot be opened.
        // Disabling the plugin would expose the discovery components as vanilla contents.
        shulkers=new CloudShulker(this);
        getServer().getPluginManager().registerEvents(shulkers,this);
        try {
            if (!Bukkit.getMinecraftVersion().equals("1.21.1")) throw new IllegalStateException("Only Paper Minecraft 1.21.1 is supported");
            saveDefaultConfig();
            if (SettingsMigration.upgrade(getConfig())) {
                saveConfig();
                getLogger().info("Upgraded defaults: shared vault, separate input/output quotas; saved player choices and assets preserved");
            }
            Files.createDirectories(getDataFolder().toPath());
            blocked=new HashSet<>(getConfig().getStringList("storage.blocked-materials"));
            ledger=new Ledger(getDataFolder().toPath().resolve("storage.sqlite"),getConfig().getLong("storage.capacity",1_000_000_000),getConfig().getInt("storage.max-types",4096));
            try { shutdownCheckpoint=new PaperShutdownCheckpoint(getServer(),getDataFolder().toPath().resolve("storage.sqlite")); }
            catch (ReflectiveOperationException | RuntimeException e) {
                getLogger().log(Level.WARNING,"Standalone shutdown checkpoint unsupported; supervisor finalizer is required",e);
            }
            if (ledger.confirmedShutdown()) getLogger().info("Confirmed previous Paper shutdown and exited JVM; standalone checkpoint accepted");
            access=new Access(ledger,getConfig().getString("default-mode","shared"));
            transfers=new Transfers(ledger,this::forbidden,item -> Items.empty(item) || shulkers.isToken(item));
            menus=new CloudMenu(this);
            ports=new Ports(this);
            CloudCommand commands=new CloudCommand(this);
            Objects.requireNonNull(getCommand("cloudstorage")).setExecutor(commands);
            getCommand("cloudstorage").setTabCompleter(commands);
            // Token protection runs before all virtual menu click handling.
            getServer().getPluginManager().registerEvents(menus,this);
            getServer().getPluginManager().registerEvents(ports,this);
            getServer().getMessenger().registerIncomingPluginChannel(this,"cloudstorage:demand",(channel,player,data) -> shulkers.payload(player,data));
            long period=Math.max(20,getConfig().getLong("ports.interval-ticks",40));
            Bukkit.getScheduler().runTaskTimer(this,ports,period,period);
            long refresh=Math.max(10,getConfig().getLong("printer.refresh-ticks",20));
            Bukkit.getScheduler().runTaskTimer(this,() -> {
                try { for (Player player:Bukkit.getOnlinePlayers()) refreshTokens(player); }
                catch (RuntimeException e) { ledger.freeze(); getLogger().log(Level.SEVERE,"Token refresh failed; cloud frozen",e); }
            },refresh,refresh);
            ready=true;
            if (ledger.frozen()) getLogger().severe("Cloud storage is FROZEN after an unclean stop or incomplete transfer. Audit journal before /cs recover unlock.");
            getLogger().info("CloudStorage ready; schema=1; frozen="+ledger.frozen());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE,"CloudStorage failed to initialize",e);
            // Guard-only mode: keep event protection active, reject commands, do not issue tokens.
            Objects.requireNonNull(getCommand("cloudstorage")).setExecutor((sender,command,label,args) -> {
                if (sender instanceof Player player && args.length==2 && args[0].equalsIgnoreCase("shulker") && args[1].equalsIgnoreCase("off")) {
                    message(player,"云潜影盒已关闭，移除="+shulkers.remove(player)+"；修复初始化故障后可重新启用"); return true;
                }
                message(sender,"error=云仓初始化失败；物品保护仍在，修复服务端日志所示问题后重启"); return true;
            });
        }
    }

    @Override public void onDisable() {
        if (transfers!=null) transfers.stop();
        if (ports!=null) ports.pause();
        if (ledger==null) return;
        boolean prepared=false;
        try {
            if (ready) {
                for (Player player:Bukkit.getOnlinePlayers()) {
                    if (player.getOpenInventory().getTopInventory().getHolder() instanceof CloudMenu.View) player.closeInventory();
                    player.saveData();
                }
                for (World world:Bukkit.getWorlds()) world.save();
                // Hot-disable must remain dirty: tokens require active protection even after shutdown.
                if (Bukkit.isStopping()) prepared=ledger.prepareShutdown();
            }
        } catch (RuntimeException e) { getLogger().log(Level.SEVERE,"Could not checkpoint storage; keeping dirty marker",e); }
        finally {
            try {
                ledger.close();
                if (shutdownCheckpoint!=null) {
                    if (prepared) shutdownCheckpoint.arm(ledger.session());
                    else shutdownCheckpoint.close();
                }
            } catch (Exception e) { getLogger().log(Level.SEVERE,"Database close/checkpoint failed; keeping dirty protection",e); }
        }
    }

    public boolean forbidden(ItemStack item) {
        return StoragePolicy.forbiddenDeposit(item) || shulkers.isToken(item) || blocked.contains(item.getType().name());
    }
    public void refreshTokens(Player player) { shulkers.refresh(player); }
    public void message(CommandSender sender,String text) { sender.sendMessage(Component.text("[CloudStorage] "+text)); }
    public void report(CommandSender sender,RuntimeException error) {
        message(sender,"error="+error.getMessage());
        if (!(error instanceof StorageException) || error.getCause()!=null) getLogger().log(Level.WARNING,"Cloud storage operation failed",error);
    }
    public Ledger ledger() { return ledger; }
    public Access access() { return access; }
    public Transfers transfers() { return transfers; }
    public CloudMenu menus() { return menus; }
    public CloudShulker shulkers() { return shulkers; }
    public Ports ports() { return ports; }
    public boolean ready() { return ready; }
}
