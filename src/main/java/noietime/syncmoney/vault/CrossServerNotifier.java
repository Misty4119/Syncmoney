package noietime.syncmoney.vault;

import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;

/**
 * [SYNC-VAULT-018] Vault-internal helper that centralizes the "notify the online
 * player about a balance change" cross-cutting concern.
 *
 * <p>Previously this pattern (online check + {@code instanceof Syncmoney} +
 * {@code getMessage} + placeholder substitution + {@code MessageHelper.sendMessage})
 * was duplicated across {@link VaultProviderCore} and {@link VaultTransferHandler}.
 * This class keeps the behavior byte-for-byte identical while removing duplication.
 *
 * <p>Scope: intentionally limited to the Vault package; other modules must not be
 * forced to depend on it.
 */
final class CrossServerNotifier {

    private CrossServerNotifier() {
    }

    /**
     * Sends a balance-change message to the player if they are online.
     * Casting semantics match the original inline code ({@code (Player) player}).
     *
     * @param plugin     owning plugin (must be {@code Syncmoney} for a message to be sent)
     * @param player     target player (may be {@code null} / offline)
     * @param messageKey message key resolved via {@code Syncmoney.getMessage}
     * @param amount     amount used for the {@code {amount}} placeholder
     * @param balance    balance used for the {@code {balance}} placeholder
     */
    static void notifyBalanceChange(Plugin plugin, OfflinePlayer player, String messageKey,
                                    BigDecimal amount, BigDecimal balance) {
        if (player == null || !player.isOnline()) {
            return;
        }
        notifyBalanceChange(plugin, (Player) player, messageKey, amount, balance);
    }

    static void notifyBalanceChange(Plugin plugin, Player player, String messageKey,
                                    BigDecimal amount, BigDecimal balance) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!(plugin instanceof noietime.syncmoney.Syncmoney syncMoneyPlugin)) {
            return;
        }
        String message = syncMoneyPlugin.getMessage(messageKey);
        if (message == null) {
            return;
        }
        message = message.replace("{amount}", FormatUtil.formatCurrency(amount));
        message = message.replace("{balance}", FormatUtil.formatCurrency(balance));
        MessageHelper.sendMessage(player, message);
    }
}
