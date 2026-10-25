package dev.cloudstorage;

import dev.cloudstorage.storage.*;
import org.bukkit.entity.Player;

public final class Access {
    private final Ledger ledger;
    private final String defaultMode;
    public Access(Ledger ledger, String defaultMode) {
        this.ledger=ledger;
        this.defaultMode="shared".equals(defaultMode)?"shared":"personal";
    }
    public static String personal(java.util.UUID id) { return "player:"+id; }
    public String selected(Player player) {
        if (!player.hasPermission("cloudstorage.use")) throw new StorageException("没有云仓使用权限");
        String vault="shared".equals(ledger.mode(player.getUniqueId(),defaultMode)) ? "shared" : personal(player.getUniqueId());
        require(player,vault);
        return vault;
    }
    public void require(Player player, String vault) {
        if (!player.hasPermission("cloudstorage.use")) throw new StorageException("没有云仓使用权限");
        if (vault.equals(personal(player.getUniqueId()))) return;
        if (vault.equals("shared") && player.hasPermission("cloudstorage.shared")) return;
        throw new StorageException("仓库不属于此 UUID，或没有共享仓权限");
    }
    public void switchMode(Player player,String mode) {
        if (!mode.equals("personal") && !mode.equals("shared")) throw new StorageException("模式须为 personal 或 shared");
        require(player,mode.equals("shared") ? "shared" : personal(player.getUniqueId()));
        ledger.setMode(player.getUniqueId(),mode);
        player.closeInventory();
    }
}
