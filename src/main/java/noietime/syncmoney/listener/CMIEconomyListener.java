package noietime.syncmoney.listener;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.CMIEconomyHandler;
import noietime.syncmoney.sync.CMIDebounceManager;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CMI Economy Event Listener.
 * Listens for CMI balance change events and triggers synchronization.
 *
 * Uses reflection to avoid compile-time dependency on CMI API.
 * Includes fallback polling mechanism for reliability.
 *
 * [MainThread] This listener runs on main thread.
 */
public final class CMIEconomyListener implements Listener {

    private final Plugin plugin;
    private final CMIEconomyHandler cmiHandler;
    private final SyncmoneyConfig config;
    private final CMIDebounceManager debounceManager;
    private final boolean cmiAvailable;

    private BukkitTask pollingTask;
    private final ConcurrentHashMap<UUID, BigDecimal> lastKnownCMIBalance = new ConcurrentHashMap<>();
    private static final long POLLING_INTERVAL_TICKS = 20L;

    private static Class<?> cmiEventClass = null;
    private static Method getUserMethod = null;
    private static Method getFromMethod = null;
    private static Method getToMethod = null;
    private static Method getSourceMethod = null;

    public CMIEconomyListener(Plugin plugin, CMIEconomyHandler cmiHandler, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.cmiHandler = cmiHandler;
        this.config = config;
        this.debounceManager = new CMIDebounceManager(plugin, config.getCMIDebounceTicks());
        this.cmiAvailable = initCMIReflection();

        if (cmiAvailable) {
            plugin.getLogger().fine("CMI event listener initialized - CMI events will be monitored");
            startPollingFallback();
        } else {
            plugin.getLogger().warning("CMI API not detected - using polling fallback for CMI mode");
            startPollingFallback();
        }
    }

    /**
     * Initialize CMI reflection.
     */
    private boolean initCMIReflection() {
        try {
            cmiEventClass = Class.forName("com.Zrips.CMI.events.CMIUserBalanceChangeEvent");
            getUserMethod = cmiEventClass.getMethod("getUser");
            getFromMethod = cmiEventClass.getMethod("getFrom");
            getToMethod = cmiEventClass.getMethod("getTo");
            getSourceMethod = cmiEventClass.getMethod("getSource");
            return true;
        } catch (ClassNotFoundException e) {
            plugin.getLogger().fine("CMI event class not found (CMI may not be installed): " + e.getMessage());
            return false;
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("CMI event methods not found: " + e.getMessage());
            return false;
        }
    }

    /**
     * Start polling fallback mechanism.
     * This provides a backup in case event-based detection doesn't work.
     */
    private void startPollingFallback() {
        if (cmiHandler == null) return;

        pollingTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    checkAndSyncBalance(uuid);
                }
            } catch (Exception e) {
                if (config.isDebug()) {
                    plugin.getLogger().warning("Polling error: " + e.getMessage());
                }
            }
        }, POLLING_INTERVAL_TICKS, POLLING_INTERVAL_TICKS);

        plugin.getLogger().fine("CMI balance polling started (interval: " + POLLING_INTERVAL_TICKS + " ticks)");
    }

    /**
     * Check and sync balance for a player.
     */
    private void checkAndSyncBalance(UUID uuid) {
        if (cmiHandler == null) return;

        try {
            BigDecimal currentBalance = cmiHandler.getCMIDirectBalance(uuid);
            BigDecimal previousBalance = lastKnownCMIBalance.get(uuid);

            if (previousBalance == null) {
                lastKnownCMIBalance.put(uuid, currentBalance);
                return;
            }

            BigDecimal diff = currentBalance.subtract(previousBalance);
            if (diff.abs().compareTo(BigDecimal.valueOf(0.01)) > 0) {
                boolean isDeposit = diff.compareTo(BigDecimal.ZERO) > 0;
                cmiHandler.handleExternalBalanceChange(uuid, diff, isDeposit, "CMI-POLLING");

                if (config.isDebug()) {
                    plugin.getLogger().fine("[CMI Polling] Player: " + uuid +
                            ", Change: " + (isDeposit ? "+" : "") + diff +
                            ", From: " + previousBalance + ", To: " + currentBalance);
                }
            }

            lastKnownCMIBalance.put(uuid, currentBalance);
        } catch (Exception e) {
            if (config.isDebug()) {
                plugin.getLogger().warning("Error checking balance: " + e.getMessage());
            }
        }
    }

    /**
     * Generic event handler - checks if event is a CMI balance change event.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEvent(Event event) {
        if (cmiHandler == null) {
            return;
        }

        if (cmiAvailable && cmiEventClass != null && cmiEventClass.isInstance(event)) {
            handleCMIEvent(event);
        }
    }

    private void handleCMIEvent(Event event) {
        try {
            Object cmiEvent = event;
            double fromBalance = (double) getFromMethod.invoke(cmiEvent);
            double toBalance = (double) getToMethod.invoke(cmiEvent);

            if (Double.compare(fromBalance, toBalance) == 0) {
                return;
            }

            Object cmiUser = getUserMethod.invoke(cmiEvent);
            UUID uuid = getUUIDFromCMIUser(cmiUser);
            if (uuid == null) {
                return;
            }

            double diff = toBalance - fromBalance;
            boolean isDeposit = diff > 0;

            String sourceName = "Unknown";
            try {
                Object source = getSourceMethod.invoke(cmiEvent);
                if (source != null) {
                    Method getNameMethod = source.getClass().getMethod("getName");
                    sourceName = (String) getNameMethod.invoke(source);
                }
            } catch (Exception ignored) {
            }

            final BigDecimal finalDiff = BigDecimal.valueOf(diff);
            final boolean finalIsDeposit = isDeposit;
            final String finalSourceName = sourceName;
            debounceManager.scheduleDebounced(uuid, () -> {
                cmiHandler.handleExternalBalanceChange(uuid, finalDiff, finalIsDeposit, finalSourceName);
            });

            lastKnownCMIBalance.put(uuid, BigDecimal.valueOf(toBalance));

            if (config.isDebug()) {
                String playerName = getNameFromCMIUser(cmiUser);
                plugin.getLogger().fine("[CMI Event] Player: " + playerName +
                        ", Change: " + (isDeposit ? "+" : "") + diff +
                        ", From: " + fromBalance + ", To: " + toBalance +
                        ", Source: " + sourceName);
            }

            Player player = Bukkit.getServer().getPlayer(uuid);
            if (player != null && player.isOnline() && config.isCrossServerNotificationsEnabled()) {
                sendNotification(player, Math.abs(diff), isDeposit);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error processing CMI event: " + e.getMessage());
        }
    }

    private UUID getUUIDFromCMIUser(Object cmiUser) {
        try {
            Method getUniqueIdMethod = cmiUser.getClass().getMethod("getUniqueId");
            return (UUID) getUniqueIdMethod.invoke(cmiUser);
        } catch (Exception e) {
            return null;
        }
    }

    private String getNameFromCMIUser(Object cmiUser) {
        try {
            Method getNameMethod = cmiUser.getClass().getMethod("getName");
            return (String) getNameMethod.invoke(cmiUser);
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private void sendNotification(Player player, double amount, boolean isDeposit) {
        String messageKey = isDeposit ? "cross-server.money-received" : "cross-server.money-spent";
        String message = ((Syncmoney) plugin).getMessage(messageKey);

        if (message != null) {
            message = message.replace("{amount}", FormatUtil.formatCurrency(amount));
            message = message.replace("{server}", config.getServerName());
            MessageHelper.sendMessage(player, message);
        }
    }

    /**
     * Clean up resources on disable.
     */
    public void shutdown() {
        if (debounceManager != null) {
            debounceManager.cancelAll();
        }
        if (pollingTask != null) {
            pollingTask.cancel();
        }
    }
}
