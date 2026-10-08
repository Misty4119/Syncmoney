package noietime.syncmoney.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.function.Supplier;

/** Only command/display settings can be replaced without rebuilding storage and service owners. */
public final class ConfigReloadPolicy {
    private static final Set<String> LIVE_ROOTS = Set.of("display", "pay", "permissions", "admin-permissions", "debug");
    private ConfigReloadPolicy() {}

    public static List<String> restartRequired(FileConfiguration active, FileConfiguration candidate) {
        Set<String> keys = new TreeSet<>(active.getKeys(true));
        keys.addAll(candidate.getKeys(true));
        return keys.stream().filter(key -> {
            Object before = active.get(key), after = candidate.get(key);
            if (before instanceof ConfigurationSection || after instanceof ConfigurationSection) return false;
            String root = key.split("\\.", 2)[0];
            return !LIVE_ROOTS.contains(root) && !Objects.equals(before, after);
        }).toList();
    }

    /** Applies a candidate only after proving it contains no restart-required changes. */
    public static void applyIfSafe(FileConfiguration active, FileConfiguration candidate, Runnable apply) {
        List<String> changed = restartRequired(active, candidate);
        if (!changed.isEmpty()) {
            throw new IllegalStateException("Restart required; active services unchanged. Settings: "
                    + String.join(", ", changed));
        }
        apply.run();
    }

    /**
     * Installs a parsed candidate, then checks Bukkit's actual installed snapshot before publishing it to services.
     * The file can change after the initial preflight, so a rejected installed snapshot must be rolled back first.
     */
    public static void installIfSafe(FileConfiguration active, FileConfiguration candidate, Runnable install,
            Supplier<FileConfiguration> installedConfig, Runnable restore, Runnable apply) {
        List<String> candidateChanges = restartRequired(active, candidate);
        if (!candidateChanges.isEmpty()) {
            throw restartRequiredFailure(candidateChanges);
        }

        try {
            install.run();
            List<String> installedChanges = restartRequired(active, installedConfig.get());
            if (!installedChanges.isEmpty()) {
                throw restartRequiredFailure(installedChanges);
            }
            apply.run();
        } catch (RuntimeException failure) {
            try {
                restore.run();
            } catch (RuntimeException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    private static IllegalStateException restartRequiredFailure(List<String> changed) {
        return new IllegalStateException("Restart required; active services unchanged. Settings: "
                + String.join(", ", changed));
    }

    public static void restore(FileConfiguration source, FileConfiguration target) {
        for (String key : new TreeSet<>(target.getKeys(false))) {
            target.set(key, null);
        }
        copyConfiguration(source, target);
        target.setDefaults(source.getDefaults());
    }

    private static void copyConfiguration(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            if (source.isConfigurationSection(key)) {
                ConfigurationSection child = target.createSection(key);
                copyConfiguration(source.getConfigurationSection(key), child);
            } else {
                target.set(key, source.get(key));
            }
        }
    }
}
