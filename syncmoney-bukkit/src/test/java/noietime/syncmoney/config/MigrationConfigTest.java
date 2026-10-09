package noietime.syncmoney.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationConfigTest {

    @Test
    void cmiEconomyAutoDisableDefaultsToRequiredSafeSetting() {
        assertTrue(new MigrationConfig(new YamlConfiguration()).isCMIAutoDisableEconomy());
    }

    @Test
    void cmiEconomyAutoDisableRespectsAnExplicitOperatorSetting() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("migration.cmi.auto-disable-economy", false);

        assertFalse(new MigrationConfig(config).isCMIAutoDisableEconomy());
    }
}
