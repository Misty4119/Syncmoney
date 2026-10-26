package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.protocol.RedisKeys;
import noietime.syncmoney.migration.MigrationLock;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIApi;
import noietime.syncmoney.sync.CMIRedisMirror;
import noietime.syncmoney.sync.CMIVersioning;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CMI Economy Handler — the cross-server sync layer for CMI economy.
 *
 * <p>Design (see {@code reference/CMIEconomySync}): CMI is the local economy authority. This
 * handler does NOT query CMI's database at runtime; it reads balances from the in-memory CMI API
 * (via {@link CMIApi}) and uses Redis purely as the sync transport + mirror. Outbound changes are
 * published as absolute balances with the legacy timestamp-derived version; inbound updates are
 * applied back into CMI by {@code CMIPubsubHandler}. The version is not globally monotonic across
 * clock rollback, restart, or multiple servers.
 *
 * [ThreadSafe] Redis I/O is performed on async threads by callers; CMI player writes go through
 * the owning entity scheduler.
 */
public class CMIEconomyHandler {

    private static final int MAX_LAST_APPLIED_VERSION_ENTRIES = 10_000;

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final CrossServerSyncManager syncManager;
    private final CMIApi cmiApi;
    private final String redisPrefix;
    private final CMIRedisMirror redisMirror;

    private record BalanceObservation(BigDecimal syncBaseline, BigDecimal apiAuthorityBalance) {
    }

    private final ConcurrentHashMap<UUID, BalanceObservation> balanceObservations = new ConcurrentHashMap<>();
    private final LinkedHashMap<UUID, Long> lastAppliedVersion = new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicLong versionCounter = new AtomicLong(0L);
    private final Object joinReconciliationGate = new Object();
    private volatile boolean joinReconciliationStopped;
    private int inFlightJoinReconciliations;

    public CMIEconomyHandler(Syncmoney plugin, SyncmoneyConfig config,
                           RedisManager redisManager,
                           CrossServerSyncManager syncManager,
                           CMIApi cmiApi) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.syncManager = syncManager;
        this.cmiApi = cmiApi;
        this.redisPrefix = config.cmi().getCMIRedisPrefix();
        this.redisMirror = new CMIRedisMirror(redisManager, redisPrefix);
        plugin.getLogger().fine("CMI Economy Handler initialized (CMI API available: " + cmiApi.isAvailable() + ")");
    }

    public CMIApi getCmiApi() {
        return cmiApi;
    }

    private volatile noietime.syncmoney.listener.CMIEconomyListener cmiListener;

    public void setCmiListener(noietime.syncmoney.listener.CMIEconomyListener listener) {
        this.cmiListener = listener;
    }

    public void notifyInboundApplied(UUID uuid, BigDecimal absoluteBalance) {
        notifyInboundApplied(uuid, absoluteBalance, null);
    }

    /**
     * Refresh listener baseline after an inbound write. When {@code appliedVersion} is known,
     * it is recorded so join-reconcile can compare against the Redis mirror version.
     */
    public void notifyInboundApplied(UUID uuid, BigDecimal absoluteBalance, Long appliedVersion) {
        updateSyncBaseline(uuid, absoluteBalance);
        if (appliedVersion != null) {
            recordLastAppliedVersion(uuid, appliedVersion);
        }
        if (cmiListener != null) {
            cmiListener.notifyInboundApplied(uuid, absoluteBalance);
        }
    }

    /**
     * [FIX-CMI-ECHO] Suppress outbound re-publish while applying remote balance into local CMI.
     */
    public void suppressOutbound(UUID uuid, long durationMs) {
        if (cmiListener != null) {
            cmiListener.suppressOutbound(uuid, durationMs);
        }
    }

    private long generateNewVersion() {
        return CMIVersioning.generateVersion(versionCounter);
    }

    public long mintCmiVersion() {
        return generateNewVersion();
    }

    public void syncAbsoluteBalance(UUID uuid, BigDecimal absoluteBalance, BigDecimal diff,
                                    boolean isDeposit, String sourceName) {
        trySyncAbsoluteBalance(uuid, absoluteBalance, diff, isDeposit, sourceName);
    }

    /** Returns false when the migration fence or the mirror write rejected this update. */
    public boolean trySyncAbsoluteBalance(UUID uuid, BigDecimal absoluteBalance, BigDecimal diff,
                                          boolean isDeposit, String sourceName) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            return false;
        }
        try (writeLease) {
            BigDecimal newBalance = NumericUtil.normalize(absoluteBalance);
            BigDecimal last = getSyncBaseline(uuid);
            if (last != null && last.compareTo(newBalance) == 0) {
                return true;
            }

            OptionalLong writtenVersion = setRedisBalanceWithVersion(uuid, newBalance);
            if (writtenVersion.isEmpty()) {
                return false;
            }
            long version = writtenVersion.getAsLong();
            recordLastAppliedVersion(uuid, version);

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                String eventType = isDeposit ? "CMI_DEPOSIT" : "CMI_WITHDRAW";
                double amount = diff != null ? diff.doubleValue() : 0.0;
                syncManager.publishCMIUpdate(uuid, newBalance, version, eventType, amount, "CMI");
            }

            updateSyncBaseline(uuid, newBalance);

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI] Absolute sync: " + uuid + " = " + newBalance
                        + " v" + version + " (source=" + sourceName + ")");
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync CMI absolute balance: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read the authoritative on-disk/in-memory CMI balance for outbound publishing.
     *
     * <p>Unlike {@link #getCMIDirectBalance(UUID)} this never falls back to the Redis mirror,
     * because the mirror may lag behind a just-applied local CMI change (especially for offline
     * pay targets on the originating server).
     */
    public BigDecimal getCMILocalBalance(UUID uuid) {
        Double apiBalance = cmiApi.getBalance(uuid);
        if (apiBalance == null) {
            return null;
        }
        BigDecimal observed = NumericUtil.normalize(apiBalance);
        recordApiAuthorityBalance(uuid, observed);
        return observed;
    }

    /** Returns a previously observed CMI authority value without performing a CMI API call. */
    public Optional<BigDecimal> getCachedBalanceForApi(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("Account UUID must not be null");
        }
        BalanceObservation observation = balanceObservations.get(uuid);
        return Optional.ofNullable(observation == null ? null : observation.apiAuthorityBalance());
    }

    /** Reads only an already loaded CMI user; this never creates or loads an offline account. */
    public Optional<BigDecimal> getExistingBalanceForApi(UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("Account UUID must not be null");
        }
        Optional<BigDecimal> observed = cmiApi.getExistingBalance(uuid);
        observed.ifPresent(balance -> recordApiAuthorityBalance(uuid, balance));
        return observed;
    }

    /** Records a direct CMI authority observation; callers must run on the player owner. */
    public void recordApiAuthorityBalance(UUID uuid, BigDecimal balance) {
        if (uuid == null || balance == null) {
            return;
        }
        BigDecimal normalized = NumericUtil.normalize(balance);
        balanceObservations.compute(uuid, (key, current) -> new BalanceObservation(
                current == null ? null : current.syncBaseline(), normalized));
    }

    /** Invalidates the API snapshot after an observed CMI mutation whose result is not confirmed. */
    public void invalidateCachedBalanceForApi(UUID uuid) {
        if (uuid == null) {
            return;
        }
        balanceObservations.computeIfPresent(uuid, (key, current) -> current.syncBaseline() == null
                ? null
                : new BalanceObservation(current.syncBaseline(), null));
    }

    private BigDecimal getSyncBaseline(UUID uuid) {
        BalanceObservation observation = balanceObservations.get(uuid);
        return observation == null ? null : observation.syncBaseline();
    }

    private void updateSyncBaseline(UUID uuid, BigDecimal balance) {
        balanceObservations.compute(uuid, (key, current) -> new BalanceObservation(balance,
                current == null ? null : current.apiAuthorityBalance()));
    }

    private void recordLastAppliedVersion(UUID uuid, long version) {
        synchronized (lastAppliedVersion) {
            lastAppliedVersion.put(uuid, version);
            if (lastAppliedVersion.size() > MAX_LAST_APPLIED_VERSION_ENTRIES) {
                UUID oldest = lastAppliedVersion.keySet().iterator().next();
                lastAppliedVersion.remove(oldest);
            }
        }
    }

    private long getLastAppliedVersion(UUID uuid) {
        synchronized (lastAppliedVersion) {
            return lastAppliedVersion.getOrDefault(uuid, 0L);
        }
    }

    /**
     * Read the current CMI balance via the CMI API (used by the polling fallback).
     *
     * <p>If the CMI API cannot resolve the player, falls back to the last mirrored value rather
     * than returning zero, so a transient read failure never publishes a bogus 0 balance.
     */
    public BigDecimal getCMIDirectBalance(UUID uuid) {
        Double apiBalance = cmiApi.getBalance(uuid);
        if (apiBalance != null) {
            return NumericUtil.normalize(apiBalance);
        }
        return getRedisBalance(uuid);
    }

    public void reconcileOnJoin(UUID uuid) {
        synchronized (joinReconciliationGate) {
            if (joinReconciliationStopped) {
                return;
            }
            inFlightJoinReconciliations++;
        }
        try {
            // This in-flight count covers the asynchronous Redis read and owner-task dispatch.
            // The player callback has a separate admission point at its final state update or
            // MigrationLock write-lease acquisition; shutdown never waits on a player region.
            if (!cmiApi.isAvailable()) {
                return;
            }
            Optional<CMIRedisMirror.Snapshot> snapshot = redisMirror.read(uuid);
            if (snapshot.isEmpty()) {
                return;
            }
            BigDecimal mirror = snapshot.orElseThrow().balance();
            long mirrorVersion = snapshot.orElseThrow().version();

            noietime.syncmoney.util.PlayerLookupUtil.runForPlayer(plugin, uuid,
                    player -> applyJoinMirror(uuid, mirror, mirrorVersion));
        } finally {
            synchronized (joinReconciliationGate) {
                inFlightJoinReconciliations--;
                if (inFlightJoinReconciliations == 0) {
                    joinReconciliationGate.notifyAll();
                }
            }
        }
    }

    private void applyJoinMirror(UUID uuid, BigDecimal mirror, long mirrorVersion) {
        synchronized (joinReconciliationGate) {
            if (joinReconciliationStopped) {
                return;
            }
        }
        // Admit the owner callback briefly, then leave the gate before touching CMI.
        // Shutdown closes admission without waiting on a different player's region.
        applyJoinMirrorWhileActive(uuid, mirror, mirrorVersion);
    }

    /** Stops queued join callbacks from mutating CMI after listener shutdown. */
    public void stopJoinReconciliation() {
        boolean interrupted = false;
        synchronized (joinReconciliationGate) {
            joinReconciliationStopped = true;
            while (inFlightJoinReconciliations > 0) {
                try {
                    joinReconciliationGate.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyJoinMirrorWhileActive(UUID uuid, BigDecimal mirror, long mirrorVersion) {
        long lastApplied = getLastAppliedVersion(uuid);
        if (!CMIVersioning.isNewer(mirrorVersion, lastApplied)) {
            BigDecimal local = getCMILocalBalance(uuid);
            if (local != null && admitJoinReconciliationUpdate()) {
                notifyInboundApplied(uuid, local, lastApplied > 0 ? lastApplied : null);
            }
            return;
        }

        BigDecimal local = getCMILocalBalance(uuid);
        if (local != null && local.compareTo(mirror) == 0) {
            if (admitJoinReconciliationUpdate()) {
                notifyInboundApplied(uuid, mirror, mirrorVersion);
            }
            return;
        }
        if (local != null && local.compareTo(mirror) > 0) {
            if (admitJoinReconciliationUpdate()) {
                notifyInboundApplied(uuid, local, lastApplied > 0 ? lastApplied : null);
            }
            return;
        }

        final double target = mirror.doubleValue();
        final BigDecimal normalizedMirror = mirror;
        final long version = mirrorVersion;
        MigrationLock.WriteLease writeLease = tryAcquireJoinWriteLease();
        if (writeLease == null) {
            return;
        }
        try (writeLease) {
            suppressOutbound(uuid, OUTBOUND_SUPPRESS_MS);
            invalidateCachedBalanceForApi(uuid);
            if (cmiApi.setBalance(uuid, target)) {
                recordApiAuthorityBalance(uuid, normalizedMirror);
            } else {
                BigDecimal observed = getCMILocalBalance(uuid);
                if (observed == null || observed.compareTo(normalizedMirror) != 0) {
                    invalidateCachedBalanceForApi(uuid);
                    return;
                }
            }
            notifyInboundApplied(uuid, normalizedMirror, version);
            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI] Join reconcile " + uuid + " -> " + target + " v" + version);
            }
        }
    }

    private MigrationLock.WriteLease tryAcquireJoinWriteLease() {
        synchronized (joinReconciliationGate) {
            if (joinReconciliationStopped) {
                return null;
            }
            // Lease acquisition is the linearized admission point for this owner mutation.
            // Shutdown may return while an admitted CMI call finishes, rather than wait across
            // entity regions; callbacks that have not acquired a lease are rejected.
            return MigrationLock.tryAcquireWrite();
        }
    }

    private boolean admitJoinReconciliationUpdate() {
        synchronized (joinReconciliationGate) {
            return !joinReconciliationStopped;
        }
    }

    private static final long OUTBOUND_SUPPRESS_MS = 750L;

    /**
     * @deprecated Use {@link #reconcileOnJoin(UUID)} only. Mirror-pull during gameplay reverts local CMI.
     */
    @Deprecated
    public void reconcileFromMirror(UUID uuid) {
        reconcileOnJoin(uuid);
    }

    /**
     * Reads the mirrored balance from Redis.
     */
    private BigDecimal getRedisBalance(UUID uuid) {
        BigDecimal present = getRedisBalanceIfPresent(uuid);
        return present != null ? present : BigDecimal.ZERO;
    }

    private BigDecimal getRedisBalanceIfPresent(UUID uuid) {
        if (redisManager == null || redisManager.isDegraded()) {
            return null;
        }
        String key = RedisKeys.cmiBalance(redisPrefix, uuid);
        try (var jedis = redisManager.getResource()) {
            String value = jedis.get(key);
            return value != null ? NumericUtil.normalize(value) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets the Redis mirror balance together with the explicit legacy timestamp-derived version.
     * Used by the unified CMI publish path so the published version and the stored
     * version key always agree (see {@link CMIVersioning}).
     *
     * @return the version written
     */
    private OptionalLong setRedisBalanceWithVersion(UUID uuid, BigDecimal balance) {
        long version = generateNewVersion();
        CMIRedisMirror.WriteResult result = redisMirror.compareAndWrite(uuid, balance, version);
        if (result != CMIRedisMirror.WriteResult.ACCEPTED) {
            plugin.getLogger().warning("CMI Redis mirror rejected outbound update for " + uuid
                    + " (" + result + "); no cross-server update was published");
            return OptionalLong.empty();
        }
        return OptionalLong.of(version);
    }

    /**
     * Deposit (CMI mode specific). Updates the mirror and publishes; CMI itself is updated either
     * by the local CMI command that triggered this or by inbound consumers on other servers.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            BigDecimal newBalance = getRedisBalance(uuid).add(amount);
            OptionalLong writtenVersion = setRedisBalanceWithVersion(uuid, newBalance);
            if (writtenVersion.isEmpty()) {
                return BigDecimal.valueOf(-1);
            }
            long version = writtenVersion.getAsLong();

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                syncManager.publishCMIUpdate(uuid, newBalance, version, "CMI_DEPOSIT", amount.doubleValue(), "CMI");
            }
            return newBalance;
        }
    }

    /**
     * Withdraw (CMI mode specific).
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            BigDecimal current = getRedisBalance(uuid);
            if (current.compareTo(amount) < 0) {
                throw new IllegalArgumentException("Insufficient funds: cannot withdraw " + amount + " from balance " + current);
            }

            BigDecimal newBalance = current.subtract(amount);
            OptionalLong writtenVersion = setRedisBalanceWithVersion(uuid, newBalance);
            if (writtenVersion.isEmpty()) {
                return BigDecimal.valueOf(-1);
            }
            long version = writtenVersion.getAsLong();

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                syncManager.publishCMIUpdate(uuid, newBalance, version, "CMI_WITHDRAW", -amount.doubleValue(), "CMI");
            }
            return newBalance;
        }
    }

    /**
     * Gets balance from the Redis mirror (CMI is the authority; the mirror reflects the synced value).
     */
    public BigDecimal getBalance(UUID uuid) {
        return getRedisBalance(uuid);
    }

    /**
     * Sets balance.
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance) {
        MigrationLock.WriteLease writeLease = MigrationLock.tryAcquireWrite();
        if (writeLease == null) {
            return BigDecimal.valueOf(-1);
        }
        try (writeLease) {
            OptionalLong writtenVersion = setRedisBalanceWithVersion(uuid, newBalance);
            if (writtenVersion.isEmpty()) {
                return BigDecimal.valueOf(-1);
            }
            long version = writtenVersion.getAsLong();

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                syncManager.publishCMIUpdate(uuid, newBalance, version, "CMI_SET_BALANCE", newBalance.doubleValue(), "CMI");
            }
            return newBalance;
        }
    }
}
