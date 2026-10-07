package noietime.syncmoney.vault;

import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.core.port.ConfirmedAccount;
import noietime.syncmoney.core.port.ReplicationReceipt;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DbWriteQueue;
import noietime.syncmoney.uuid.NameResolver;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Applies the exact account pairs returned by direct Redis atomic-transfer paths. */
final class TransferReceiptSupport {

    private TransferReceiptSupport() { }

    static void accept(Plugin plugin, EconomyFacade economyFacade, DbWriteQueue dbWriteQueue,
            SyncmoneyConfig config, NameResolver nameResolver, DbWriteQueue.Reservation sqlReservation,
            UUID fromUuid, UUID toUuid, CacheManager.TransferResult result) {
        ReplicationReceipt receipt = new ReplicationReceipt(UUID.randomUUID().toString(), List.of(
                new ConfirmedAccount(fromUuid, result.fromNewBalance, result.fromNewVersion),
                new ConfirmedAccount(toUuid, result.toNewBalance, result.toNewVersion)));

        for (ConfirmedAccount account : receipt.accounts()) {
            economyFacade.updateMemoryState(account.playerUuid(), account.balance(), account.version());
        }

        if (config == null || !config.isDbEnabled()) {
            return;
        }
        if (dbWriteQueue == null) {
            plugin.getLogger().severe("Confirmed Vault transfer has no SQL write queue; receipt "
                    + receipt.operationId() + " was not persisted");
            return;
        }
        if (sqlReservation == null) {
            plugin.getLogger().severe("Confirmed Vault transfer has no SQL queue reservation; receipt "
                    + receipt.operationId() + " was not persisted");
            return;
        }

        String serverName = config.getServerName() != null ? config.getServerName() : "unknown";
        Instant timestamp = Instant.now();
        String fromName = nameResolver == null ? null : nameResolver.getNameCachedOnly(fromUuid);
        String toName = nameResolver == null ? null : nameResolver.getNameCachedOnly(toUuid);
        List<DbWriteQueue.AccountWrite> writes = List.of(
                new DbWriteQueue.AccountWrite(receipt.accounts().get(0), fromName, serverName, timestamp),
                new DbWriteQueue.AccountWrite(receipt.accounts().get(1), toName, serverName, timestamp));
        if (!sqlReservation.commit(new DbWriteQueue.DbWriteTask(receipt.operationId(), writes))) {
            plugin.getLogger().severe("DbWriteQueue is full; confirmed Vault transfer receipt "
                    + receipt.operationId() + " was not persisted");
        }
    }
}
