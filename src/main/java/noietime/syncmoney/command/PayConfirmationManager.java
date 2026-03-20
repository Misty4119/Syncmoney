package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.Constants;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the large-transfer confirmation flow for /pay.
 *
 * <p>When a transfer amount reaches the configured threshold, the player is prompted
 * to confirm with {@code /pay confirm}. This class stores the pending confirmation
 * state and processes the reply.
 */
final class PayConfirmationManager {

    /**
     * Immutable snapshot of a pending confirmation.
     *
     * <p>{@code cooldownAlreadyConsumed}: {@code true} when {@code validateTransfer()} in
     * {@link PayCommand} already charged the cooldown during the initial {@code /pay} call,
     * so {@link #handleConfirmation} must not charge it again.
     */
    record ConfirmInfo(String targetName, BigDecimal amount, long timestamp, boolean cooldownAlreadyConsumed) {}

    private final Syncmoney plugin;
    private final CooldownManager cooldownManager;
    private final PayTransferExecutor transferExecutor;
    private final BigDecimal confirmThreshold;

    private final Map<UUID, AtomicReference<ConfirmInfo>> pendingConfirmations = new ConcurrentHashMap<>();

    PayConfirmationManager(Syncmoney plugin, CooldownManager cooldownManager,
            PayTransferExecutor transferExecutor, BigDecimal confirmThreshold) {
        this.plugin = plugin;
        this.cooldownManager = cooldownManager;
        this.transferExecutor = transferExecutor;
        this.confirmThreshold = confirmThreshold;
    }

    BigDecimal getConfirmThreshold() {
        return confirmThreshold;
    }

    /**
     * Prompt the player to confirm a large transfer and register the pending state.
     */
    boolean requestConfirmation(Player player, String targetName, BigDecimal amount) {
        UUID uuid = player.getUniqueId();

        MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-request"));
        MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-details")
                .replace("{player}", targetName)
                .replace("{amount}", FormatUtil.formatCurrency(amount)));
        MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-hint"));

        ConfirmInfo info = new ConfirmInfo(targetName, amount, System.currentTimeMillis(), true);
        AtomicReference<ConfirmInfo> ref = new AtomicReference<>(info);
        pendingConfirmations.put(uuid, ref);

        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            ref.compareAndSet(info, null);
        }, Constants.CONFIRM_TIMEOUT_TICKS);

        return true;
    }

    /**
     * Process a {@code /pay confirm} reply from the player.
     */
    boolean handleConfirmation(Player player) {
        UUID uuid = player.getUniqueId();
        AtomicReference<ConfirmInfo> ref = pendingConfirmations.remove(uuid);

        if (ref == null) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-expired"));
            return true;
        }

        ConfirmInfo info = ref.getAndSet(null);
        if (info == null) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.confirm-expired"));
            return true;
        }

        if (!info.cooldownAlreadyConsumed() && !cooldownManager.checkAndUpdate(uuid)) {
            MessageHelper.sendMessage(player, plugin.getMessage("pay.cooldown")
                    .replace("{seconds}", String.valueOf(cooldownManager.getRemainingSeconds(uuid))));
            return true;
        }

        transferExecutor.executeTransferAsync(player, info.targetName(), info.amount());
        return true;
    }
}
