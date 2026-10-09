package noietime.syncmoney.economy;

import noietime.syncmoney.core.state.AccountState;
import noietime.syncmoney.core.state.AccountStateStore;
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

    private final AccountStateStore accountStates = new AccountStateStore();
    private final ConcurrentMap<UUID, AccountState> unverifiedBalanceStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AccountState> deferredRemoteUpdates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, AccountState> rejectedAuthorityBases = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingRollback> pendingRollbacks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TransferContext> transferContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> confirmedSnapshotConflictVersions = new ConcurrentHashMap<>();
    private final Set<UUID> unavailableAccounts = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, Set<String>> unresolvedTransferIds = new ConcurrentHashMap<>();

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


    AccountState get(UUID uuid) {
        // AccountState is immutable and the map is concurrent. Reads must never
        // wait behind a transfer or persistence operation holding an account lease.
        return accountStates.get(uuid);
    }

    AccountState compute(UUID uuid, BiFunction<UUID, AccountState, AccountState> fn) {
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            return accountStates.compute(uuid, (key, current) -> {
                AccountState next = fn.apply(key, current);
                if (next != current) {
                    unverifiedBalanceStates.remove(key);
                }
                return next;
            });
        }
    }

    AccountState computeIfAbsent(UUID uuid, Function<UUID, AccountState> fn) {
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            return accountStates.computeIfAbsent(uuid, fn);
        }
    }

    /** Creates a legacy fallback snapshot while retaining that its value is unverified. */
    AccountState computeIfAbsentUnverified(UUID uuid, Function<UUID, AccountState> fn) {
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            AccountState current = accountStates.get(uuid);
            if (current != null) {
                return current;
            }
            AccountState created = fn.apply(uuid);
            unverifiedBalanceStates.put(uuid, created);
            accountStates.put(uuid, created);
            return created;
        }
    }

    /**
     * Returns a resident balance for the API only when its provenance is confirmed.
     * This cache lookup performs no I/O and immediately returns {@code null} when an
     * account mutation currently owns the lock, allowing callers to treat contention
     * as a cache miss and load on their asynchronous worker.
     */
    AccountState getConfirmedStateForApi(UUID uuid) {
        AccountStateStore.MutationLease lease = tryLockAccount(uuid);
        if (lease == null) return null;
        try (lease) {
            AccountState current = accountStates.get(uuid);
            return current == null || unverifiedBalanceStates.get(uuid) == current ? null : current;
        }
    }

    /** Caches an authoritative read, replacing only a prior synthetic fallback snapshot. */
    AccountState cacheConfirmedBalance(UUID uuid, BigDecimal balance, long version, long timestamp) {
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            AccountState current = accountStates.get(uuid);
            if (current == null) {
                unverifiedBalanceStates.remove(uuid);
                AccountState confirmed = new AccountState(balance, version, timestamp);
                accountStates.put(uuid, confirmed);
                return confirmed;
            }
            if (unverifiedBalanceStates.get(uuid) == current) {
                AccountState confirmed = new AccountState(balance, version, timestamp);
                accountStates.put(uuid, confirmed);
                unverifiedBalanceStates.remove(uuid, current);
                return confirmed;
            }
            AccountState staleMarker = unverifiedBalanceStates.get(uuid);
            if (staleMarker != null && staleMarker != current) {
                unverifiedBalanceStates.remove(uuid, staleMarker);
            }
            return current;
        }
    }

    void put(UUID uuid, AccountState state) {
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            accountStates.put(uuid, state);
            unverifiedBalanceStates.remove(uuid);
        }
    }

    AccountStateStore.MutationLease lockAccounts(UUID... uuids) {
        return accountStates.lockAccounts(uuids);
    }

    private AccountStateStore.MutationLease tryLockAccount(UUID uuid) {
        return accountStates.tryLockAccount(uuid);
    }

    void withAccountsLocked(UUID firstAccount, UUID secondAccount, Runnable action) {
        UUID[] accounts = secondAccount == null
                ? new UUID[] { firstAccount }
                : new UUID[] { firstAccount, secondAccount };
        try (AccountStateStore.MutationLease ignored = lockAccounts(accounts)) {
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

    void completeRejectedDirectTransferMutation(String operationId, UUID fromUuid, AccountState fromSnapshot,
            UUID toUuid, AccountState toSnapshot) {
        withAccountsLocked(fromUuid, toUuid, () -> {
            reconcileRejectedTransferAccount(fromUuid, fromSnapshot);
            reconcileRejectedTransferAccount(toUuid, toSnapshot);
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
        AccountState receipt = new AccountState(balance, version, now);
        AccountState latest = newestConfirmedSnapshot(uuid, accountStates.get(uuid), deferredRemoteUpdates.get(uuid));
        latest = newestConfirmedSnapshot(uuid, latest, receipt);
        if (latest == null) {
            markUnavailableUnlessConfirmedSnapshotConflict(uuid);
            return;
        }
        clearConfirmedSnapshotConflict(uuid, latest);
        accountStates.put(uuid, latest);
        unverifiedBalanceStates.remove(uuid);
        deferredRemoteUpdates.remove(uuid);
        rejectedAuthorityBases.remove(uuid);
        unavailableAccounts.remove(uuid);
    }

    private void reconcileRejectedTransferAccount(UUID uuid, AccountState snapshot) {
        if (snapshot != null) {
            reconcileDirectTransferAccount(uuid, snapshot.balance(), snapshot.version(), snapshot.lastAccessTime());
            return;
        }

        if (hasPendingWrite(uuid) || hasCommittedWrite(uuid) || deferredRemoteUpdates.containsKey(uuid)
                || rejectedAuthorityBases.containsKey(uuid) || confirmedSnapshotConflictVersions.containsKey(uuid)) {
            unavailableAccounts.add(uuid);
            return;
        }

        AccountState current = accountStates.get(uuid);
        if (current != null && unverifiedBalanceStates.get(uuid) != current) {
            unavailableAccounts.add(uuid);
            return;
        }

        if (current == null) {
            unverifiedBalanceStates.remove(uuid);
        } else {
            accountStates.remove(uuid, current);
            unverifiedBalanceStates.remove(uuid, current);
        }
        unavailableAccounts.remove(uuid);
    }

    EconomyWriteQueue.Reservation tryReserveWrite(UUID uuid, AccountState expectedState) {
        if (writeQueue == null) {
            return null;
        }

        AtomicReference<EconomyWriteQueue.Reservation> reservationRef = new AtomicReference<>();
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            accountStates.compute(uuid, (key, existing) -> {
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
        return accountStates.containsKey(uuid);
    }

    int size() {
        return accountStates.size();
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
        return accountStates.keySet();
    }

    BigDecimal peekBalance(UUID uuid) {
        AccountState state = accountStates.get(uuid);
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
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            accountStates.compute(uuid, (key, existing) -> {
                AccountState incoming = new AccountState(balance, version, now);
                AccountState deferred = deferredRemoteUpdates.get(key);
                if (hasCommittedWrite(key)) {
                    // The current memory version may be an optimistic projection. Keep
                    // confirmed snapshots even when they equal that projection so a later
                    // known-rejected write can rebase onto this authority observation.
                    AccountState newest = newestConfirmedSnapshot(key, deferred, incoming);
                    if (newest != null) {
                        clearConfirmedSnapshotConflict(key, newest);
                        deferredRemoteUpdates.put(key, newest);
                    }
                    result.set(RemoteUpdateResult.DEFERRED);
                    return existing;
                }

                AccountState newest = newestConfirmedSnapshot(key, deferred, incoming);
                if (newest == null) {
                    result.set(RemoteUpdateResult.REJECTED);
                    return existing;
                }
                if (existing != null && newest.version() <= existing.version()) return existing;

                clearConfirmedSnapshotConflict(key, newest);
                deferredRemoteUpdates.remove(key);
                unverifiedBalanceStates.remove(key);
                result.set(RemoteUpdateResult.APPLIED);
                return newest;
            });
        }
        return result.get();
    }

    private void onAcceptedWriteCompleted(EconomyEvent event, BigDecimal authorityBalance, long authorityVersion) {
        UUID uuid = event.playerUuid();
        AccountState authority = authorityBalance != null && authorityVersion >= 0
                ? new AccountState(authorityBalance, authorityVersion, System.currentTimeMillis())
                : null;

        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            accountStates.compute(uuid, (key, existing) -> {
                AccountState deferred = deferredRemoteUpdates.get(key);
                AccountState candidate = deferred;
                // Keep a deferred remote snapshot on equal versions: a balance and a later
                // observed cache version must not be paired into a synthetic authority receipt.
                if (authority != null) {
                    candidate = newestConfirmedSnapshot(key, candidate, authority);
                    if (candidate == null) {
                        markUnavailableUnlessConfirmedSnapshotConflict(key);
                        return existing;
                    }
                }

                AccountState rejectedBase = rejectedAuthorityBases.get(key);
                if (rejectedBase != null && hasCommittedWrite(key)) {
                    if (candidate != null) {
                        AccountState latest = newestConfirmedSnapshot(key, deferred, candidate);
                        if (latest != null) deferredRemoteUpdates.put(key, latest);
                    }
                    return existing;
                }

                if (rejectedBase != null) {
                    if (authority == null || authority.version() <= rejectedBase.version() || candidate == null) {
                        unavailableAccounts.add(key);
                        return existing;
                    }
                    AccountState latest = newestConfirmedSnapshot(key, rejectedBase, candidate);
                    if (latest == null) {
                        markUnavailableUnlessConfirmedSnapshotConflict(key);
                        return existing;
                    }
                    clearConfirmedSnapshotConflict(key, latest);
                    deferredRemoteUpdates.remove(key);
                    rejectedAuthorityBases.remove(key, rejectedBase);
                    unverifiedBalanceStates.remove(key);
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
                    unverifiedBalanceStates.remove(key);
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
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
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
        try (AccountStateStore.MutationLease ignored = lockAccounts(first.playerUuid(), second.playerUuid())) {
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
        AccountState existing = accountStates.get(uuid);
        AccountState deferred = deferredRemoteUpdates.get(uuid);
        AccountState latestConfirmed = newestConfirmedSnapshot(uuid, deferred, new AccountState(balance, version, now));
        if (latestConfirmed == null) {
            markUnavailableUnlessConfirmedSnapshotConflict(uuid);
            return;
        }
        balance = latestConfirmed.balance();
        version = latestConfirmed.version();
        AccountState rejectedBase = rejectedAuthorityBases.get(uuid);
        if (rejectedBase != null) {
            if (hasCommittedWrite(uuid)) {
                AccountState latest = newestConfirmedSnapshot(uuid, deferred,
                        new AccountState(balance, version, now));
                if (latest != null) deferredRemoteUpdates.put(uuid, latest);
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
            if (version <= rejectedBase.version()) {
                unavailableAccounts.add(uuid);
                return;
            }
            AccountState latest = newestConfirmedSnapshot(uuid, rejectedBase,
                    new AccountState(balance, version, now));
            if (latest == null) {
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
            clearConfirmedSnapshotConflict(uuid, latest);
            accountStates.put(uuid, latest);
            unverifiedBalanceStates.remove(uuid);
            deferredRemoteUpdates.remove(uuid);
            rejectedAuthorityBases.remove(uuid, rejectedBase);
            unavailableAccounts.remove(uuid);
            return;
        }
        // This exact pair came from Redis or one SQLite transaction. A rejected
        // provisional transfer can have the same locally predicted version with a
        // different balance, so ordinary strictly-newer remote-update rules do not fit.
        if (existing == null || version >= existing.version()) {
            AccountState pairSnapshot = new AccountState(balance, version, now);
            clearConfirmedSnapshotConflict(uuid, pairSnapshot);
            accountStates.put(uuid, pairSnapshot);
            unverifiedBalanceStates.remove(uuid);
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
        if (uuid == null) return;
        if (balance == null || version < 0L) {
            if (balance != null || version != -1L) {
                unavailableAccounts.add(uuid);
                return;
            }
            reconcileRejectedAbsentAuthorityAccount(uuid, rejectedEvent);
            return;
        }

        AccountState authority = new AccountState(balance, version, now);
        AccountState candidate = newestConfirmedSnapshot(uuid, deferredRemoteUpdates.get(uuid), authority);
        if (candidate == null) {
            markUnavailableUnlessConfirmedSnapshotConflict(uuid);
            return;
        }

        AccountState priorBase = rejectedAuthorityBases.get(uuid);
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

        AccountState existing = accountStates.get(uuid);
        if (existing != null && !matchesRejectedProjection(existing, rejectedEvent)) {
            candidate = newestConfirmedSnapshot(uuid, existing, candidate);
            if (candidate == null) {
                markUnavailableUnlessConfirmedSnapshotConflict(uuid);
                return;
            }
        }

        accountStates.put(uuid, candidate);
        unverifiedBalanceStates.remove(uuid);
        clearConfirmedSnapshotConflict(uuid, candidate);
        deferredRemoteUpdates.remove(uuid);
        rejectedAuthorityBases.remove(uuid);
        unavailableAccounts.remove(uuid);
    }

    /**
     * A rejected write can confirm that Redis has no account key. Do not turn that absence
     * into a synthetic zero account. If later accepted writes are queued, discard their local
     * projection and keep the account unavailable until one of those writes returns authority.
     */
    private void reconcileRejectedAbsentAuthorityAccount(UUID uuid, EconomyEvent rejectedEvent) {
        if (hasPendingWrite(uuid) || hasCommittedWrite(uuid)) {
            accountStates.remove(uuid);
            unverifiedBalanceStates.remove(uuid);
            deferredRemoteUpdates.remove(uuid);
            rejectedAuthorityBases.remove(uuid);
            unavailableAccounts.add(uuid);
            return;
        }

        AccountState current = accountStates.get(uuid);
        if (current == null || unverifiedBalanceStates.get(uuid) == current
                || matchesRejectedProjection(current, rejectedEvent)) {
            if (current != null) accountStates.remove(uuid, current);
            unverifiedBalanceStates.remove(uuid);
            deferredRemoteUpdates.remove(uuid);
            rejectedAuthorityBases.remove(uuid);
            unavailableAccounts.remove(uuid);
            return;
        }

        // A fresh absent snapshot must not erase a different confirmed in-memory snapshot.
        unavailableAccounts.add(uuid);
    }

    private boolean matchesRejectedProjection(AccountState state, EconomyEvent rejectedEvent) {
        return rejectedEvent != null && state.version() == rejectedEvent.version()
                && state.balance().compareTo(rejectedEvent.balanceAfter()) == 0;
    }

    private AccountState newestConfirmedSnapshot(UUID uuid, AccountState first, AccountState second) {
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

    private void clearConfirmedSnapshotConflict(UUID uuid, AccountState confirmedSnapshot) {
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
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            if (hasPendingWrite(uuid)) {
                plugin.getLogger().warning("Rejected force memory update while accepted writes remain pending for " + uuid);
                return;
            }
            accountStates.put(uuid, new AccountState(balance, version, now));
            unverifiedBalanceStates.remove(uuid);
        }
    }

    /** [SYNC-ECO-055] Remove a single entry from memory. */
    void clearEntry(UUID uuid) {
        try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
            if (hasPendingWrite(uuid)) {
                return;
            }
            accountStates.remove(uuid);
            unverifiedBalanceStates.remove(uuid);
        }
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
        Iterator<Map.Entry<UUID, AccountState>> iterator = accountStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, AccountState> entry = iterator.next();
            AccountState state = entry.getValue();
            if (state != null && state.lastAccessTime() < expirationTime) {
                UUID uuid = entry.getKey();
                try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
                    AccountState current = accountStates.get(uuid);
                    if (current == null || current.lastAccessTime() >= expirationTime || hasPendingWrite(uuid)) {
                        continue;
                    }
                    if (accountStates.remove(uuid, current)) {
                        unverifiedBalanceStates.remove(uuid);
                        removedCount++;
                    }
                }
            }
        }

        if (accountStates.size() > MAX_MEMORY_ENTRIES) {
            int entriesToRemove = accountStates.size() - MAX_MEMORY_ENTRIES;
            java.util.List<? extends Map.Entry<UUID, AccountState>> oldestEntries = accountStates.entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(
                            e1.getValue() != null ? e1.getValue().lastAccessTime() : 0,
                            e2.getValue() != null ? e2.getValue().lastAccessTime() : 0))
                    .map(entry -> new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()))
                    .toList();

            int capacityRemoved = 0;
            for (Map.Entry<UUID, AccountState> candidate : oldestEntries) {
                if (capacityRemoved >= entriesToRemove) {
                    break;
                }
                UUID uuid = candidate.getKey();
                AccountState observed = candidate.getValue();
                try (AccountStateStore.MutationLease ignored = lockAccounts(uuid)) {
                    AccountState current = accountStates.get(uuid);
                    if (current != observed || hasPendingWrite(uuid) || accountStates.size() <= MAX_MEMORY_ENTRIES) {
                        continue;
                    }
                    if (accountStates.remove(uuid, current)) {
                        unverifiedBalanceStates.remove(uuid);
                        removedCount++;
                        capacityRemoved++;
                    }
                }
            }

            if (capacityRemoved > 0) {
                plugin.getLogger().warning("Memory cleanup: removed " + capacityRemoved
                        + " oldest entries due to exceeding max limit of " + MAX_MEMORY_ENTRIES);
            }
        }

        if (removedCount > 0) {
            plugin.getLogger().fine("Memory cleanup: removed " + removedCount + " expired entries, " + accountStates.size() + " entries remaining");
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
