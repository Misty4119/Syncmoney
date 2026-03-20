package noietime.syncmoney.command;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.EconomyWriteQueue;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.storage.TransferLockManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.sync.PubsubSubscriber;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.MessageHelper;
import noietime.syncmoney.util.NumericUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes /pay transfers for both LOCAL and Redis (Lua) modes.
 * Also handles idempotency protection, post-transfer state updates, and notifications.
 */
final class PayTransferExecutor {

    private static final long IDEMPOTENCY_KEY_TTL_MILLIS = 60_000;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final EconomyFacade economyFacade;
    private final CacheManager cacheManager;
    private final RedisManager redisManager;
    private final NameResolver nameResolver;
    private final TransferLockManager lockManager;
    private final DbWriteQueue dbWriteQueue;
    private final EconomyWriteQueue writeQueue;
    private final PubsubSubscriber pubsubSubscriber;
    private final BaltopManager baltopManager;
    private final PayLuaScriptManager luaScriptManager;
    private final boolean localMode;

    private final Map<UUID, String> idempotencyKeys = new ConcurrentHashMap<>();

    PayTransferExecutor(Syncmoney plugin, SyncmoneyConfig config,
            EconomyFacade economyFacade, CacheManager cacheManager,
            RedisManager redisManager, NameResolver nameResolver,
            TransferLockManager lockManager, DbWriteQueue dbWriteQueue,
            EconomyWriteQueue writeQueue, PubsubSubscriber pubsubSubscriber,
            BaltopManager baltopManager, PayLuaScriptManager luaScriptManager,
            boolean localMode) {
        this.plugin = plugin;
        this.config = config;
        this.economyFacade = economyFacade;
        this.cacheManager = cacheManager;
        this.redisManager = redisManager;
        this.nameResolver = nameResolver;
        this.lockManager = lockManager;
        this.dbWriteQueue = dbWriteQueue;
        this.writeQueue = writeQueue;
        this.pubsubSubscriber = pubsubSubscriber;
        this.baltopManager = baltopManager;
        this.luaScriptManager = luaScriptManager;
        this.localMode = localMode;
    }

    void executeTransferAsync(Player sender, String targetName, BigDecimal amount) {
        if (localMode) {
            executeLocalTransfer(sender, targetName, amount);
            return;
        }

        UUID senderUuid = sender.getUniqueId();
        String senderName = sender.getName();

        String idempotencyKey = senderUuid + ":" + targetName + ":" + amount.toPlainString();
        long currentTime = System.currentTimeMillis();

        cleanupIdempotencyKeys(senderUuid, idempotencyKey);

        String existingKey = idempotencyKeys.putIfAbsent(senderUuid, idempotencyKey + ":" + currentTime);
        if (existingKey != null && existingKey.startsWith(idempotencyKey)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.duplicate-transfer"));
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            Optional<UUID> targetUuidOpt = resolveTargetPlayer(targetName);
            if (targetUuidOpt.isEmpty()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.target-not-found")
                        .replace("{player}", targetName));
                return;
            }

            UUID targetUuid = targetUuidOpt.get();

            boolean lockAcquired = acquireTransferLock(senderUuid, targetUuid);
            if (!lockAcquired) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.lock-failed"));
                return;
            }

            try {
                Object result = executeLuaTransfer(senderUuid, targetUuid, amount);

                if (result instanceof java.util.List) {
                    handleTransferSuccess(sender, senderName, targetName, amount, result);
                } else if (result instanceof String) {
                    BigDecimal senderBalance = economyFacade.getBalance(senderUuid);
                    handleTransferError(sender, result, senderBalance, amount);
                }
            } catch (Exception e) {
                handleTransferException(sender, e);
                cleanupIdempotencyKeys(senderUuid, idempotencyKey);
            } finally {
                releaseTransferLock(senderUuid, targetUuid);
            }
        });
    }





    private void executeLocalTransfer(Player sender, String targetName, BigDecimal amount) {
        UUID senderUuid = sender.getUniqueId();
        String senderName = sender.getName();

        String idempotencyKey = senderUuid + ":" + targetName + ":" + amount.toPlainString();
        long currentTime = System.currentTimeMillis();

        cleanupIdempotencyKeys(senderUuid, idempotencyKey);

        String existingKey = idempotencyKeys.putIfAbsent(senderUuid, idempotencyKey + ":" + currentTime);
        if (existingKey != null && existingKey.startsWith(idempotencyKey)) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.duplicate-transfer"));
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, (task) -> {
            Optional<UUID> targetUuidOpt = nameResolver.resolve(targetName);
            if (targetUuidOpt.isEmpty()) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.target-not-found")
                        .replace("{player}", targetName));
                return;
            }

            UUID targetUuid = targetUuidOpt.get();

            if (!lockManager.acquireDualLock(senderUuid, targetUuid)) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.lock-failed"));
                return;
            }

            try {
                BigDecimal senderBalance = economyFacade.getBalance(senderUuid);

                if (senderBalance.compareTo(amount) < 0) {
                    MessageHelper.sendMessage(sender, plugin.getMessage("pay.insufficient-funds")
                            .replace("{balance}", FormatUtil.formatCurrency(senderBalance)));
                    return;
                }

                BigDecimal senderNewBalance = economyFacade.withdraw(senderUuid, amount,
                        EconomyEvent.EventSource.PLAYER_TRANSFER);

                if (senderNewBalance.compareTo(BigDecimal.ZERO) < 0) {
                    if (economyFacade.isPlayerLocked(senderUuid)) {
                        MessageHelper.sendMessage(sender, plugin.getMessage("pay.account-locked"));
                    } else {
                        MessageHelper.sendMessage(sender, plugin.getMessage("pay.error"));
                    }
                    return;
                }

                BigDecimal targetNewBalance = economyFacade.deposit(targetUuid, amount,
                        EconomyEvent.EventSource.PLAYER_TRANSFER);

                if (targetNewBalance.compareTo(BigDecimal.ZERO) < 0) {
                    plugin.getLogger().warning("Local transfer deposit failed for target " + targetUuid
                            + ", rolling back sender " + senderUuid);
                    economyFacade.deposit(senderUuid, amount, EconomyEvent.EventSource.ADMIN_GIVE);
                    MessageHelper.sendMessage(sender, plugin.getMessage("pay.error"));
                    return;
                }

                if (baltopManager != null) {
                    baltopManager.updatePlayerRank(senderUuid, senderNewBalance.doubleValue());
                    baltopManager.updatePlayerRank(targetUuid, targetNewBalance.doubleValue());
                }

                notifyTransferSuccess(sender, targetName, amount, senderNewBalance.doubleValue());

                plugin.getLogger().fine("Local Transfer: " + senderName + " -> " + targetName +
                        " " + amount.toPlainString() + " (sender new balance: " + senderNewBalance + ")");

            } catch (Exception e) {
                plugin.getLogger().severe("Local transfer error: " + e.getMessage());
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.error"));
            } finally {
                lockManager.releaseDualLock(senderUuid, targetUuid);
            }
        });
    }





    private Object executeLuaTransfer(UUID senderUuid, UUID targetUuid, BigDecimal amount) throws Exception {
        String senderKey = "syncmoney:balance:" + senderUuid;
        String senderVersionKey = "syncmoney:version:" + senderUuid;
        String targetKey = "syncmoney:balance:" + targetUuid;
        String targetVersionKey = "syncmoney:version:" + targetUuid;

        try (var jedis = redisManager.getResource()) {
            return jedis.evalsha(
                    luaScriptManager.getTransferScriptSha(),
                    4,
                    senderKey, senderVersionKey, targetKey, targetVersionKey,
                    amount.toPlainString());
        }
    }

    private void handleTransferSuccess(Player sender, String senderName, String targetName,
            BigDecimal amount, Object result) {
        @SuppressWarnings("unchecked")
        var list = (java.util.List<?>) result;

        if (list.size() < 4) {
            plugin.getLogger().severe("Lua script returned insufficient elements: " + list.size());
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", "Internal error: invalid response"));
            return;
        }

        BigDecimal senderNewBalanceBD = NumericUtil.normalize(list.get(0).toString());
        BigDecimal targetNewBalanceBD = NumericUtil.normalize(list.get(1).toString());
        double senderNewBalance = senderNewBalanceBD.doubleValue();
        double targetNewBalance = targetNewBalanceBD.doubleValue();
        long senderVersion = Long.parseLong(list.get(2).toString());
        long targetVersion = Long.parseLong(list.get(3).toString());

        UUID senderUuid = sender.getUniqueId();
        UUID targetUuid = sender.getServer().getPlayerUniqueId(targetName);
        if (targetUuid == null) {
            try {
                if (list.size() > 4) {
                    targetUuid = UUID.fromString(list.get(4).toString());
                } else {
                    var offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(targetName);
                    if (offlinePlayer != null) {
                        targetUuid = offlinePlayer.getUniqueId();
                    }
                }
            } catch (Exception e) {
                MessageHelper.sendMessage(sender, plugin.getMessage("pay.target-uuid-not-found")
                        .replace("{player}", targetName));
                plugin.getLogger().warning("Failed to get UUID for player " + targetName + ": " + e.getMessage());
                return;
            }
        }

        if (targetUuid == null) {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.target-not-found")
                    .replace("{player}", targetName));
            return;
        }

        economyFacade.updateMemoryState(senderUuid, senderNewBalance, senderVersion);
        economyFacade.updateMemoryState(targetUuid, targetNewBalance, targetVersion);
        cacheManager.updateMemoryCache(senderUuid, senderNewBalance, senderVersion);
        cacheManager.updateMemoryCache(targetUuid, targetNewBalance, targetVersion);

        if (dbWriteQueue != null) {
            String serverName = config.getServerName();
            dbWriteQueue.offer(createDbTask(senderUuid, senderName, senderNewBalance, senderVersion, serverName));
            dbWriteQueue.offer(createDbTask(targetUuid, targetName, targetNewBalance, targetVersion, serverName));
        }

        notifyTransferSuccess(sender, targetName, amount, senderNewBalance);
        plugin.getLogger().fine("Transfer: " + senderName + " -> " + targetName +
                " " + amount.toPlainString() + " (sender new balance: " + senderNewBalance + ")");

        publishTransferEvents(senderUuid, senderName, targetUuid, senderNewBalance, targetNewBalance, amount);

        if (baltopManager != null) {
            baltopManager.updatePlayerRank(senderUuid, senderNewBalance);
            baltopManager.updatePlayerRank(targetUuid, targetNewBalance);
        }

        if (writeQueue != null) {
            try {
                if (!writeQueue.offer(createTransferEvent(senderUuid, amount.negate(), senderNewBalance, senderVersion, "TRANSFER_OUT"))) {
                    plugin.getLogger().warning("EconomyWriteQueue full: failed to queue TRANSFER_OUT event for " + senderName);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to queue TRANSFER_OUT event: " + e.getMessage());
            }
            try {
                if (!writeQueue.offer(createTransferEvent(targetUuid, amount, targetNewBalance, targetVersion, "TRANSFER_IN"))) {
                    plugin.getLogger().warning("EconomyWriteQueue full: failed to queue TRANSFER_IN event for " + targetName);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to queue TRANSFER_IN event: " + e.getMessage());
            }
        }
    }

    private void handleTransferError(Player sender, Object result, BigDecimal senderBalance, BigDecimal amount) {
        String error = result.toString();
        if (error.contains("INSUFFICIENT_FUNDS")) {
            String msg = plugin.getMessage("pay.insufficient-funds")
                    .replace("{balance}", FormatUtil.formatCurrency(senderBalance))
                    .replace("{amount}", FormatUtil.formatCurrency(amount));
            MessageHelper.sendMessage(sender, msg);
        } else {
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", error));
        }
    }

    private void handleTransferException(Player sender, Exception e) {
        String errorMsg = e.getMessage();


        if (errorMsg != null && errorMsg.contains("INSUFFICIENT_FUNDS")) {
            double senderBalance = economyFacade.getBalanceAsDouble(sender.getUniqueId());
            String msg = plugin.getMessage("pay.insufficient-funds")
                    .replace("{balance}", FormatUtil.formatCurrency(senderBalance))
                    .replace("{amount}", "?");
            MessageHelper.sendMessage(sender, msg);
            return;
        }

        if (luaScriptManager.handleError(errorMsg)) {
            plugin.getLogger().warning("Lua script error occurred, attempting to retry transfer...");
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.transfer-failed")
                    .replace("{error}", "Script reloaded, please retry"));
        } else {
            plugin.getLogger().severe("Transfer error: " + errorMsg);
            MessageHelper.sendMessage(sender, plugin.getMessage("pay.error"));
        }
    }





    void notifyTransferSuccess(Player sender, String targetName, BigDecimal amount, double newBalance) {
        String senderMsg = plugin.getMessage("pay.success-sender")
                .replace("{player}", targetName)
                .replace("{amount}", amount.toPlainString())
                .replace("{balance}", FormatUtil.formatCurrency(newBalance));

        sender.getScheduler().run(plugin, task -> MessageHelper.sendMessage(sender, senderMsg), null);

        Player target = Bukkit.getServer().getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            String receiverMsg = plugin.getMessage("pay.success-receiver")
                    .replace("{player}", sender.getName())
                    .replace("{amount}", amount.toPlainString());
            target.getScheduler().run(plugin, task -> MessageHelper.sendMessage(target, receiverMsg), null);
        }
    }





    private void publishTransferEvents(UUID senderUuid, String senderName, UUID targetUuid,
            double senderNewBalance, double targetNewBalance, BigDecimal amount) {
        if (pubsubSubscriber != null && config.isPubsubEnabled()) {
            pubsubSubscriber.publishTransferEvent(senderUuid, BigDecimal.valueOf(senderNewBalance),
                    "TRANSFER_OUT", amount.doubleValue(), senderName);
            pubsubSubscriber.publishTransferEvent(targetUuid, BigDecimal.valueOf(targetNewBalance),
                    "TRANSFER_IN", amount.doubleValue(), senderName);
        }
    }

    private DbWriteQueue.DbWriteTask createDbTask(UUID uuid, String name, double balance,
            long version, String serverName) {
        return new DbWriteQueue.DbWriteTask(
                uuid, name, balance, version,
                serverName != null ? serverName : "unknown",
                java.time.Instant.now());
    }

    private EconomyEvent createTransferEvent(UUID uuid, BigDecimal delta, double newBalance,
            long version, String type) {
        return new EconomyEvent(
                uuid, delta, BigDecimal.valueOf(newBalance), version,
                EconomyEvent.EventType.valueOf(type),
                EconomyEvent.EventSource.PLAYER_TRANSFER,
                UUID.randomUUID().toString(),
                System.currentTimeMillis());
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
        idempotencyKeys.compute(senderUuid, (uuid, existingKey) -> {
            if (existingKey == null) {
                return null;
            }
            String[] parts = existingKey.split(":");
            if (parts.length > 3) {
                try {
                    long timestamp = Long.parseLong(parts[parts.length - 1]);
                    if (System.currentTimeMillis() - timestamp > IDEMPOTENCY_KEY_TTL_MILLIS
                            || !existingKey.startsWith(currentKey)) {
                        return null;
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return existingKey;
        });
    }
}
