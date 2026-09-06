package noietime.syncmoney.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigReloadPolicyTest {
    @Test
    void allowsDisplayButRejectsServiceAndUnknownChanges() {
        var active = new YamlConfiguration();
        active.set("shadow-sync.enabled", true);
        var next = new YamlConfiguration();
        next.set("shadow-sync.enabled", false);
        next.set("display.decimals", 2);
        next.set("future-storage.option", "new");
        assertEquals(java.util.List.of("future-storage.option", "shadow-sync.enabled"),
                ConfigReloadPolicy.restartRequired(active, next));
    }
}
