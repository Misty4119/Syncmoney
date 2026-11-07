package noietime.syncmoney.migration;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationCheckpointFailureTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cannotMarkCheckpointCompleteWhilePlayerFailuresRemain() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCheckpointFailureTest"));
        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        checkpoint.init(1);
        checkpoint.recordFailure("player", "injected persistence failure");

        assertThrows(IllegalStateException.class, checkpoint::complete);
        assertEquals(MigrationCheckpoint.MigrationState.RUNNING, checkpoint.getState());

        checkpoint.recordFailure("player", "second retry failure");
        assertEquals(1, checkpoint.getFailedCount(), "the same failed row is counted once");
        checkpoint.clearFailure("player");
        assertEquals(0, checkpoint.getFailedCount());
        checkpoint.complete();
        assertEquals(MigrationCheckpoint.MigrationState.COMPLETED, checkpoint.getState());
    }

    @Test
    void resumePreservesCheckpointSourceAndForcePolicy() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCheckpointFailureTest"));
        MigrationCheckpoint saved = new MigrationCheckpoint(plugin);
        saved.init(3, "cmi-single|jdbc:test-db|CMI_Users", true,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        saved.pause();

        MigrationCheckpoint resumed = new MigrationCheckpoint(plugin);
        assertTrue(resumed.resume());
        assertEquals("cmi-single|jdbc:test-db|CMI_Users", resumed.getSourceIdentity());
        assertTrue(resumed.getForceOverwrite());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                resumed.getSourceFingerprint());
    }

    @Test
    void legacyCheckpointWithoutSourceIdentityRemainsUnpinned() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCheckpointFailureTest"));
        Files.writeString(temporaryDirectory.resolve("migration_checkpoint.json"), """
                {
                  "state": "PAUSED",
                  "currentOffset": 5,
                  "totalPlayers": 10,
                  "migratedCount": 5,
                  "failedCount": 0,
                  "startTime": "2026-10-06T00:00:00Z",
                  "lastUpdateTime": "2026-10-06T00:00:00Z",
                  "failures": {}
                }
                """);

        MigrationCheckpoint legacy = new MigrationCheckpoint(plugin);
        assertTrue(legacy.resume());
        assertNull(legacy.getSourceIdentity(), "legacy checkpoints must not claim a verified source");
        assertNull(legacy.getSourceFingerprint(), "legacy checkpoints must not claim verified source contents");
        assertFalse(legacy.getForceOverwrite(), "legacy checkpoints without force policy must resume conservatively");
    }

    @Test
    void concurrentCheckpointUpdatesRemainConsistentAndReadable() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temporaryDirectory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MigrationCheckpointFailureTest"));
        MigrationCheckpoint checkpoint = new MigrationCheckpoint(plugin);
        checkpoint.init(400);

        int workers = 8;
        int failuresPerWorker = 50;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int failure = 0; failure < failuresPerWorker; failure++) {
                        checkpoint.recordFailure("player-" + workerId + "-" + failure, "injected failure");
                    }
                    return null;
                }));
            }

            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        int expectedFailures = workers * failuresPerWorker;
        assertEquals(expectedFailures, checkpoint.getFailedCount());
        assertEquals(expectedFailures, checkpoint.getFailures().size());

        MigrationCheckpoint resumed = new MigrationCheckpoint(plugin);
        assertTrue(resumed.resume(), "concurrent saves must leave a readable checkpoint");
        assertEquals(expectedFailures, resumed.getFailedCount());
        assertEquals(expectedFailures, resumed.getFailures().size());
    }
}
