package noietime.syncmoney.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * [SYNC-CONFIG] Player protection configuration settings.
 * [ThreadSafe] - Read-only configuration, no mutable state.
 */
public final class PlayerProtectionConfig {

    private final FileConfiguration config;

    public PlayerProtectionConfig(FileConfiguration config) {
        this.config = config;
    }

    /**
     * [SYNC-CONFIG-070] Whether to enable per-player protection system.
     * Supports both new path and legacy path for backward compatibility.
     */
    public boolean isPlayerProtectionEnabled() {
        if (config.isSet("circuit-breaker.player-protection.enabled")) {
            return config.getBoolean("circuit-breaker.player-protection.enabled", true);
        }
        return config.getBoolean("player-protection.enabled", true);
    }

    /**
     * [SYNC-CONFIG-071] Gets maximum transactions per second per player.
     */
    public int getPlayerProtectionMaxTransactionsPerSecond() {
        if (config.isSet("circuit-breaker.player-protection.rate-limit.max-transactions-per-second")) {
            return config.getInt("circuit-breaker.player-protection.rate-limit.max-transactions-per-second", 5);
        }
        return config.getInt("player-protection.rate-limit.max-transactions-per-second", 5);
    }

    /**
     * [SYNC-CONFIG-072] Gets maximum transactions per minute per player.
     */
    public int getPlayerProtectionMaxTransactionsPerMinute() {
        if (config.isSet("circuit-breaker.player-protection.rate-limit.max-transactions-per-minute")) {
            return config.getInt("circuit-breaker.player-protection.rate-limit.max-transactions-per-minute", 50);
        }
        return config.getInt("player-protection.rate-limit.max-transactions-per-minute", 50);
    }

    /**
     * [SYNC-CONFIG-073] Gets maximum transaction amount per minute per player (0 = disabled).
     * Supports both new path and legacy path for backward compatibility.
     */
    public long getPlayerProtectionMaxAmountPerMinute() {
        if (config.isSet("circuit-breaker.player-protection.rate-limit.max-amount-per-minute")) {
            return config.getLong("circuit-breaker.player-protection.rate-limit.max-amount-per-minute", 1000000);
        }
        return config.getLong("player-protection.rate-limit.max-amount-per-minute", 1000000);
    }

    /**
     * [SYNC-CONFIG-074] Gets warning window in seconds.
     */
    public int getPlayerProtectionWarningWindowSeconds() {
        if (config.isSet("circuit-breaker.player-protection.anomaly-detection.warning-window-seconds")) {
            return config.getInt("circuit-breaker.player-protection.anomaly-detection.warning-window-seconds", 30);
        }
        return config.getInt("player-protection.anomaly-detection.warning-window-seconds", 30);
    }

    /**
     * [SYNC-CONFIG-075] Gets transaction count threshold to trigger WARNING.
     */
    public int getPlayerProtectionWarningThreshold() {
        if (config.isSet("circuit-breaker.player-protection.anomaly-detection.warning-threshold")) {
            return config.getInt("circuit-breaker.player-protection.anomaly-detection.warning-threshold", 30);
        }
        return config.getInt("player-protection.anomaly-detection.warning-threshold", 30);
    }

    /**
     * [SYNC-CONFIG-076] Gets balance change multiplier threshold.
     */
    public double getPlayerProtectionBalanceChangeThreshold() {
        if (config.isSet("circuit-breaker.player-protection.anomaly-detection.balance-change-threshold")) {
            return config.getDouble("circuit-breaker.player-protection.anomaly-detection.balance-change-threshold", 50.0);
        }
        return config.getDouble("player-protection.anomaly-detection.balance-change-threshold", 50.0);
    }

    /**
     * [SYNC-CONFIG-077] Gets lock duration in minutes before auto-unlock attempt.
     */
    public int getPlayerProtectionLockDurationMinutes() {
        if (config.isSet("circuit-breaker.player-protection.auto-unlock.lock-duration-minutes")) {
            return config.getInt("circuit-breaker.player-protection.auto-unlock.lock-duration-minutes", 5);
        }
        return config.getInt("player-protection.auto-unlock.lock-duration-minutes", 5);
    }

    /**
     * [SYNC-CONFIG-078] Gets maximum number of lock extensions.
     */
    public int getPlayerProtectionMaxLockExtensions() {
        if (config.isSet("circuit-breaker.player-protection.auto-unlock.max-lock-extensions")) {
            return config.getInt("circuit-breaker.player-protection.auto-unlock.max-lock-extensions", 3);
        }
        return config.getInt("player-protection.auto-unlock.max-lock-extensions", 3);
    }

    /**
     * [SYNC-CONFIG-079] Gets number of successful transactions required to confirm unlock.
     */
    public int getPlayerProtectionUnlockTestTransactions() {
        if (config.isSet("circuit-breaker.player-protection.auto-unlock.unlock-test-transactions")) {
            return config.getInt("circuit-breaker.player-protection.auto-unlock.unlock-test-transactions", 3);
        }
        return config.getInt("player-protection.auto-unlock.unlock-test-transactions", 3);
    }

    /**
     * [SYNC-CONFIG-080] Whether to enable global lock when total economy spikes.
     * Supports both new path and legacy path for backward compatibility.
     */
    public boolean isPlayerProtectionGlobalLockEnabled() {
        if (config.isSet("circuit-breaker.player-protection.global-lock.enabled")) {
            return config.getBoolean("circuit-breaker.player-protection.global-lock.enabled", true);
        }
        return config.getBoolean("player-protection.global-lock.enabled", true);
    }

    /**
     * [SYNC-CONFIG-081] Gets total inflation threshold for global lock.
     * Supports both new path and legacy path for backward compatibility.
     */
    public double getPlayerProtectionGlobalLockThreshold() {
        if (config.isSet("circuit-breaker.player-protection.global-lock.total-inflation-threshold")) {
            return config.getDouble("circuit-breaker.player-protection.global-lock.total-inflation-threshold", 0.2);
        }
        return config.getDouble("player-protection.global-lock.total-inflation-threshold", 0.2);
    }

    /**
     * [SYNC-CONFIG-082] Whether to enable player protection in LOCAL mode.
     * Supports both new path and legacy path for backward compatibility.
     */
    public boolean isPlayerProtectionEnabledInLocalMode() {
        if (config.isSet("circuit-breaker.player-protection.local-mode.enabled-in-local-mode")) {
            return config.getBoolean("circuit-breaker.player-protection.local-mode.enabled-in-local-mode", false);
        }
        return config.getBoolean("player-protection.local-mode.enabled-in-local-mode", false);
    }

    /**
     * [SYNC-CONFIG-083] Whether to apply relaxed threshold for VAULT transactions.
     * Supports both new path and legacy path for backward compatibility.
     */
    public boolean isPlayerProtectionVaultRelaxedThreshold() {
        if (config.isSet("circuit-breaker.player-protection.local-mode.vault-transaction-handling.relaxed-threshold")) {
            return config.getBoolean("circuit-breaker.player-protection.local-mode.vault-transaction-handling.relaxed-threshold", false);
        }
        return config.getBoolean("player-protection.local-mode.vault-transaction-handling.relaxed-threshold", false);
    }

    /**
     * [SYNC-CONFIG-084] Gets whitelist of Vault event sources to bypass guard.
     * Supports both new path and legacy path for backward compatibility.
     */
    public List<String> getPlayerProtectionVaultBypassWhitelist() {
        if (config.isSet("circuit-breaker.player-protection.local-mode.vault-transaction-handling.bypass-whitelist")) {
            return config.getStringList("circuit-breaker.player-protection.local-mode.vault-transaction-handling.bypass-whitelist");
        }
        return config.getStringList("player-protection.local-mode.vault-transaction-handling.bypass-whitelist");
    }

    /**
     * [SYNC-CONFIG-085] Whether to lock receiver when a transfer occurs.
     * Supports both new path and legacy path for backward compatibility.
     */
    public boolean isPlayerProtectionLockReceiver() {
        if (config.isSet("circuit-breaker.player-protection.transfer-protection.lock-receiver")) {
            return config.getBoolean("circuit-breaker.player-protection.transfer-protection.lock-receiver", true);
        }
        return config.getBoolean("player-protection.transfer-protection.lock-receiver", true);
    }

    /**
     * [SYNC-CONFIG-086] Gets amount threshold for locking receiver.
     * Supports both new path and legacy path for backward compatibility.
     */
    public long getPlayerProtectionReceiverLockThreshold() {
        if (config.isSet("circuit-breaker.player-protection.transfer-protection.receiver-lock-threshold")) {
            return config.getLong("circuit-breaker.player-protection.transfer-protection.receiver-lock-threshold", 0);
        }
        return config.getLong("player-protection.transfer-protection.receiver-lock-threshold", 0);
    }
}
