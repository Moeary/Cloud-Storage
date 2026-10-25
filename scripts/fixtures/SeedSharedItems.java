package dev.cloudstorage.fixture;

import dev.cloudstorage.CloudStoragePlugin;
import dev.cloudstorage.storage.ItemData;
import dev.cloudstorage.transfer.Items;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/** Disposable LOCAL TEST fixture. Remove this helper JAR after provisioning. */
public final class SeedSharedItems extends JavaPlugin {
    @Override public void onEnable() {
        CloudStoragePlugin cloud=(CloudStoragePlugin)getServer().getPluginManager().getPlugin("CloudStorage");
        if (cloud==null || !cloud.ready() || cloud.ledger().frozen()) throw new IllegalStateException("CloudStorage must be ready and writable");
        final long desired=100_000;
        int count=0;
        long added=0;
        var names=new ArrayList<String>();
        byte[] empty=ItemStack.serializeItemsAsBytes(new ItemStack[0]);
        try {
            for (Material material:Material.values()) {
                if (material.isLegacy() || !material.isItem() || material.isAir()) continue;
                ItemData item=Items.encode(new ItemStack(material));
                long delta=desired-cloud.ledger().amount("shared",item.key());
                if (delta>0) {
                    String id=UUID.randomUUID().toString();
                    cloud.ledger().prepare(id,"shared",item,delta,"fixture:all-items:explicit-local-test",empty,empty);
                    cloud.ledger().settle(id);
                    added+=delta;
                }
                names.add("\""+material.name()+"\"");
                if (++count%200==0) getLogger().info("Provisioned "+count+" item types");
            }
            Path report=getDataFolder().toPath().resolve("report.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report,"{\"created\":\""+Instant.now()+"\",\"vault\":\"shared\",\"types\":"+count+
                    ",\"minimum_per_type\":"+desired+",\"added\":"+added+",\"materials\":["+String.join(",",names)+"]}");
            getLogger().info("ALL_ITEMS_READY types="+count+" minimum_per_type="+desired+" added="+added);
        } catch (Exception e) {
            cloud.ledger().freeze();
            throw new IllegalStateException("Local fixture failed; cloud frozen for audit",e);
        }
    }
}
