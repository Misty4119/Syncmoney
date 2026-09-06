package noietime.syncmoney.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;

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
}
