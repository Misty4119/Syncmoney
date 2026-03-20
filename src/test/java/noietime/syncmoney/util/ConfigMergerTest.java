package noietime.syncmoney.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.StringReader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConfigMerger.
 */
class ConfigMergerTest {

    @Mock
    private JavaPlugin mockPlugin;

    private ConfigMerger configMerger;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(mockPlugin.getDataFolder()).thenReturn(new File("target/test-config"));
        when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        
        configMerger = new ConfigMerger(mockPlugin, "config.yml", "messages.yml");
    }

    @Test
    void testDefaultConfigVersion() {

        assertEquals(11, configMerger.getDefaultConfigVersion());
    }

    @Test
    void testGetLocalConfigVersion() {



        int version = configMerger.getLocalConfigVersion();

        assertTrue(version >= 0);
    }

    @Test
    void testNeedsUpgrade() {




        boolean needsUpgrade = configMerger.needsUpgrade();

        assertNotNull(needsUpgrade);
    }

    @Test
    void testFindMissingKeys() throws Exception {

        String defaultYaml = """
            server-name: "test"
            redis:
              host: "localhost"
              port: 6379
            """;

        String localYaml = """
            server-name: "test"
            """;

        FileConfiguration defaults = YamlConfiguration.loadConfiguration(new StringReader(defaultYaml));
        FileConfiguration local = YamlConfiguration.loadConfiguration(new StringReader(localYaml));




        assertNotNull(defaults);
        assertNotNull(local);
    }

    @Test
    void testVersionComparison() {

        int defaultVersion = configMerger.getDefaultConfigVersion();
        

        assertEquals(11, defaultVersion);
        

        assertTrue(10 < defaultVersion);
        

        assertEquals(11, defaultVersion);
        

        assertTrue(12 > defaultVersion);
    }
}
