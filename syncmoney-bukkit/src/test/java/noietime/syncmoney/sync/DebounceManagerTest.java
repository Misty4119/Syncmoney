package noietime.syncmoney.sync;

import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link DebounceManager}, focusing on the hard upper-bound LRU eviction that prevents
 * the deduplication map from growing without bound during message bursts.
 */
class DebounceManagerTest {

    private static final int MAX_ENTRIES = 10_000;

    private DebounceManager debounceManager;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        SyncmoneyConfig config = mock(SyncmoneyConfig.class);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("DebounceManagerTest"));
        lenient().when(config.isDebug()).thenReturn(false);
        debounceManager = new DebounceManager(plugin, config);
    }

    @Test
    void firstMessageIsProcessed_duplicateWithinTtlIsRejected() {
        UUID uuid = UUID.randomUUID();
        String server = "server-a";
        String messageId = UUID.randomUUID().toString();

        assertTrue(debounceManager.shouldProcess(uuid, 1L, server, messageId),
                "first occurrence must be processed");
        assertFalse(debounceManager.shouldProcess(uuid, 1L, server, messageId),
                "duplicate within TTL must be rejected");
    }

    @Test
    void cacheNeverExceedsHardCap_duringLargeBurst() {
        String server = "server-burst";
        UUID uuid = UUID.randomUUID();

        for (int version = 0; version < MAX_ENTRIES * 3; version++) {
            debounceManager.shouldProcess(uuid, version, server, "msg-" + version);
            assertTrue(debounceManager.getCacheSize() <= MAX_ENTRIES,
                    "cache size must never exceed the hard cap, was " + debounceManager.getCacheSize()
                            + " at version " + version);
        }
    }

    @Test
    void overflowIsEvictedDownToHardCap() {
        String server = "server-overflow";
        UUID uuid = UUID.randomUUID();

        for (int version = 0; version < MAX_ENTRIES + 500; version++) {
            debounceManager.shouldProcess(uuid, version, server, "msg-" + version);
        }

        assertTrue(debounceManager.getCacheSize() <= MAX_ENTRIES,
                "after overflow the cache must be evicted down to the hard cap");
        assertTrue(debounceManager.getCacheSize() > 0,
                "eviction must not wipe the entire cache");
    }

    @Test
    void manualCleanupEnforcesHardCap() {
        String server = "server-manual";
        UUID uuid = UUID.randomUUID();

        for (int version = 0; version < MAX_ENTRIES + 200; version++) {
            debounceManager.shouldProcess(uuid, version, server, "msg-" + version);
        }

        debounceManager.cleanup();

        assertTrue(debounceManager.getCacheSize() <= MAX_ENTRIES,
                "cleanup must enforce the hard cap");
    }
}
