package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.sync.CMIApi;
import noietime.syncmoney.sync.CMIVersioning;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CMI Economy Handler — the cross-server sync layer for CMI economy.
 *
 * <p>Design (see {@code reference/CMIEconomySync}): CMI is the local economy authority. This
 * handler does NOT query CMI's database at runtime; it reads balances from the in-memory CMI API
 * (via {@link CMIApi}) and uses Redis purely as the sync transport + mirror. Outbound changes are
 * published as absolute balances with a monotonic version; inbound updates are applied back into
 * CMI by {@code CMIPubsubHandler}.
 *
 * [ThreadSafe] Redis I/O is performed on async threads by callers; CMI writes go through the
 * global region scheduler.
 */
public class CMIEconomyHandler {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final CrossServerSyncManager syncManager;
    private final CMIApi cmiApi;
    private final String redisPrefix;

    private final ConcurrentHashMap<UUID, BigDecimal> lastKnownBalance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastAppliedVersion = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0L);

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
        lastKnownBalance.put(uuid, absoluteBalance);
        if (appliedVersion != null) {
            lastAppliedVersion.put(uuid, appliedVersion);
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
        try {
            BigDecimal newBalance = NumericUtil.normalize(absoluteBalance);
            BigDecimal last = lastKnownBalance.get(uuid);
            if (last != null && last.compareTo(newBalance) == 0) {
                return;
            }

            long version = setRedisBalanceWithVersion(uuid, newBalance);
            lastAppliedVersion.put(uuid, version);

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                String eventType = isDeposit ? "CMI_DEPOSIT" : "CMI_WITHDRAW";
                double amount = diff != null ? diff.doubleValue() : 0.0;
                syncManager.publishCMIUpdate(uuid, newBalance, version, eventType, amount, "CMI");
            }

            lastKnownBalance.put(uuid, newBalance);

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI] Absolute sync: " + uuid + " = " + newBalance
                        + " v" + version + " (source=" + sourceName + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync CMI absolute balance: " + e.getMessage());
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
        return apiBalance != null ? NumericUtil.normalize(apiBalance) : null;
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
        if (!cmiApi.isAvailable()) {
            return;
        }
        BigDecimal mirror = getRedisBalanceIfPresent(uuid);
        Long mirrorVersion = getRedisVersionIfPresent(uuid);
        if (mirror == null || mirrorVersion == null) {
            return;
        }

        long lastApplied = lastAppliedVersion.getOrDefault(uuid, 0L);
        if (!CMIVersioning.isNewer(mirrorVersion, lastApplied)) {
            BigDecimal local = getCMILocalBalance(uuid);
            if (local != null) {
                notifyInboundApplied(uuid, local, lastApplied > 0 ? lastApplied : null);
            }
            return;
        }

        BigDecimal local = getCMILocalBalance(uuid);
        if (local != null && local.compareTo(mirror) == 0) {
            notifyInboundApplied(uuid, mirror, mirrorVersion);
            return;
        }
        if (local != null && local.compareTo(mirror) > 0) {
            notifyInboundApplied(uuid, local, lastApplied > 0 ? lastApplied : null);
            return;
        }

        final double target = mirror.doubleValue();
        final BigDecimal normalizedMirror = mirror;
        final long version = mirrorVersion;
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            suppressOutbound(uuid, OUTBOUND_SUPPRESS_MS);
            cmiApi.setBalance(uuid, target);
            notifyInboundApplied(uuid, normalizedMirror, version);
            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI] Join reconcile " + uuid + " -> " + target + " v" + version);
            }
        });
    }

    private static final long OUTBOUND_SUPPRESS_MS = 750L;

    /**
     * @deprecated Use {@link #reconcileOnJoin(UUID)} only. Mirror-pull during gameplay reverts local CMI.
     */
    @Deprecated
    public void reconcileFromMirror(UUID uuid) {
        reconcileOnJoin(uuid);
    }

    private Long getRedisVersionIfPresent(UUID uuid) {
        if (redisManager == null || redisManager.isDegraded()) {
            return null;
        }
        String versionKey = redisPrefix + ":" + uuid + ":version";
        try (var jedis = redisManager.getResource()) {
            String value = jedis.get(versionKey);
            if (value == null) {
                return null;
            }
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
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
        String key = redisPrefix + ":" + uuid.toString();
        try (var jedis = redisManager.getResource()) {
            String value = jedis.get(key);
            return value != null ? NumericUtil.normalize(value) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets the Redis mirror balance together with an explicit, monotonic version.
     * Used by the unified CMI publish path so the published version and the stored
     * version key always agree (see {@link CMIVersioning}).
     *
     * @return the version written
     */
    private long setRedisBalanceWithVersion(UUID uuid, BigDecimal balance) {
        long version = generateNewVersion();
        String key = redisPrefix + ":" + uuid.toString();
        String versionKey = key + ":version";

        try (var jedis = redisManager.getResource()) {
            jedis.set(key, balance.toPlainString());
            jedis.set(versionKey, String.valueOf(version));
        }
        return version;
    }

    /**
     * Deposit (CMI mode specific). Updates the mirror and publishes; CMI itself is updated either
     * by the local CMI command that triggered this or by inbound consumers on other servers.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount) {
        BigDecimal newBalance = getRedisBalance(uuid).add(amount);
        long version = setRedisBalanceWithVersion(uuid, newBalance);

        if (config.cmi().isCMICrossServerSync() && syncManager != null) {
            syncManager.publishCMIUpdate(uuid, newBalance, version, "CMI_DEPOSIT", amount.doubleValue(), "CMI");
        }
        return newBalance;
    }

    /**
     * Withdraw (CMI mode specific).
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        BigDecimal current = getRedisBalance(uuid);
        if (current.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds: cannot withdraw " + amount + " from balance " + current);
        }

        BigDecimal newBalance = current.subtract(amount);
        long version = setRedisBalanceWithVersion(uuid, newBalance);

        if (config.cmi().isCMICrossServerSync() && syncManager != null) {
            syncManager.publishCMIUpdate(uuid, newBalance, version, "CMI_WITHDRAW", -amount.doubleValue(), "CMI");
        }
        return newBalance;
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
        long version = setRedisBalanceWithVersion(uuid, newBalance);

        if (config.cmi().isCMICrossServerSync() && syncManager != null) {
            syncManager.publishCMIUpdate(uuid, newBalance, version, "CMI_SET_BALANCE", newBalance.doubleValue(), "CMI");
        }
        return newBalance;
    }
}
