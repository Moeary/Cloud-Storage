package dev.cloudstorage.test;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;

/** Disposable test fixture only. NEVER included in the release plugin or manual server. */
public final class CheckpointProbe extends JavaPlugin {
    @Override public void onDisable() {
        if (!Files.exists(Path.of("checkpoint-barrier.enabled"))) return;
        getLogger().info("CHECKPOINT_PROBE_BARRIER after CloudStorage disable, before Paper world flush");
        while (Files.exists(Path.of("checkpoint-barrier.enabled"))) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length!=1) return true;
        if (args[0].equals("unload")) {
            Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("CloudStorage"));
            getLogger().info("CHECKPOINT_PROBE_UNLOADED");
        } else if (args[0].equals("abnormal")) {
            try {
                Object server=Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
                server.getClass().getField("abnormalExit").setBoolean(server,true);
                Bukkit.shutdown();
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        }
        return true;
    }
}
