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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
    private final ConcurrentMap<UUID, EconomyState> rejectedAuthorityBases = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingRollback> pendingRollbacks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransferContext> transferContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AccountMutationLock> accountMutationLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> confirmedSnapshotConflictVersions = new ConcurrentHashMap<>();
    private final Set<UUID> unavailableAccounts = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, Set<String>> unresolvedTransferIds = new ConcurrentHashMap<>();

    private static final class AccountMutationLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger references = new AtomicInteger();
    }

    final class AccountMutationLease implements AutoCloseable {
        private final Map<UUID, AccountMutationLock> locks;
        private boolean closed;

        private AccountMutationLease(Map<UUID, AccountMutationLock> locks) {
            this.locks = locks;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            var entries = new java.util.ArrayList<>(locks.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                entries.get(i).getValue().lock.unlock();
            }
            for (var entry : entries) {
                accountMutationLocks.computeIfPresent(entry.getKey(), (uuid, current) -> {
                    if (current != entry.getValue()) return current;
                    return current.references.decrementAndGet() == 0 ? null : current;
                });
            }
        }
    }

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
                queue.setWriteCompletionHandler(new EconomyWriteQueue.WriteCompletionHandler() {
                    @Override
                    public void onComplete(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
                        onAcceptedWriteCompleted(event, authorityBalance, authorityVersion);
                    }

                    @Override
                    public void onRejected(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
                        onAcceptedWriteRejected(event, authorityBalance, authorityVersion);
                    }

                    @Override
                    public void onCompletePair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
                            EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
                        onAcceptedTransferPairCompleted(first, firstBalance, firstVersion,
                                second, secondBalance, secondVersion);
                    }

                    @Override
                    public void onRejectedPair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
                            EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
                        onRejectedTransferPairCompleted(first, firstBalance, firstVersion,
                                second, secondBalance, secondVersion);
                    }

                    @Override
                    public void withAccountsLocked(UUID firstAccount, UUID secondAccount, Runnable completion) {
                        MemoryStateManager.this.withAccountsLocked(firstAccount, secondAccount, completion);
                    }
                });
                queue.setUnavailableAccountHandler(MemoryStateManager.this::markUnavailable);
                queue.setTransferAvailabilityHandler(new EconomyWriteQueue.TransferAvailabilityHandler() {
                    @Override
                    public void markUnavailable(String operationId, UUID firstAccount, UUID secondAccount) {
                        markTransferUnavailable(operationId, firstAccount, secondAccount);
                    }

                    @Override
                    public void resolve(String operationId, UUID firstAccount, UUID secondAccount) {
                        resolveTransferUnavailable(operationId, firstAccount, secondAccount);
                    }
                });
            }
        } else if (writeQueue != queue) {
            throw new IllegalArgumentException("MemoryStateManager already tracks a different EconomyWriteQueue");
        }
    }


    EconomyState get(UUID uuid) {
        // EconomyState is immutable and the map is concurrent. Reads must never
        // wait behind a transfer or persistence operation holding an account lease.
        return memoryState.get(uuid);
    }

    EconomyState compute(UUID uuid, BiFunction<UUID, EconomyState, EconomyState> fn) {
        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            return memoryState.compute(uuid, fn);
        }
    }

    EconomyState computeIfAbsent(UUID uuid, Function<UUID, EconomyState> fn) {
        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            return memoryState.computeIfAbsent(uuid, fn);
        }
    }

    void put(UUID uuid, EconomyState state) {
        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            memoryState.put(uuid, state);
        }
    }

    AccountMutationLease lockAccounts(UUID... uuids) {
        java.util.TreeSet<UUID> ordered = new java.util.TreeSet<>();
        for (UUID uuid : uuids) {
            if (uuid == null) throw new IllegalArgumentException("Account UUID must not be null");
            ordered.add(uuid);
        }
        Map<UUID, AccountMutationLock> acquired = new java.util.LinkedHashMap<>();
        for (UUID uuid : ordered) {
            AccountMutationLock accountLock = accountMutationLocks.compute(uuid, (key, existing) -> {
                AccountMutationLock selected = existing == null ? new AccountMutationLock() : existing;
                selected.references.incrementAndGet();
                return selected;
            });
            acquired.put(uuid, accountLock);
        }
        for (AccountMutationLock accountLock : acquired.values()) {
            accountLock.lock.lock();
        }
        return new AccountMutationLease(acquired);
    }

    void withAccountsLocked(UUID firstAccount, UUID secondAccount, Runnable action) {
        UUID[] accounts = secondAccount == null
                ? new UUID[] { firstAccount }
                : new UUID[] { firstAccount, secondAccount };
        try (AccountMutationLease ignored = lockAccounts(accounts)) {
            action.run();
        }
    }

    boolean beginDirectTransferMutation(String operationId, UUID fromUuid, UUID toUuid) {
        if (operationId == null || operationId.isBlank() || fromUuid == null || toUuid == null
                || fromUuid.equals(toUuid)) {
            return false;
        }
        AtomicBoolean admitted = new AtomicBoolean();
        withAccountsLocked(fromUuid, toUuid, () -> {
            if (isUnavailable(fromUuid) || isUnavailable(toUuid)
                    || hasPendingWrite(fromUuid) || hasPendingWrite(toUuid)) {
                return;
            }
            markTransferUnavailable(operationId, fromUuid, toUuid);
            admitted.set(true);
        });
        return admitted.get();
    }

    void completeDirectTransferMutation(String operationId, UUID fromUuid, BigDecimal fromBalance,
            long fromVersion, UUID toUuid, BigDecimal toBalance, long toVersion) {
        withAccountsLocked(fromUuid, toUuid, () -> {
            reconcileDirectTransferAccount(fromUuid, fromBalance, fromVersion, System.currentTimeMillis());
            reconcileDirectTransferAccount(toUuid, toBalance, toVersion, System.currentTimeMillis());
            resolveTransferUnavailable(operationId, fromUuid, toUuid);
        });
    }

    void cancelDirectTransferMutation(String operationId, UUID fromUuid, UUID toUuid) {
        withAccountsLocked(fromUuid, toUuid, () -> resolveTransferUnavailable(operationId, fromUuid, toUuid));
    }

    private void reconcileDirectTransferAccount(UUID uuid, BigDecimal balance, long version, long now) {
        if (balance == null || version < 0L || hasCommittedWrite(uuid)) {
            unavailableAccounts.add(uuid);
            return;
        }
        EconomyState receipt = new EconomyState(balance, version, now);
        EconomyState latest = newestConfirmedSnapshot(uuid, memoryState.get(uuid), deferredRemoteUpdates.get(uuid));
        latest = newestConfirmedSnapshot(uuid, latest, receipt);
        if (latest == null) {
            markUnavailableUnlessConfirmedSnapshotConflict(uuid);
            return;
        }
        clearConfirmedSnapshotConflict(uuid, latest);
        memoryState.put(uuid, latest);
        deferredRemoteUpdates.remove(uuid);
        rejectedAuthorityBases.remove(uuid);
        unavailableAccounts.remove(uuid);
    }

    EconomyWriteQueue.Reservation tryReserveWrite(UUID uuid, EconomyState expectedState) {
        if (writeQueue == null) {
            return null;
        }

        AtomicReference<EconomyWriteQueue.Reservation> reservationRef = new AtomicReference<>();
        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            memoryState.compute(uuid, (key, existing) -> {
                if (isUnavailable(key)) {
                    return existing;
                }
                // A cleanup that wins after ensureLoaded must not turn this transaction into a
                // write based on an absent/zero balance. The caller can retry and reload.
                if (expectedState != null && existing == null) {
                    return null;
                }
                reservationRef.set(writeQueue.tryReserve(uuid));
                return existing;
            });
        }
        return reservationRef.get();
    }

    boolean containsKey(UUID uuid) {
        return memoryState.containsKey(uuid);
    }

    int size() {
        return memoryState.size();
    }

    boolean isUnavailable(UUID uuid) {
        Set<String> unresolved = unresolvedTransferIds.get(uuid);
        return unavailableAccounts.contains(uuid) || confirmedSnapshotConflictVersions.containsKey(uuid)
                || unresolved != null && !unresolved.isEmpty();
    }

    void markUnavailable(UUID uuid) {
        if (uuid != null) unavailableAccounts.add(uuid);
    }

    void markAvailable(UUID uuid) {
        if (uuid != null) unavailableAccounts.remove(uuid);
    }

    void markTransferUnavailable(String operationId, UUID firstAccount, UUID secondAccount) {
        if (operationId == null || operationId.isBlank()) {
            markUnavailable(firstAccount);
            markUnavailable(secondAccount);
            return;
        }
        addUnresolvedTransfer(firstAccount, operationId);
        addUnresolvedTransfer(secondAccount, operationId);
    }

    private void addUnresolvedTransfer(UUID uuid, String operationId) {
        if (uuid != null) {
            unresolvedTransferIds.computeIfAbsent(uuid, ignored -> ConcurrentHashMap.newKeySet()).add(operationId);
        }
    }

    private void resolveTransferUnavailable(String operationId, UUID firstAccount, UUID secondAccount) {
        if (operationId == null) return;
        removeUnresolvedTransfer(firstAccount, operationId);
        removeUnresolvedTransfer(secondAccount, operationId);
    }

    private void removeUnresolvedTransfer(UUID uuid, String operationId) {
        if (uuid == null) return;
        unresolvedTransferIds.computeIfPresent(uuid, (key, operationIds) -> {
            operationIds.remove(operationId);
            return operationIds.isEmpty() ? null : operationIds;
        });
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
        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            memoryState.compute(uuid, (key, existing) -> {
                EconomyState incoming = new EconomyState(balance, version, now);
                EconomyState deferred = deferredRemoteUpdates.get(key);
                if (hasCommittedWrite(key)) {
                    // The current memory version may be an optimistic projection. Keep
                    // confirmed snapshots even when they equal that projection so a later
                    // known-rejected write can rebase onto this authority observation.
                    EconomyState newest = newestConfirmedSnapshot(key, deferred, incoming);
                    if (newest != null) {
                        clearConfirmedSnapshotConflict(key, newest);
                        deferredRemoteUpdates.put(key, newest);
                    }
                    result.set(RemoteUpdateResult.DEFERRED);
                    return existing;
                }

                EconomyState newest = newestConfirmedSnapshot(key, deferred, incoming);
                if (newest == null) {
                    result.set(RemoteUpdateResult.REJECTED);
                    return existing;
                }
                if (existing != null && newest.version() <= existing.version()) return existing;

                clearConfirmedSnapshotConflict(key, newest);
                deferredRemoteUpdates.remove(key);
                result.set(RemoteUpdateResult.APPLIED);
                return newest;
            });
        }
        return result.get();
    }

    private void onAcceptedWriteCompleted(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
        UUID uuid = event.playerUuid();
        EconomyState authority = authorityBalance != null && authorityVersion >= 0
                ? new EconomyState(authorityBalance, authorityVersion, System.currentTimeMillis())
                : null;

        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            memoryState.compute(uuid, (key, existing) -> {
                EconomyState deferred = deferredRemoteUpdates.get(key);
                EconomyState candidate = deferred;
                // Keep a deferred remote snapshot on equal versions: a balance and a later
                // observed cache version must not be paired into a synthetic authority receipt.
                if (authority != null) {
                    candidate = newestConfirmedSnapshot(key, candidate, authority);
                    if (candidate == null) {
                        markUnavailableUnlessConfirmedSnapshotConflict(key);
                        return existing;
                    }
                }

                EconomyState rejectedBase = rejectedAuthorityBases.get(key);
                if (rejectedBase != null && hasCommittedWrite(key)) {
                    if (candidate != null) {
                        EconomyState latest = newestConfirmedSnapshot(key, deferred, candidate);
                        if (latest != null) deferredRemoteUpdates.put(key, latest);
                    }
                    return existing;
                }

                if (rejectedBase != null) {
                    if (authority == null || authority.version() <= rejectedBase.version() || candidate == null) {
                        unavailableAccounts.add(key);
                        return existing;
                    }
                    EconomyState latest = newestConfirmedSnapshot(key, rejectedBase, candidate);
                    if (latest == null) {
                        markUnavailableUnlessConfirmedSnapshotConflict(key);
                        return existing;
                    }
                    clearConfirmedSnapshotConflict(key, latest);
                    deferredRemoteUpdates.remove(key);
                    rejectedAuthorityBases.remove(key, rejectedBase);
                    unavailableAccounts.remove(key);
                    return latest;
                }

                if (hasCommittedWrite(key)) {
                    if (candidate != null) deferredRemoteUpdates.put(key, candidate);
                    return existing;
                }

                deferredRemoteUpdates.remove(key);
                // A confirmed receipt may legitimately have the same provisional
                // version but a different balance after a remote authority rejection.
                // In that tie, prefer the exact confirmed balance/version pair.
                if (candidate != null && (existing == null || candidate.version() >= existing.version())) {
                    clearConfirmedSnapshotConflict(key, candidate);
                    return candidate;
                }
                return existing;
            });
            if (!hasCommittedWrite(uuid) && !rejectedAuthorityBases.containsKey(uuid)) {
                unavailableAccounts.remove(uuid);
            }
        }
    }

    private void onAcceptedWriteRejected(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
        if (event == null) return;
        UUID uuid = event.playerUuid();
        try (AccountMutationLease ignored = lockAccounts(uuid)) {
            reconcileRejectedAuthorityAccount(uuid, authorityBalance, authorityVersion,
                    System.currentTimeMillis(), event);
        }
    }

    private void onAcceptedTransferPairCompleted(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
        reconcileTransferPair(first, firstBalance, firstVersion, second, secondBalance, secondVersion, false);
    }

    private void onRejectedTransferPairCompleted(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion) {
        reconcileTransferPair(first, firstBalance, firstVersion, second, secondBalance, secondVersion, true);
    }

    private void reconcileTransferPair(EconomyEvent first, BigDecimal firstBalance, long firstVersion,
            EconomyEvent second, BigDecimal secondBalance, long secondVersion, boolean rejected) {
        if (!TransferRequestIds.arePair(first, second)) {
            markUnavailable(first == null ? null : first.playerUuid());
            markUnavailable(second == null ? null : second.playerUuid());
            return;
        }

        long now = System.currentTimeMillis();
        try (AccountMutationLease ignored = lockAccounts(first.playerUuid(), second.playerUuid())) {
            if (rejected) {
                reconcileRejectedAuthorityAccount(first.playerUuid(), firstBalance, firstVersion, now, first);
                reconcileRejectedAuthorityAccount(second.playerUuid(), secondBalance, secondVersion, now, second);
            } else {
                reconcileTransferAccount(first.playerUuid(), firstBalance, firstVersion, now);
                reconcileTransferAccount(second.playerUuid(), secondBalance, secondVersion, now);
            }
        }
    }

    private void reconcileTransferAccount(UUID uuid, BigDecimal balance, long version, long now) {
        if (balance == null || version < 0L) {
            unavailableAccounts.add(uuid);
            return;
        }
        EconomyState existing = memoryState.get(uuid);
        EconomyState deferred = deferredRemoteUpdates.get(uuid);
        EconomyState latestConfirmed = newestConfirmedSnapshot(uuid, deferred, new EconomyState(balance, version, now));
        if (latestConfirmed == null) {
            markUnavailableUnlessConfirmedSnapshotConflict(uuid);
            return;
        }
        balance = latestConfirmed.balance();
        version = latestConfirmed.version();
        EconomyState rejectedBase = rejectedAuthorityBases.get(uuid);
        if (rejectedBase != null) {
            if (hasCommittedWrite(uuid)) {
                EconomyState latest = newestConfirmedSnapshot(uuid, deferred,
                        new EconomyState(balance, version, now));
                if (latest != null) deferredRemoteUpdates.put(uuid, latest);
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
            if (version <= rejectedBase.version()) {
                unavailableAccounts.add(uuid);
                return;
            }
            EconomyState latest = newestConfirmedSnapshot(uuid, rejectedBase,
                    new EconomyState(balance, version, now));
            if (latest == null) {
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
            clearConfirmedSnapshotConflict(uuid, latest);
            memoryState.put(uuid, latest);
            deferredRemoteUpdates.remove(uuid);
            rejectedAuthorityBases.remove(uuid, rejectedBase);
            unavailableAccounts.remove(uuid);
            return;
        }
        // This exact pair came from Redis or one SQLite transaction. A rejected
        // provisional transfer can have the same locally predicted version with a
        // different balance, so ordinary strictly-newer remote-update rules do not fit.
        if (existing == null || version >= existing.version()) {
            EconomyState pairSnapshot = new EconomyState(balance, version, now);
            clearConfirmedSnapshotConflict(uuid, pairSnapshot);
            memoryState.put(uuid, pairSnapshot);
            deferredRemoteUpdates.remove(uuid);
            if (hasCommittedWrite(uuid)) {
                unavailableAccounts.add(uuid);
            } else {
                unavailableAccounts.remove(uuid);
            }
        } else {
            // A lower confirmed version than the current projection indicates that
            // accepted work is still unresolved or state ordering has been violated.
            unavailableAccounts.add(uuid);
        }
    }

    private void reconcileRejectedAuthorityAccount(UUID uuid, BigDecimal balance, long version, long now,
            EconomyEvent rejectedEvent) {
        if (balance == null || version < 0L) {
            unavailableAccounts.add(uuid);
            return;
        }

        EconomyState authority = new EconomyState(balance, version, now);
        EconomyState candidate = newestConfirmedSnapshot(uuid, deferredRemoteUpdates.get(uuid), authority);
        if (candidate == null) {
            markUnavailableUnlessConfirmedSnapshotConflict(uuid);
            return;
        }

        EconomyState priorBase = rejectedAuthorityBases.get(uuid);
        if (priorBase != null) {
            candidate = newestConfirmedSnapshot(uuid, priorBase, candidate);
            if (candidate == null) {
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
        }

        if (hasCommittedWrite(uuid)) {
            rejectedAuthorityBases.put(uuid, candidate);
            deferredRemoteUpdates.put(uuid, candidate);
            unavailableAccounts.add(uuid);
            return;
        }

        EconomyState existing = memoryState.get(uuid);
        if (existing != null && !matchesRejectedProjection(existing, rejectedEvent)) {
            candidate = newestConfirmedSnapshot(uuid, existing, candidate);
            if (candidate == null) {
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
        }

        memoryState.put(uuid, candidate);
        clearConfirmedSnapshotConflict(uuid, candidate);
        deferredRemoteUpdates.remove(uuid);
        rejectedAuthorityBases.remove(uuid);
        unavailableAccounts.remove(uuid);
    }

    private boolean matchesRejectedProjection(EconomyState state, EconomyEvent rejectedEvent) {
        return rejectedEvent != null && state.version() == rejectedEvent.version()
                && state.balance().compareTo(rejectedEvent.balanceAfter()) == 0;
    }

    private EconomyState newestConfirmedSnapshot(UUID uuid, EconomyState first, EconomyState second) {
        if (first == null) return second;
        if (second == null) return first;
        if (first.version() == second.version()
                && first.balance().compareTo(second.balance()) != 0) {
            plugin.getLogger().severe("Conflicting confirmed balance snapshots for " + uuid
                    + " at version " + first.version() + "; account remains unavailable");
            confirmedSnapshotConflictVersions.merge(uuid, first.version(), Math::max);
            return null;
        }
        return second.version() > first.version() ? second : first;
    }

    private void clearConfirmedSnapshotConflict(UUID uuid, EconomyState confirmedSnapshot) {
        if (confirmedSnapshot == null) return;
        Long conflictVersion = confirmedSnapshotConflictVersions.get(uuid);
        if (conflictVersion != null && confirmedSnapshot.version() > conflictVersion) {
            confirmedSnapshotConflictVersions.remove(uuid, conflictVersion);
        }
    }

    private void markUnavailableUnlessConfirmedSnapshotConflict(UUID uuid) {
        if (!confirmedSnapshotConflictVersions.containsKey(uuid)) {
            unavailableAccounts.add(uuid);
        }
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

    boolean hasPendingWrite(UUID uuid) {
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
