package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import noietime.syncmoney.event.PostTransactionEvent;
import noietime.syncmoney.event.SyncmoneyEventBus;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Executes /pay transfers and routes notifications for Syncmoney-owned economy modes. */
final class PayTransferExecutor {

    private static final long IDEMPOTENCY_KEY_TTL_MILLIS = 60_000;
    private static final String IN_FLIGHT_SUFFIX = ":IN_FLIGHT";
    private static final String RECEIPT_CONFIRMED_SUFFIX = ":RECEIPT_CONFIRMED";
    private static final String SENT_UNKNOWN_SUFFIX = ":SENT_UNKNOWN";
    private static final String CONFIRMED_QUARANTINE_SUFFIX = ":CONFIRMED_QUARANTINE";

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final NameResolver nameResolver;
    private final TransferLockManager lockManager;
    private final boolean localMode;

    private final Map<UUID, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPayTransfer> pendingPairedTransfers = new ConcurrentHashMap<>();
    private volatile boolean completionListenerRegistered;

    private enum PayTransferOutcome {
        CONFIRMED,
        REJECTED,
        UNKNOWN,
        CONFIRMED_QUARANTINED
    }

    private record PayTransferCompletion(PayTransferOutcome outcome, BigDecimal senderBalance) { }

    private record PendingPayTransfer(Player sender, String senderName, UUID senderUuid, UUID targetUuid,
            String targetName, BigDecimal amount, String idempotencyKey) { }

    PayTransferExecutor(Syncmoney plugin, SyncmoneyConfig config,
            EconomyFacade economyFacade, NameResolver nameResolver,
            TransferLockManager lockManager, boolean localMode) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.nameResolver = nameResolver;
        this.lockManager = lockManager;
        this.localMode = localMode;
    }

    void executeTransferAsync(Player sender, String targetName, BigDecimal amount) {
        if (rejectCmiTransfer(sender)) return;
        if (!ensureCompletionListenerRegistered()) {
            sendTransferMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", "Transfer outcome reporting is unavailable; no transfer was accepted"));
            return;
        }
        executePairedTransferAsync(sender, targetName, amount);
    }

    boolean rejectCmiTransfer(Player sender) {
        if (config == null || !config.isCMIMode()) return false;
        sendTransferMessage(sender, plugin.getMessage("general.cmi-authority-command-disabled"));
        return true;
    }

    private void executePairedTransferAsync(Player sender, String targetName, BigDecimal amount) {
        UUID senderUuid = sender.getUniqueId();
        String senderName = sender.getName();
        String idempotencyKey = senderUuid + ":" + targetName + ":" + amount.toPlainString();

        cleanupIdempotencyKeys(senderUuid, idempotencyKey);
        String existingKey = idempotencyKeys.putIfAbsent(senderUuid, idempotencyKey + IN_FLIGHT_SUFFIX);
        if (existingKey != null) {
            MessageHelper.sendMessage(sender, isSentUnknownKey(existingKey)
                    ? unknownTransferMessage()
                    : isConfirmedQuarantineKey(existingKey)
                            ? confirmedTransferFollowupFailureMessage()
                            : plugin.getMessage("pay.duplicate-transfer"));
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            MigrationLock.WriteLease migrationWrite = MigrationLock.tryAcquireWrite();
            if (migrationWrite == null) {
                sendTransferMessage(sender, plugin.getMessage("pay.lock-failed"));
                completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                return;
            }

            try (migrationWrite) {
                Optional<UUID> targetUuidOpt = resolveTargetPlayer(targetName);
                if (targetUuidOpt.isEmpty()) {
                    sendTransferMessage(sender, plugin.getMessage("pay.target-not-found")
                            .replace("{player}", targetName));
                    completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                    return;
                }

                UUID targetUuid = targetUuidOpt.get();
                if (senderUuid.equals(targetUuid)) {
                    sendTransferMessage(sender, plugin.getMessage("pay.self-transfer"));
                    completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                    return;
                }

                if (!acquireTransferLock(senderUuid, targetUuid)) {
                    sendTransferMessage(sender, plugin.getMessage("pay.lock-failed"));
                    completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                    return;
                }

                boolean accepted = false;
                PendingPayTransfer pending = null;
                try {
                    if (!localMode) {
                        pending = new PendingPayTransfer(sender, senderName, senderUuid, targetUuid,
                                targetName, amount, idempotencyKey);
                        PendingPayTransfer existing = pendingPairedTransfers.putIfAbsent(senderUuid, pending);
                        if (existing != null) {
                            sendTransferMessage(sender, unknownTransferMessage());
                            quarantineSentUnknown(idempotencyKeys, senderUuid, idempotencyKey);
                            return;
                        }
                    }
                    BigDecimal senderBalance = localMode
                            ? economyFacade.atomicTransfer(senderUuid, targetUuid,
                                    amount, EconomyEvent.EventSource.PLAYER_TRANSFER)
                            : economyFacade.atomicTransfer(senderUuid, targetUuid,
                                    amount, EconomyEvent.EventSource.PLAYER_TRANSFER);
                    if (senderBalance == null || senderBalance.signum() < 0) {
                        if (pending != null) pendingPairedTransfers.remove(senderUuid, pending);
                        BigDecimal currentBalance = economyFacade.getBalanceForPlaceholder(senderUuid);
                        if (currentBalance != null && currentBalance.compareTo(amount) < 0) {
                            sendTransferMessage(sender, plugin.getMessage("pay.insufficient-funds")
                                    .replace("{balance}", FormatUtil.formatCurrency(currentBalance))
                                    .replace("{amount}", FormatUtil.formatCurrency(amount)));
                        } else {
                            sendTransferMessage(sender, plugin.getMessage("pay.transfer-failed")
                                    .replace("{error}", "Transfer was not accepted; one or both accounts are unavailable"));
                        }
                        completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                        return;
                    }

                    accepted = true;
                    if (localMode) {
                        completeAcceptedTransfer(idempotencyKeys, senderUuid, idempotencyKey,
                                System.currentTimeMillis());
                        notifyTransferSuccess(sender, senderName, targetName, amount, senderBalance.doubleValue());
                        plugin.getLogger().fine("Accepted LOCAL /pay transfer: " + senderName + " -> " + targetName
                                + " " + amount.toPlainString() + " (sender balance: " + senderBalance + ")");
                    } else {
                        plugin.getLogger().fine("Queued paired /pay transfer pending authority result: "
                                + senderName + " -> " + targetName + " " + amount.toPlainString());
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().severe((accepted ? "Paired /pay transfer was accepted but follow-up failed: "
                            : "Paired /pay transfer failed: ") + e.getMessage());
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Paired /pay transfer processing stacktrace", e);
                    if (!localMode && pending != null && pendingPairedTransfers.remove(senderUuid, pending)) {
                        quarantineSentUnknown(idempotencyKeys, senderUuid, idempotencyKey);
                        sendTransferMessage(sender, unknownTransferMessage());
                    } else {
                        sendTransferMessage(sender, plugin.getMessage("pay.transfer-failed")
                                .replace("{error}", accepted
                                        ? "Transfer was accepted; check the account balance before retrying"
                                        : "Transfer was not confirmed; check the account balance before retrying"));
                    }
                    if (!accepted && localMode) {
                        completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                    }
                } finally {
                    releaseTransferLock(senderUuid, targetUuid);
                }
            }
        });
    }

    private synchronized boolean ensureCompletionListenerRegistered() {
        if (localMode || config == null) return true;
        if (completionListenerRegistered) return true;
        if (!SyncmoneyEventBus.isInitialized()) return false;
        SyncmoneyEventBus.getInstance().register(PostTransactionEvent.class, this::onPostTransactionEvent);
        completionListenerRegistered = true;
        return true;
    }

    private void onPostTransactionEvent(PostTransactionEvent event) {
        if (event == null || event.getType() != AsyncPreTransactionEvent.TransactionType.WITHDRAW
                || !EconomyEvent.EventSource.PLAYER_TRANSFER.name().equals(event.getSource())
                || event.getPlayerUuid() == null) {
            return;
        }
        PendingPayTransfer pending = pendingPairedTransfers.get(event.getPlayerUuid());
        if (pending == null || event.getAmount() == null
                || pending.amount().compareTo(event.getAmount()) != 0
                || event.getTargetUuid() != null && !pending.targetUuid().equals(event.getTargetUuid())) {
            return;
        }
        if (!pendingPairedTransfers.remove(pending.senderUuid(), pending)) return;

        String error = event.getErrorMessage();
        PayTransferOutcome outcome;
        if (event.isSuccess()) {
            outcome = "CONFIRMED_QUARANTINED".equals(error)
                    ? PayTransferOutcome.CONFIRMED_QUARANTINED : PayTransferOutcome.CONFIRMED;
        } else if ("SENT_UNKNOWN".equals(error)) {
            outcome = PayTransferOutcome.UNKNOWN;
        } else {
            outcome = PayTransferOutcome.REJECTED;
        }
        completePairedTransfer(pending, new PayTransferCompletion(outcome, event.getBalanceAfter()));
    }

    private void completePairedTransfer(PendingPayTransfer pending, PayTransferCompletion completion) {
        if (completion == null) return;
        Player sender = pending.sender();
        String senderName = pending.senderName();
        UUID senderUuid = pending.senderUuid();
        String idempotencyKey = pending.idempotencyKey();
        String targetName = pending.targetName();
        BigDecimal amount = pending.amount();
        switch (completion.outcome()) {
            case CONFIRMED -> {
                if (completion.senderBalance() == null) {
                    quarantineSentUnknown(idempotencyKeys, senderUuid, idempotencyKey);
                    sendTransferMessage(sender, unknownTransferMessage());
                    return;
                }
                completeAcceptedTransfer(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
                notifyTransferSuccess(sender, senderName, targetName, amount,
                        completion.senderBalance().doubleValue());
            }
            case REJECTED -> {
                if (completion.senderBalance() != null && completion.senderBalance().compareTo(amount) < 0) {
                    sendTransferMessage(sender, plugin.getMessage("pay.insufficient-funds")
                            .replace("{balance}", FormatUtil.formatCurrency(completion.senderBalance()))
                            .replace("{amount}", FormatUtil.formatCurrency(amount)));
                } else {
                    sendTransferMessage(sender, plugin.getMessage("pay.transfer-failed")
                            .replace("{error}", "Transfer was rejected by authority; no transfer was applied"));
                }
                completeNotApplied(idempotencyKeys, senderUuid, idempotencyKey, System.currentTimeMillis());
            }
            case UNKNOWN -> {
                quarantineSentUnknown(idempotencyKeys, senderUuid, idempotencyKey);
                sendTransferMessage(sender, unknownTransferMessage());
            }
            case CONFIRMED_QUARANTINED -> {
                quarantineConfirmedReceipt(idempotencyKeys, senderUuid, idempotencyKey);
                sendTransferMessage(sender, confirmedTransferFollowupFailureMessage());
            }
        }
    }

    private void sendTransferMessage(Player sender, String message) {
        if (sender == null || message == null || message.isEmpty()) return;
        try {
            sender.getScheduler().run(plugin, task -> MessageHelper.sendMessage(sender, message), null);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not schedule /pay status message: " + e.getMessage());
        }
    }

    void notifyTransferSuccess(Player sender, String senderName, String targetName,
            BigDecimal amount, double newBalance) {
        String senderMsg = plugin.getMessage("pay.success-sender")
                .replace("{player}", targetName)
                .replace("{amount}", amount.toPlainString())
                .replace("{balance}", FormatUtil.formatCurrency(newBalance));

        sender.getScheduler().run(plugin, task -> MessageHelper.sendMessage(sender, senderMsg), null);

        org.bukkit.Server server = plugin.getServer();
        if (server == null) return;
        String receiverMsg = plugin.getMessage("pay.success-receiver")
                .replace("{player}", senderName)
                .replace("{amount}", amount.toPlainString());
        server.getGlobalRegionScheduler().run(plugin, task -> {
            Player target = server.getPlayerExact(targetName);
            if (target != null) {
                target.getScheduler().run(plugin,
                        entityTask -> {
                            if (target.isOnline()) MessageHelper.sendMessage(target, receiverMsg);
                        }, null);
            }
        });
    }





    static DbWriteQueue.DbWriteTask createTransferDbTask(UUID senderUuid, String senderName,
            BigDecimal senderBalance, long senderVersion, UUID targetUuid, String targetName,
            BigDecimal targetBalance, long targetVersion, String serverName, Instant timestamp) {
        ReplicationReceipt receipt = new ReplicationReceipt(UUID.randomUUID().toString(), List.of(
                new ConfirmedAccount(senderUuid, senderBalance, senderVersion),
                new ConfirmedAccount(targetUuid, targetBalance, targetVersion)));
        String normalizedServerName = serverName != null ? serverName : "unknown";
        List<DbWriteQueue.AccountWrite> accountWrites = List.of(
                new DbWriteQueue.AccountWrite(receipt.accounts().get(0), senderName,
                        normalizedServerName, timestamp),
                new DbWriteQueue.AccountWrite(receipt.accounts().get(1), targetName,
                        normalizedServerName, timestamp));
        return new DbWriteQueue.DbWriteTask(receipt.operationId(), accountWrites);
    }

    private Optional<UUID> resolveTargetPlayer(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            plugin.getLogger().warning("resolveTargetPlayer: target name is null or blank");
            return Optional.empty();
        }
        try {
            Optional<UUID> result = nameResolver.resolve(targetName);
            if (result.isEmpty()) {
                plugin.getLogger().fine("resolveTargetPlayer: player not found - " + targetName);
            }
            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("resolveTargetPlayer: error resolving " + targetName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private boolean acquireTransferLock(UUID senderUuid, UUID targetUuid) {
        return lockManager.acquireDualLock(senderUuid, targetUuid);
    }

    private void releaseTransferLock(UUID senderUuid, UUID targetUuid) {
        lockManager.releaseDualLock(senderUuid, targetUuid);
    }

    private void cleanupIdempotencyKeys(UUID senderUuid, String currentKey) {
        cleanupIdempotencyKeys(idempotencyKeys, senderUuid, currentKey, System.currentTimeMillis());
    }

    static void quarantineSentUnknown(Map<UUID, String> keys, UUID senderUuid, String operationKey) {
        keys.computeIfPresent(senderUuid, (uuid, current) -> belongsToOperation(current, operationKey)
                ? operationKey + SENT_UNKNOWN_SUFFIX
                : current);
    }

    static void markReceiptConfirmed(Map<UUID, String> keys, UUID senderUuid, String operationKey) {
        keys.computeIfPresent(senderUuid, (uuid, current) -> {
            if (!belongsToOperation(current, operationKey) || isSentUnknownKey(current)) {
                return current;
            }
            return operationKey + RECEIPT_CONFIRMED_SUFFIX;
        });
    }

    static void completeConfirmedTransfer(Map<UUID, String> keys, UUID senderUuid, String operationKey,
            long completedAt) {
        keys.computeIfPresent(senderUuid, (uuid, current) -> current.equals(operationKey + RECEIPT_CONFIRMED_SUFFIX)
                ? operationKey + ":" + completedAt
                : current);
    }

    static void completeAcceptedTransfer(Map<UUID, String> keys, UUID senderUuid, String operationKey,
            long completedAt) {
        keys.computeIfPresent(senderUuid, (uuid, current) -> current.equals(operationKey + IN_FLIGHT_SUFFIX)
                ? operationKey + ":" + completedAt
                : current);
    }

    static void completeNotApplied(Map<UUID, String> keys, UUID senderUuid, String operationKey, long completedAt) {
        keys.computeIfPresent(senderUuid, (uuid, current) -> current.equals(operationKey + IN_FLIGHT_SUFFIX)
                ? operationKey + ":" + completedAt
                : current);
    }

    static void quarantineConfirmedReceipt(Map<UUID, String> keys, UUID senderUuid, String operationKey) {
        keys.computeIfPresent(senderUuid, (uuid, current) -> belongsToOperation(current, operationKey)
                ? operationKey + CONFIRMED_QUARANTINE_SUFFIX
                : current);
    }

    static void cleanupIdempotencyKeys(Map<UUID, String> keys, UUID senderUuid, String currentKey, long now) {
        keys.compute(senderUuid, (uuid, existingKey) -> {
            if (existingKey == null) {
                return null;
            }
            if (isNonExpiringKey(existingKey)) {
                return existingKey;
            }
            String prefix = currentKey + ":";
            if (!existingKey.startsWith(prefix)) {
                return null;
            }
            try {
                long timestamp = Long.parseLong(existingKey.substring(prefix.length()));
                return now - timestamp > IDEMPOTENCY_KEY_TTL_MILLIS ? null : existingKey;
            } catch (NumberFormatException e) {
                return null;
            }
        });
    }

    static boolean isSentUnknownKey(String key) {
        return key != null && key.endsWith(SENT_UNKNOWN_SUFFIX);
    }

    static boolean isConfirmedQuarantineKey(String key) {
        return key != null && key.endsWith(CONFIRMED_QUARANTINE_SUFFIX);
    }

    private static boolean isNonExpiringKey(String key) {
        return key.endsWith(IN_FLIGHT_SUFFIX) || key.endsWith(RECEIPT_CONFIRMED_SUFFIX)
                || isSentUnknownKey(key) || isConfirmedQuarantineKey(key);
    }

    private static boolean belongsToOperation(String current, String operationKey) {
        return current.equals(operationKey + IN_FLIGHT_SUFFIX)
                || current.equals(operationKey + RECEIPT_CONFIRMED_SUFFIX)
                || current.equals(operationKey + SENT_UNKNOWN_SUFFIX)
                || current.equals(operationKey + CONFIRMED_QUARANTINE_SUFFIX)
                || current.startsWith(operationKey + ":");
    }

    private String unknownTransferMessage() {
        return plugin.getMessage("pay.transfer-failed").replace("{error}",
                "Previous transfer outcome is unknown. Further /pay operations are blocked until reconciliation.");
    }

    private String confirmedTransferFollowupFailureMessage() {
        return plugin.getMessage("pay.transfer-failed").replace("{error}",
                "Redis confirmed the transfer, but follow-up processing failed. Further /pay operations are blocked "
                        + "until reconciliation or restart; do not retry this transfer.");
    }
}
