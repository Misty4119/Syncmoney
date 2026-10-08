package noietime.syncmoney.web.api.nodes;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NodeOperationsConfigTest {

    @Test
    void nodeEditsUpdateBothRuntimeSnapshotAndBukkitConfigThatSaveConfigPersists() {
        Syncmoney plugin = mock(Syncmoney.class);
        YamlConfiguration persistedConfig = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(persistedConfig);

        SyncmoneyConfig runtimeSnapshot = mock(SyncmoneyConfig.class);
        YamlConfiguration snapshotConfig = new YamlConfiguration();
        when(runtimeSnapshot.getConfig()).thenReturn(snapshotConfig);

        NodeOperationsHandler handler = new NodeOperationsHandler(plugin, runtimeSnapshot, null, null);
        List<Map<String, Object>> nodes = List.of(Map.of(
                "name", "remote",
                "url", "https://syncmoney.example",
                "api-key", "encrypted-value",
                "enabled", true));

        handler.setNodeConfigValue("web-admin.nodes", nodes);

        assertEquals(nodes, persistedConfig.getMapList("web-admin.nodes"));
        assertEquals(nodes, snapshotConfig.getMapList("web-admin.nodes"));
    }
}
