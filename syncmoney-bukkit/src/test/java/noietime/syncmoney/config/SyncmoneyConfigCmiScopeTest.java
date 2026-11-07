package noietime.syncmoney.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncmoneyConfigCmiScopeTest {

    @Test
    void cmiModeDoesNotValidateSettingsForDisabledSyncmoneyManagementModules() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.setDefaults(new YamlConfiguration());
        yaml.set("server-name", "cmi-node");
        yaml.set("economy.mode", "cmi");
        yaml.set("pay.min-amount", -5);
        yaml.set("pay.max-amount", -10);
        yaml.set("circuit-breaker.player-protection.rate-limit.max-amount-per-minute", -1);
        yaml.set("circuit-breaker.player-protection.anomaly-detection.balance-change-threshold", 0);
        yaml.set("web-admin.enabled", true);

        SyncmoneyConfig config = new SyncmoneyConfig(configPlugin(yaml));

        assertDoesNotThrow(config::validate);
    }

    @Test
    void syncmoneyManagedModesContinueToValidateTheirWalletConfiguration() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.setDefaults(new YamlConfiguration());
        yaml.set("server-name", "sync-node");
        yaml.set("economy.mode", "sync");
        yaml.set("pay.min-amount", -5);

        SyncmoneyConfig config = new SyncmoneyConfig(configPlugin(yaml));

        assertThrows(IllegalArgumentException.class, config::validate);
    }

    private static JavaPlugin configPlugin(YamlConfiguration yaml) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(yaml);
        return plugin;
    }
}
