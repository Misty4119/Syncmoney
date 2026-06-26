package noietime.syncmoney.economy;

import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.economy.EconomyFacade.PendingRollback;
import noietime.syncmoney.economy.EconomyFacade.TransferContext;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * [SYNC-ECO-070] Owns the in-memory economy state and the transfer bookkeeping maps.
 */
final class MemoryStateManager {

    private static final int DEFAULT_EXPIRATION_MINUTES = 30;
    private static final int MAX_MEMORY_ENTRIES = 10000;

    private final Plugin plugin;

    private final ConcurrentMap<UUID, EconomyState> memoryState = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingRollback> pendingRollbacks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransferContext> transferContexts = new ConcurrentHashMap<>();

    MemoryStateManager(Plugin plugin) {
        this.plugin = plugin;
    }


    EconomyState get(UUID uuid) {
        return memoryState.get(uuid);
    }

    EconomyState compute(UUID uuid, BiFunction<UUID, EconomyState, EconomyState> fn) {
        return memoryState.compute(uuid, fn);
    }

    EconomyState computeIfAbsent(UUID uuid, Function<UUID, EconomyState> fn) {
        return memoryState.computeIfAbsent(uuid, fn);
    }

    void put(UUID uuid, EconomyState state) {
        memoryState.put(uuid, state);
    }

    boolean containsKey(UUID uuid) {
        return memoryState.containsKey(uuid);
    }

    int size() {
        return memoryState.size();
    }

    Set<UUID> keySet() {
        return memoryState.keySet();
    }

    BigDecimal peekBalance(UUID uuid) {
        EconomyState state = memoryState.get(uuid);
        return state != null ? state.balance() : null;
    }

    /**
     * [SYNC-ECO-053] Apply a remote balance update, only when the new version is
     * strictly greater than the current one. Returns true when the update was applied.
     */
    boolean applyRemoteUpdate(UUID uuid, BigDecimal balance, long version) {
        long now = System.currentTimeMillis();
        EconomyState result = memoryState.compute(uuid, (key, existing) -> {
            if (existing == null || version > existing.version()) {
                return new EconomyState(balance, version, now);
            }
            return existing;
        });
        return result != null && result.version() == version;
    }

    /** [SYNC-ECO-057] Force update memory state from external source (unconditional). */
    void forceUpdate(UUID uuid, BigDecimal balance, long version) {
        long now = System.currentTimeMillis();
        memoryState.put(uuid, new EconomyState(balance, version, now));
    }

    /** [SYNC-ECO-055] Remove a single entry from memory. */
    void clearEntry(UUID uuid) {
        memoryState.remove(uuid);
    }


    void putPendingRollback(UUID toUuid, PendingRollback rollback) {
        pendingRollbacks.put(toUuid, rollback);
    }

    PendingRollback getPendingRollback(UUID uuid) {
        return pendingRollbacks.get(uuid);
    }

    void removePendingRollback(UUID uuid) {
        pendingRollbacks.remove(uuid);
    }

    void putTransferContext(String transferId, TransferContext context) {
        transferContexts.put(transferId, context);
    }

    void computeTransferContext(String transferId,
            BiFunction<String, TransferContext, TransferContext> fn) {
        transferContexts.compute(transferId, fn);
    }

    /**
     * [SYNC-ECO-060] Clean up expired memory state. Removes player data not accessed
     * for more than 30 minutes and enforces the maximum entry limit.
     */
    int cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        long expirationTime = now - (DEFAULT_EXPIRATION_MINUTES * 60 * 1000);

        int removedCount = 0;
        Iterator<Map.Entry<UUID, EconomyState>> iterator = memoryState.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EconomyState> entry = iterator.next();
            EconomyState state = entry.getValue();
            if (state != null && state.lastAccessTime() < expirationTime) {
                if (memoryState.remove(entry.getKey(), state)) {
                    removedCount++;
                }
            }
        }

        if (memoryState.size() > MAX_MEMORY_ENTRIES) {
            int entriesToRemove = memoryState.size() - MAX_MEMORY_ENTRIES;
            java.util.List<UUID> oldestEntries = memoryState.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(
                            e1.getValue() != null ? e1.getValue().lastAccessTime() : 0,
                            e2.getValue() != null ? e2.getValue().lastAccessTime() : 0))
                    .limit(entriesToRemove)
                    .map(Map.Entry::getKey)
                    .toList();

            for (UUID uuid : oldestEntries) {
                if (memoryState.remove(uuid) != null)
                    removedCount++;
            }

            plugin.getLogger().warning("Memory cleanup: removed " + entriesToRemove + " oldest entries due to exceeding max limit of " + MAX_MEMORY_ENTRIES);
        }

        if (removedCount > 0) {
            plugin.getLogger().fine("Memory cleanup: removed " + removedCount + " expired entries, " + memoryState.size() + " entries remaining");
        }
        return removedCount;
    }

    /** [SYNC-ECO-107] Clean up old transfer contexts and pending rollbacks. */
    void cleanupTransferData() {
        long now = System.currentTimeMillis();
        long expirationTime = now - (5 * 60 * 1000);

        transferContexts.entrySet().removeIf(entry -> {
            TransferContext ctx = entry.getValue();
            return ctx.timestamp() < expirationTime;
        });

        pendingRollbacks.entrySet().removeIf(entry -> {
            PendingRollback rb = entry.getValue();
            return rb.timestamp() < expirationTime;
        });
    }
}
