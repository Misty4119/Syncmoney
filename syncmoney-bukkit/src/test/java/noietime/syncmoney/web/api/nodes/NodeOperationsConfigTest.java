package noietime.syncmoney.web.api.nodes;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NodeOperationsConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://[fc00::1]:8080",
            "https://[fd12:3456::1]:8443",
            "http://[fec0::1]:8080",
            "http://[::]:8080",
            "https://[ff02::1]:8443",
            "http://225.0.0.1:8080"
    })
    void rejectsIpv6LocalReservedAndMulticastNodeAddresses(String url) {
        NodeOperationsHandler handler = new NodeOperationsHandler(
                mock(Syncmoney.class), mock(SyncmoneyConfig.class), null, null);

        assertFalse(handler.isUrlAllowed(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://100.64.0.1:8080",
            "http://100.127.255.254:8080",
            "http://192.0.0.1:8080",
            "http://192.0.2.1:8080",
            "http://192.88.99.1:8080",
            "http://198.18.0.1:8080",
            "http://198.19.255.254:8080",
            "http://198.51.100.1:8080",
            "http://203.0.113.1:8080"
    })
    void rejectsSpecialUseIpv4NodeAddresses(String url) {
        NodeOperationsHandler handler = new NodeOperationsHandler(
                mock(Syncmoney.class), mock(SyncmoneyConfig.class), null, null);

        assertFalse(handler.isUrlAllowed(url));
    }

    @Test
    void rejectsNodeUrlContainingUserInfo() {
        NodeOperationsHandler handler = new NodeOperationsHandler(
                mock(Syncmoney.class), mock(SyncmoneyConfig.class), null, null);

        assertFalse(handler.isUrlAllowed("http://operator:synthetic-password@203.0.113.7:8080"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://8.8.8.8:8443/api?token=synthetic-query",
            "https://8.8.8.8:8443/api#synthetic-fragment",
            "https://8.8.8.8:8443/api%3Ftoken=synthetic-query",
            "https://8.8.8.8:8443/api%23synthetic-fragment"
    })
    void rejectsNodeUrlsContainingQueryOrFragment(String url) {
        NodeOperationsHandler handler = new NodeOperationsHandler(
                mock(Syncmoney.class), mock(SyncmoneyConfig.class), null, null);

        assertFalse(handler.isUrlAllowed(url));
    }

    @Test
    void doesNotLogRawUrlWhenValidationFails() {
        Syncmoney plugin = mock(Syncmoney.class);
        Logger logger = Logger.getLogger("syncmoney-node-url-validation-test-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        when(plugin.getLogger()).thenReturn(logger);
        NodeOperationsHandler handler = new NodeOperationsHandler(
                plugin, mock(SyncmoneyConfig.class), null, null);

        try {
            assertFalse(handler.isUrlAllowed("https://203.0.113.8/?token=synthetic-token%zz"));
            assertTrue(records.stream().anyMatch(record -> record.getMessage()
                    .startsWith("URL validation error:")));
            assertTrue(records.stream().noneMatch(record -> record.getMessage()
                    .contains("synthetic-token")));
            assertTrue(records.stream().noneMatch(record -> record.getMessage()
                    .contains("203.0.113.8")));
        } finally {
            logger.removeHandler(capture);
        }
    }

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

    @Test
    void indexedNodeEditPreservesTheYamlListShapeAfterSerialization() throws Exception {
        Syncmoney plugin = mock(Syncmoney.class);
        YamlConfiguration persistedConfig = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(persistedConfig);

        SyncmoneyConfig runtimeSnapshot = mock(SyncmoneyConfig.class);
        YamlConfiguration snapshotConfig = new YamlConfiguration();
        when(runtimeSnapshot.getConfig()).thenReturn(snapshotConfig);

        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of(
                        "name", "primary",
                        "url", "https://primary.example",
                        "api-key", "primary-key",
                        "enabled", true)),
                new LinkedHashMap<>(Map.of(
                        "name", "backup",
                        "url", "https://backup.example",
                        "api-key", "backup-key",
                        "enabled", false))));
        persistedConfig.set("web-admin.nodes", nodes);
        snapshotConfig.set("web-admin.nodes", nodes);

        NodeOperationsHandler handler = new NodeOperationsHandler(plugin, runtimeSnapshot, null, null);
        handler.updateNodeInConfig(0, "name", "renamed-primary");

        List<Map<String, Object>> expected = List.of(
                Map.of("name", "renamed-primary", "url", "https://primary.example",
                        "api-key", "primary-key", "enabled", true),
                Map.of("name", "backup", "url", "https://backup.example",
                        "api-key", "backup-key", "enabled", false));
        assertEquals(expected, persistedConfig.getMapList("web-admin.nodes"));
        assertEquals(expected, snapshotConfig.getMapList("web-admin.nodes"));

        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.loadFromString(persistedConfig.saveToString());
        assertEquals(expected, reloaded.getMapList("web-admin.nodes"),
                "saveConfig must persist a list of node maps after an indexed edit");
    }
}
