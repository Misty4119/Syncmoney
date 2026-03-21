package noietime.syncmoney.economy;

import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.config.SyncmoneyConfig.CMIBalanceMode;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.util.NumericUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CMI Economy Handler.
 * Uses Redis to store CMI economy data, synchronized via Pub/Sub.
 *
 * Supports two balance modes:
 * - API: Uses CMI API for balance operations
 * - INTERNAL: Direct database access (faster, default)
 */
public class CMIEconomyHandler {

    private final Syncmoney plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final CrossServerSyncManager syncManager;
    private final String redisPrefix;
    private final CMIBalanceMode balanceMode;

    private final ConcurrentHashMap<UUID, BigDecimal> lastKnownBalance = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0L);

    private Connection cmiConnection;

    public CMIEconomyHandler(Syncmoney plugin, SyncmoneyConfig config,
                           RedisManager redisManager,
                           CrossServerSyncManager syncManager) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.syncManager = syncManager;
        this.redisPrefix = config.cmi().getCMIRedisPrefix();
        this.balanceMode = config.getCMIBalanceMode();

        initCMIConnection();
        plugin.getLogger().fine("CMI Economy Handler initialized with mode: " + balanceMode);
    }

    /**
     * Get current balance mode.
     */
    public CMIBalanceMode getBalanceMode() {
        return balanceMode;
    }

    /**
     * Handle external balance change from CMI event.
     * This is called when CMI detects a balance change.
     */
    public void handleExternalBalanceChange(UUID uuid, BigDecimal diff, boolean isDeposit, String sourceName) {
        try {
            BigDecimal currentRedisBalance = getRedisBalance(uuid);

            BigDecimal newBalance = currentRedisBalance.add(diff);

            setRedisBalance(uuid, newBalance);

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                String eventType = isDeposit ? "CMI_DEPOSIT" : "CMI_WITHDRAW";
                syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    eventType,
                    diff.doubleValue(),
                    "CMI",
                    null
                );
            }

            lastKnownBalance.put(uuid, newBalance);

            if (config.isDebug()) {
                plugin.getLogger().fine("[CMI] External balance change: " + uuid +
                    ", diff: " + diff + ", new balance: " + newBalance);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to handle external balance change: " + e.getMessage());
        }
    }

    /**
     * Generate new version number using CMIEconomySync strategy.
     */
    private long generateNewVersion() {
        long millis = System.currentTimeMillis();
        long counter = versionCounter.incrementAndGet() % 10000L;
        return millis * 10000L + counter;
    }

    /**
     * Get direct balance from CMI database.
     * Used by polling fallback mechanism.
     */
    public BigDecimal getCMIDirectBalance(UUID uuid) {
        try {
            return NumericUtil.normalize(getCMIBalance(uuid));
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get CMI balance: " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Initializes CMI database connection.
     */
    private void initCMIConnection() {
        try {
            String sqlitePath = config.migration().getCMISqlitePath();
            if (sqlitePath != null && !sqlitePath.isEmpty()) {
                String dbUrl = "jdbc:sqlite:" + sqlitePath;
                cmiConnection = DriverManager.getConnection(dbUrl);
                plugin.getLogger().fine("CMI SQLite database connected: " + sqlitePath);
            } else {
                String host = config.migration().getCMIDatabaseHost();
                int port = config.migration().getCMIDatabasePort();
                String db = config.migration().getCMIDatabaseName();
                String user = config.migration().getCMIDatabaseUsername();
                String pass = config.migration().getCMIDatabasePassword();

                String dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + db;
                cmiConnection = DriverManager.getConnection(dbUrl, user, pass);
                plugin.getLogger().fine("CMI MySQL database connected: " + host + ":" + port);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("CMI database connection failed: " + e.getMessage());
        }
    }

    /**
     * Synchronizes player balance from CMI to Redis.
     */
    public void syncPlayerFromCMI(UUID uuid) {
        try {
            BigDecimal cmiBalance = getCMIBalance(uuid);

            BigDecimal redisBalance = getRedisBalance(uuid);

            if (cmiBalance.subtract(redisBalance).abs().compareTo(BigDecimal.valueOf(0.01)) > 0) {
                setRedisBalance(uuid, cmiBalance);

                if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                    syncManager.publishAndNotify(
                        uuid,
                        cmiBalance,
                        "CMI_DEPOSIT",
                        cmiBalance.subtract(redisBalance).doubleValue(),
                        "CMI",
                        null
                    );
                }

                lastKnownBalance.put(uuid, cmiBalance);

                plugin.getLogger().fine("CMI balance synchronized: " + uuid + " = " + cmiBalance);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("CMI balance synchronization failed: " + e.getMessage());
        }
    }

    /**
     * Reads balance from CMI database.
     */
    private BigDecimal getCMIBalance(UUID uuid) throws SQLException {
        String tablePrefix = config.migration().getCMITablePrefix();
        String sql = "SELECT * FROM " + tablePrefix + "users WHERE uuid = ?";

        try (PreparedStatement stmt = cmiConnection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return NumericUtil.normalize(rs.getDouble("money"));
                }
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Reads balance from Redis.
     */
    private BigDecimal getRedisBalance(UUID uuid) {
        String key = redisPrefix + ":" + uuid.toString();

        try (var jedis = redisManager.getResource()) {
            String value = jedis.get(key);
            return value != null ? NumericUtil.normalize(value) : BigDecimal.ZERO;
        }
    }

    /**
     * Sets Redis balance.
     */
    private void setRedisBalance(UUID uuid, BigDecimal balance) {
        String key = redisPrefix + ":" + uuid.toString();
        String versionKey = redisPrefix + ":" + uuid.toString() + ":version";

        try (var jedis = redisManager.getResource()) {
            jedis.set(key, balance.toPlainString());
            jedis.incr(versionKey);
        }
    }

    /**
     * Deposit (CMI mode specific).
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount) {
        String key = redisPrefix + ":" + uuid.toString();
        String versionKey = redisPrefix + ":" + uuid.toString() + ":version";

        try (var jedis = redisManager.getResource()) {
            BigDecimal current = getRedisBalance(uuid);
            BigDecimal newBalance = current.add(amount);

            jedis.set(key, newBalance.toPlainString());
            jedis.incr(versionKey);

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "CMI_DEPOSIT",
                    amount.doubleValue(),
                    "CMI",
                    null
                );
            }

            return newBalance;
        }
    }

    /**
     * Withdraw (CMI mode specific).
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        String key = redisPrefix + ":" + uuid.toString();
        String versionKey = redisPrefix + ":" + uuid.toString() + ":version";

        try (var jedis = redisManager.getResource()) {
            BigDecimal current = getRedisBalance(uuid);
            if (current.compareTo(amount) < 0) {
                throw new IllegalArgumentException("Insufficient funds: cannot withdraw " + amount + " from balance " + current);
            }

            BigDecimal newBalance = current.subtract(amount);

            jedis.set(key, newBalance.toPlainString());
            jedis.incr(versionKey);

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "CMI_WITHDRAW",
                    -amount.doubleValue(),
                    "CMI",
                    null
                );
            }

            return newBalance;
        }
    }

    /**
     * Gets balance.
     */
    public BigDecimal getBalance(UUID uuid) {
        return getRedisBalance(uuid);
    }

    /**
     * Sets balance.
     */
    public BigDecimal setBalance(UUID uuid, BigDecimal newBalance) {
        String key = redisPrefix + ":" + uuid.toString();
        String versionKey = redisPrefix + ":" + uuid.toString() + ":version";

        try (var jedis = redisManager.getResource()) {
            jedis.set(key, newBalance.toPlainString());
            jedis.incr(versionKey);

            if (config.cmi().isCMICrossServerSync() && syncManager != null) {
                syncManager.publishAndNotify(
                    uuid,
                    newBalance,
                    "CMI_SET_BALANCE",
                    newBalance.doubleValue(),
                    "CMI",
                    null
                );
            }

            return newBalance;
        }
    }

    /**
     * Periodically synchronizes all online players.
     */
    public void syncAllOnlinePlayers() {
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            syncPlayerFromCMI(player.getUniqueId());
        });
    }

    /**
     * Closes database connection.
     */
    public void close() {
        try {
            if (cmiConnection != null && !cmiConnection.isClosed()) {
                cmiConnection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to close CMI connection: " + e.getMessage());
        }
    }
}
