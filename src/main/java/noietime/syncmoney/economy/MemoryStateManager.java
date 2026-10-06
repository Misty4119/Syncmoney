package noietime.syncmoney.economy;

import noietime.syncmoney.economy.EconomyFacade.EconomyState;
import noietime.syncmoney.economy.EconomyFacade.PendingRollback;
import noietime.syncmoney.economy.EconomyFacade.TransferContext;
import noietime.syncmoney.migration.MigrationLock;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [SYNC-ECO-070] Owns the in-memory economy state and the transfer bookkeeping maps.
 */
final class MemoryStateManager {

    private static final int DEFAULT_EXPIRATION_MINUTES = 30;
    private static final int MAX_MEMORY_ENTRIES = 10000;

    private final Plugin plugin;
    private volatile EconomyWriteQueue writeQueue;

    private final ConcurrentMap<UUID, EconomyState> memoryState = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, EconomyState> deferredRemoteUpdates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingRollback> pendingRollbacks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransferContext> transferContexts = new ConcurrentHashMap<>();

    MemoryStateManager(Plugin plugin) {
        this(plugin, null);
    }

    MemoryStateManager(Plugin plugin, EconomyWriteQueue writeQueue) {
        this.plugin = plugin;
        attachWriteQueue(writeQueue);
    }

    void attachWriteQueue(EconomyWriteQueue queue) {
        if (writeQueue == null) {
            writeQueue = queue;
            if (queue != null) {
                queue.setWriteCompletionHandler(this::onAcceptedWriteCompleted);
            }
        } else if (writeQueue != queue) {
            throw new IllegalArgumentException("MemoryStateManager already tracks a different EconomyWriteQueue");
        }
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

    EconomyWriteQueue.Reservation tryReserveWrite(UUID uuid, EconomyState expectedState) {
        if (writeQueue == null) {
            return null;
        }

        AtomicReference<EconomyWriteQueue.Reservation> reservationRef = new AtomicReference<>();
        memoryState.compute(uuid, (key, existing) -> {
            // A cleanup that wins after ensureLoaded must not turn this transaction into a
            // write based on an absent/zero balance. The caller can retry and reload.
            if (expectedState != null && existing == null) {
                return null;
            }
            reservationRef.set(writeQueue.tryReserve(uuid));
            return existing;
        });
        return reservationRef.get();
    }

    boolean containsKey(UUID uuid) {
        return memoryState.containsKey(uuid);
    }

    int size() {
        return memoryState.size();
    }

    boolean hasOutstandingWrites() {
        return writeQueue != null && writeQueue.hasOutstandingWrites();
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
        return applyRemoteUpdateResult(uuid, balance, version) == RemoteUpdateResult.APPLIED;
    }

    enum RemoteUpdateResult {
        APPLIED,
        DEFERRED,
        REJECTED
    }

    RemoteUpdateResult applyRemoteUpdateResult(UUID uuid, BigDecimal balance, long version) {
        long now = System.currentTimeMillis();
        AtomicReference<RemoteUpdateResult> result = new AtomicReference<>(RemoteUpdateResult.REJECTED);
        memoryState.compute(uuid, (key, existing) -> {
            EconomyState incoming = new EconomyState(balance, version, now);
            EconomyState deferred = deferredRemoteUpdates.get(key);
            EconomyState newest = deferred == null || version > deferred.version() ? incoming : deferred;

            if (existing != null && newest.version() <= existing.version()) {
                return existing;
            }

            if (hasCommittedWrite(key)) {
                deferredRemoteUpdates.put(key, newest);
                result.set(RemoteUpdateResult.DEFERRED);
                return existing;
            }

            deferredRemoteUpdates.remove(key);
            result.set(RemoteUpdateResult.APPLIED);
            return newest;
        });
        return result.get();
    }

    private void onAcceptedWriteCompleted(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
        UUID uuid = event.playerUuid();
        EconomyState authority = authorityBalance != null && authorityVersion > 0
                ? new EconomyState(authorityBalance, authorityVersion, System.currentTimeMillis())
                : null;

        memoryState.compute(uuid, (key, existing) -> {
            EconomyState deferred = deferredRemoteUpdates.get(key);
            EconomyState candidate = deferred;
            // Keep a deferred remote snapshot on equal versions: a balance and a later
            // observed cache version must not be paired into a synthetic authority receipt.
            if (authority != null && (candidate == null || authority.version() > candidate.version())) {
                candidate = authority;
            }

            if (hasCommittedWrite(key)) {
                if (candidate != null && (existing == null || candidate.version() > existing.version())) {
                    deferredRemoteUpdates.put(key, candidate);
                }
                return existing;
            }

            deferredRemoteUpdates.remove(key);
            if (candidate != null && (existing == null || candidate.version() > existing.version())) {
                return candidate;
            }
            return existing;
        });
    }

    /** Force update is restricted to the explicit migration maintenance lock. */
    void forceUpdate(UUID uuid, BigDecimal balance, long version) {
        if (!MigrationLock.isLocked()) {
            plugin.getLogger().warning("Rejected force memory update outside the migration maintenance lock for " + uuid);
            return;
        }
        long now = System.currentTimeMillis();
        AtomicBoolean updated = new AtomicBoolean();
        memoryState.compute(uuid, (key, existing) -> {
            if (hasPendingWrite(uuid)) {
                return existing;
            }
            updated.set(true);
            return new EconomyState(balance, version, now);
        });
        if (!updated.get()) {
            plugin.getLogger().warning("Rejected force memory update while accepted writes remain pending for " + uuid);
        }
    }

    /** [SYNC-ECO-055] Remove a single entry from memory. */
    void clearEntry(UUID uuid) {
        memoryState.computeIfPresent(uuid, (key, existing) -> hasPendingWrite(uuid) ? existing : null);
    }

    private boolean hasPendingWrite(UUID uuid) {
        return writeQueue != null && writeQueue.hasAcceptedWrite(uuid);
    }

    private boolean hasCommittedWrite(UUID uuid) {
        return writeQueue != null && writeQueue.hasCommittedWrite(uuid);
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
                AtomicBoolean removed = new AtomicBoolean();
                memoryState.computeIfPresent(entry.getKey(), (key, current) -> {
                    if (hasPendingWrite(key) || current.lastAccessTime() >= expirationTime) {
                        return current;
                    }
                    removed.set(true);
                    return null;
                });
                if (removed.get()) {
                    removedCount++;
                }
            }
        }

        if (memoryState.size() > MAX_MEMORY_ENTRIES) {
            int entriesToRemove = memoryState.size() - MAX_MEMORY_ENTRIES;
            java.util.List<? extends Map.Entry<UUID, EconomyState>> oldestEntries = memoryState.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(
                            e1.getValue() != null ? e1.getValue().lastAccessTime() : 0,
                            e2.getValue() != null ? e2.getValue().lastAccessTime() : 0))
                    .map(entry -> new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()))
                    .toList();

            int capacityRemoved = 0;
            for (Map.Entry<UUID, EconomyState> candidate : oldestEntries) {
                if (capacityRemoved >= entriesToRemove) {
                    break;
                }
                UUID uuid = candidate.getKey();
                EconomyState observed = candidate.getValue();
                AtomicBoolean removed = new AtomicBoolean();
                memoryState.computeIfPresent(uuid, (key, current) -> {
                    if (current != observed || hasPendingWrite(key) || memoryState.size() <= MAX_MEMORY_ENTRIES) {
                        return current;
                    }
                    removed.set(true);
                    return null;
                });
                if (removed.get()) {
                    removedCount++;
                    capacityRemoved++;
                }
            }

            if (capacityRemoved > 0) {
                plugin.getLogger().warning("Memory cleanup: removed " + capacityRemoved
                        + " oldest entries due to exceeding max limit of " + MAX_MEMORY_ENTRIES);
            }
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
