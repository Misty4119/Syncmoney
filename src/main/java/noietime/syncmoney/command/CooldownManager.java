package noietime.syncmoney.command;

import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Transfer cooldown manager.
 * Records and checks player transfer cooldown time to prevent money farming.
 *
 * [MainThread] Should operate on main thread
 */
public final class CooldownManager {

    private final ConcurrentMap<UUID, Long> cooldowns;
    private final long cooldownMillis;

    public CooldownManager(Plugin plugin, int cooldownSeconds) {
        this.cooldownMillis = cooldownSeconds * 1000L;
        this.cooldowns = new ConcurrentHashMap<>();
    }

    /**
     * Check and update cooldown time.
     * @return true if transfer is allowed (no cooldown or cooldown expired)
     */
    public boolean checkAndUpdate(UUID uuid) {
        if (cooldownMillis <= 0) return true;

        Long lastTime = cooldowns.get(uuid);
        long now = System.currentTimeMillis();

        if (lastTime == null || (now - lastTime) >= cooldownMillis) {
            cooldowns.put(uuid, now);
            return true;
        }
        return false;
    }

    /**
     * Get remaining cooldown seconds.
     */
    public long getRemainingSeconds(UUID uuid) {
        if (cooldownMillis <= 0) return 0;

        Long lastTime = cooldowns.get(uuid);
        if (lastTime == null) return 0;

        long remaining = cooldownMillis - (System.currentTimeMillis() - lastTime);
        return remaining > 0 ? (remaining / 1000) + 1 : 0;
    }

    /**
     * Clear cooldown (for admin use).
     */
    public void clearCooldown(UUID uuid) {
        cooldowns.remove(uuid);
    }

    /**
     * Get cooldown time in milliseconds.
     */
    public long getCooldownMillis() {
        return cooldownMillis;
    }
}
