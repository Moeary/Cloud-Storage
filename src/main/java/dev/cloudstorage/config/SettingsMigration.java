package dev.cloudstorage.config;

import org.bukkit.configuration.file.FileConfiguration;

/** One-time upgrade of old defaults; never edits inventory, preferences or port bindings. */
public final class SettingsMigration {
    private SettingsMigration() {}
    public static boolean upgrade(FileConfiguration config) {
        if (config.isSet("config-version") && config.getInt("config-version")>=2) return false;
        config.set("default-mode","shared");
        if (!config.isSet("ports.max-inputs-per-player")) config.set("ports.max-inputs-per-player",5);
        if (!config.isSet("ports.max-outputs-per-player")) config.set("ports.max-outputs-per-player",5);
        config.set("ports.max-per-player",null);
        config.set("config-version",2);
        return true;
    }
}
