package dev.cloudstorage.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsMigrationTest {
    @Test void oldConfigWithResourceDefaultsStillUpgradesOnlyOnce() throws Exception {
        var config=new YamlConfiguration();
        config.loadFromString("default-mode: personal\nports:\n  max-per-player: 8\n  interval-ticks: 60\n");
        var defaults=new YamlConfiguration(); defaults.set("config-version",2); config.setDefaults(defaults);
        assertTrue(SettingsMigration.upgrade(config));
        assertEquals("shared",config.getString("default-mode"));
        assertEquals(5,config.getInt("ports.max-inputs-per-player"));
        assertEquals(5,config.getInt("ports.max-outputs-per-player"));
        assertFalse(config.isSet("ports.max-per-player"));
        assertEquals(60,config.getInt("ports.interval-ticks"));
        config.set("default-mode","personal");
        assertFalse(SettingsMigration.upgrade(config));
        assertEquals("personal",config.getString("default-mode"));
    }
    @Test void explicitDirectionalLimitsArePreserved() {
        var config=new YamlConfiguration(); config.set("ports.max-inputs-per-player",2); config.set("ports.max-outputs-per-player",3);
        assertTrue(SettingsMigration.upgrade(config));
        assertEquals(2,config.getInt("ports.max-inputs-per-player"));
        assertEquals(3,config.getInt("ports.max-outputs-per-player"));
    }
}
