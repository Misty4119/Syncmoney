package noietime.syncmoney.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;

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

    @Test
    void restartRequiredCandidateIsRejectedBeforeReloadActionRuns() {
        var active = new YamlConfiguration();
        active.set("economy.mode", "local");
        var candidate = new YamlConfiguration();
        candidate.set("economy.mode", "sync");
        AtomicBoolean applied = new AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ConfigReloadPolicy.applyIfSafe(active, candidate, () -> applied.set(true)));

        assertFalse(applied.get());
        assertTrue(failure.getMessage().contains("economy.mode"));
    }

    @Test
    void liveCandidateRunsReloadActionOnce() {
        var active = new YamlConfiguration();
        active.set("display.decimal-places", 2);
        var candidate = new YamlConfiguration();
        candidate.set("display.decimal-places", 4);
        AtomicBoolean applied = new AtomicBoolean();

        assertDoesNotThrow(() -> ConfigReloadPolicy.applyIfSafe(active, candidate,
                () -> applied.set(true)));

        assertTrue(applied.get());
    }

    @Test
    void restartRequiredChangeAfterPreflightRestoresInstalledConfigBeforeRejecting() {
        var active = new YamlConfiguration();
        active.set("economy.mode", "local");
        var candidate = new YamlConfiguration();
        candidate.set("economy.mode", "local");
        candidate.set("display.decimal-places", 4);
        var installed = new YamlConfiguration();
        installed.set("economy.mode", "local");
        AtomicBoolean restored = new AtomicBoolean();
        AtomicBoolean applied = new AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ConfigReloadPolicy.installIfSafe(active, candidate,
                        () -> installed.set("economy.mode", "sync"),
                        () -> installed,
                        () -> {
                            installed.set("economy.mode", active.getString("economy.mode"));
                            restored.set(true);
                        },
                        () -> applied.set(true)));

        assertTrue(failure.getMessage().contains("economy.mode"));
        assertTrue(restored.get());
        assertFalse(applied.get());
        assertEquals("local", installed.getString("economy.mode"));
    }

    @Test
    void applyFailureRestoresInstalledConfigBeforePropagating() {
        var active = new YamlConfiguration();
        active.set("economy.mode", "local");
        active.addDefault("pay.cooldown-seconds", 3);
        var candidate = new YamlConfiguration();
        candidate.set("economy.mode", "local");
        candidate.set("display.decimal-places", 4);
        var installed = new YamlConfiguration();
        installed.set("economy.mode", "local");
        AtomicBoolean restored = new AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ConfigReloadPolicy.installIfSafe(active, candidate,
                        () -> installed.set("display.decimal-places", 4),
                        () -> installed,
                        () -> {
                            ConfigReloadPolicy.restore(active, installed);
                            restored.set(true);
                        },
                        () -> {
                            installed.set("economy.mode", "sync");
                            throw new IllegalStateException("runtime snapshot rejected installed config");
                        }));

        assertEquals("runtime snapshot rejected installed config", failure.getMessage());
        assertTrue(restored.get(), "suppressed restore failure: " + Arrays.toString(failure.getSuppressed()));
        assertEquals("local", installed.getString("economy.mode"));
        assertEquals(0, installed.getInt("display.decimal-places"));
    }

    @Test
    void restoreCopiesThePriorNestedSnapshotAndDefaults() {
        var previous = new YamlConfiguration();
        previous.set("economy.mode", "local");
        previous.set("display.decimal-places", 2);
        previous.addDefault("pay.cooldown-seconds", 3);

        var installed = new YamlConfiguration();
        installed.set("economy.mode", "sync");
        installed.set("display.decimal-places", 4);
        installed.set("extra.option", true);

        ConfigReloadPolicy.restore(previous, installed);

        assertEquals("local", installed.getString("economy.mode"));
        assertEquals(2, installed.getInt("display.decimal-places"));
        assertFalse(installed.contains("extra.option"));
        assertEquals(3, installed.getInt("pay.cooldown-seconds"));
    }
}
