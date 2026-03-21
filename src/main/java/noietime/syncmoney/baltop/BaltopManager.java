package noietime.syncmoney.baltop;

import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.storage.RedisManager;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.util.FormatUtil;
import noietime.syncmoney.util.Constants;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.Tuple;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Cross-server leaderboard manager.
 * Implements leaderboard functionality using Redis ZSET (or SQLite for LOCAL
 * mode).
 *
 * [AsyncScheduler] This class can be safely executed on async threads.
 */
public final class BaltopManager {

    private static final String BALTOP_KEY = "syncmoney:baltop";
    private static final String TOTAL_SUPPLY_KEY = "syncmoney:baltop:total";

    private static final int MAX_TOP_CACHE_ENTRIES = 30;

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;
    private final NameResolver nameResolver;
    private final HikariDataSource dataSource;
    private final LocalEconomyHandler localEconomyHandler;
    private final Logger logger;

    private final Map<Integer, List<RankEntry>> topCache;
    private volatile long lastCacheTime = 0;
    private final long cacheExpirationMs;

    private volatile boolean enabled = true;
    private final boolean isLocalMode;

    private final List<NumberFormatEntry> numberFormatList;

    public BaltopManager(Plugin plugin, SyncmoneyConfig config,
            RedisManager redisManager, NameResolver nameResolver,
            HikariDataSource dataSource) {
        this(plugin, config, redisManager, nameResolver, dataSource, null);
    }

    public BaltopManager(Plugin plugin, SyncmoneyConfig config,
            RedisManager redisManager, NameResolver nameResolver,
            HikariDataSource dataSource, LocalEconomyHandler localEconomyHandler) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
        this.nameResolver = nameResolver;
        this.dataSource = dataSource;
        this.localEconomyHandler = localEconomyHandler;
        this.logger = plugin.getLogger();
        this.topCache = new ConcurrentHashMap<>();
        this.cacheExpirationMs = config.baltop().getBaltopCacheSeconds() * 1000L;
        this.numberFormatList = loadNumberFormatConfig();

        this.isLocalMode = (dataSource == null && localEconomyHandler != null);

        topCache.clear();
        lastCacheTime = 0;

        if (dataSource == null && localEconomyHandler == null) {
            enabled = false;
            logger.fine("Baltop manager disabled: no database source available.");
            return;
        }

        if (isLocalMode) {
            logger.fine("Baltop manager initialized in LOCAL mode (SQLite).");
            initializeLocalSchema();
        } else {
            initializeSchema();
        }
    }

    /**
     * Loads number format configuration from config.yml
     */
    private List<NumberFormatEntry> loadNumberFormatConfig() {
        List<NumberFormatEntry> result = new ArrayList<>();
        List<Object> formatList = config.baltop().getBaltopNumberFormat();

        if (formatList != null) {
            for (Object entry : formatList) {
                if (entry instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) entry;
                    if (list.size() >= 2) {
                        try {
                            double threshold = ((Number) list.get(0)).doubleValue();
                            String label = String.valueOf(list.get(1));
                            result.add(new NumberFormatEntry(threshold, label));
                        } catch (Exception e) {
                            logger.warning("Invalid number format entry: " + entry);
                        }
                    }
                }
            }
        }

        result.sort((a, b) -> Double.compare(b.threshold(), a.threshold()));

        if (result.isEmpty()) {
            result.add(new NumberFormatEntry(1_000_000_000_000.0, "T"));
            result.add(new NumberFormatEntry(1_000_000_000.0, "B"));
            result.add(new NumberFormatEntry(1_000_000.0, "M"));
            result.add(new NumberFormatEntry(1_000.0, "K"));
        }

        return result;
    }

    /**
     * Record for number format entry (threshold and label)
     */
    private record NumberFormatEntry(double threshold, String label) {
    }

    /**
     * Initializes database schema.
     */
    private void initializeSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS syncmoney_baltop (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(16),
                    balance DECIMAL(20, 2) NOT NULL,
                    rank INT,
                    last_update BIGINT NOT NULL
                )
                """;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
            logger.fine("Baltop table initialized.");

            loadFromDatabase();
        } catch (SQLException e) {
            logger.severe("Failed to initialize baltop table: " + e.getMessage());
        }
    }

    /**
     * Initializes local SQLite schema for LOCAL mode.
     */
    private void initializeLocalSchema() {
        if (localEconomyHandler == null) {
            return;
        }

        logger.fine("Baltop initialized in LOCAL mode using SQLite.");
    }

    /**
     * Loads leaderboard data from database into Redis.
     * Falls back to players table if syncmoney_baltop is empty.
     */
    private void loadFromDatabase() {
        String baltopSql = "SELECT player_uuid, player_name, balance FROM syncmoney_baltop ORDER BY balance DESC LIMIT 1000";

        try (Connection conn = dataSource.getConnection();
                PreparedStatement baltopStmt = conn.prepareStatement(baltopSql);
                ResultSet rs = baltopStmt.executeQuery()) {

            try (Jedis jedis = redisManager.getResource()) {
                jedis.del(BALTOP_KEY);
                jedis.del(TOTAL_SUPPLY_KEY);

                double totalSupply = 0.0;
                int rank = 1;
                boolean hasBaltopData = false;

                while (rs.next()) {
                    hasBaltopData = true;
                    String uuid = rs.getString("player_uuid");
                    String name = rs.getString("player_name");
                    double balance = rs.getDouble("balance");

                    jedis.zadd(BALTOP_KEY, balance, uuid);
                    totalSupply += balance;

                    if (name != null) {
                        nameResolver.cacheName(name, UUID.fromString(uuid));
                    }

                    rank++;
                }

                
                if (!hasBaltopData) {
                    logger.fine("Baltop table is empty, falling back to players table for initial load.");
                    loadFromPlayersTable(jedis, conn);
                } else {
                    jedis.set(TOTAL_SUPPLY_KEY, String.valueOf(totalSupply));
                    logger.fine("Loaded " + (rank - 1) + " players from database to baltop.");
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to load baltop from database: " + e.getMessage());
        }
    }

    /**
     * Loads leaderboard data from players table as fallback.
     */
    private void loadFromPlayersTable(Jedis jedis, Connection conn) {
        String playersSql = "SELECT player_uuid, player_name, balance FROM players ORDER BY balance DESC LIMIT 1000";

        try (PreparedStatement stmt = conn.prepareStatement(playersSql);
                ResultSet rs = stmt.executeQuery()) {

            double totalSupply = 0.0;
            int rank = 1;

            while (rs.next()) {
                String uuid = rs.getString("player_uuid");
                String name = rs.getString("player_name");
                double balance = rs.getDouble("balance");

                if (uuid != null && balance > 0) {
                    jedis.zadd(BALTOP_KEY, balance, uuid);
                    totalSupply += balance;

                    if (name != null) {
                        try {
                            nameResolver.cacheName(name, UUID.fromString(uuid));
                        } catch (IllegalArgumentException ignored) {
                            
                        }
                    }
                }
                rank++;
            }

            jedis.set(TOTAL_SUPPLY_KEY, String.valueOf(totalSupply));
            logger.fine("Loaded " + (rank - 1) + " players from players table to baltop (fallback).");
        } catch (Exception e) {
            logger.warning("Failed to load baltop from players table: " + e.getMessage());
        }
    }

    /**
     * Writes leaderboard to database.
     */
    public void saveToDatabase() {
        if (!enabled) {
            return;
        }

        if (dataSource == null) {
            logger.fine("Baltop database save skipped - LOCAL mode (no database).");
            return;
        }

        List<RankEntry> topList = getTopRank(1000);

        if (topList.isEmpty()) {
            logger.fine("Baltop database save skipped - no players in ranking.");
            return;
        }

        String sql = """
                INSERT INTO syncmoney_baltop (player_uuid, player_name, balance, rank, last_update)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), balance = VALUES(balance), rank = VALUES(rank), last_update = VALUES(last_update)
                """;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            long now = System.currentTimeMillis();
            int batchCount = 0;
            for (RankEntry entry : topList) {
                String playerName = entry.name();
                if (playerName == null || playerName.startsWith("Unknown-")) {
                    playerName = nameResolver.getNameCachedOnly(entry.uuid());
                    if (playerName == null) {
                        playerName = entry.uuid().toString().substring(0, 8);
                    }
                }

                if (playerName != null && playerName.length() > 16) {
                    playerName = playerName.substring(0, 16);
                }

                stmt.setString(1, entry.uuid().toString());
                stmt.setString(2, playerName);
                stmt.setDouble(3, entry.balance());
                stmt.setInt(4, entry.rank());
                stmt.setLong(5, now);
                stmt.addBatch();
                batchCount++;
            }

            int[] results = stmt.executeBatch();
            logger.fine("Saved " + batchCount + " players to baltop database.");
        } catch (Exception e) {
            logger.severe("Failed to save baltop to database: " + e.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "Baltop save stacktrace", e);
        }
    }

    /**
     * Updates player rank.
     * 
     * @param uuid    Player UUID
     * @param balance Player balance
     */
    public void updatePlayerRank(UUID uuid, double balance) {
        if (!enabled) {
            return;
        }

        if (isLocalMode) {
            invalidateCache();
            logger.fine("Baltop cache invalidated for player " + uuid + " (LOCAL mode)");
            return;
        }

        try (Jedis jedis = redisManager.getResource()) {
            Double oldScore = jedis.zscore(BALTOP_KEY, uuid.toString());
            double oldBalance = oldScore != null ? oldScore : 0.0;

            long added = jedis.zadd(BALTOP_KEY, balance, uuid.toString());
            if (added == 0) {
                logger.fine("Updated baltop for player " + uuid + ": " + oldBalance + " -> " + balance);
            } else {
                logger.fine("Added new player to baltop: " + uuid + " with balance " + balance);
            }

            double delta = balance - oldBalance;
            jedis.incrByFloat(TOTAL_SUPPLY_KEY, delta);

            invalidateCache();
        } catch (Exception e) {
            logger.severe("Failed to update player rank for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Removes player rank.
     */
    public void removePlayerRank(UUID uuid) {
        if (!enabled) {
            return;
        }

        if (isLocalMode) {
            invalidateCache();
            return;
        }

        try (Jedis jedis = redisManager.getResource()) {
            Double oldScore = jedis.zscore(BALTOP_KEY, uuid.toString());
            double oldBalance = oldScore != null ? oldScore : 0.0;

            jedis.zrem(BALTOP_KEY, uuid.toString());

            if (oldBalance > 0) {
                jedis.incrByFloat(TOTAL_SUPPLY_KEY, -oldBalance);
            }

            invalidateCache();
        } catch (Exception e) {
            logger.warning("Failed to remove player rank: " + e.getMessage());
        }
    }

    /**
     * Gets leaderboard top N players.
     * 
     * @param count Number of entries
     * @return Rank list
     */
    public List<RankEntry> getTopRank(int count) {
        if (!enabled) {
            return Collections.emptyList();
        }
        if (isLocalMode) {
            return getTopRankLocal(count);
        }
        if (isCacheValid() && topCache.containsKey(count)) {
            return topCache.get(count);
        }

        List<RankEntry> result = fetchTopRankFromRedis(count);
        updateRankCache(count, result);

        return result;
    }

    /**
     * Fetches top rank entries from Redis.
     */
    private List<RankEntry> fetchTopRankFromRedis(int count) {
        List<RankEntry> result = new ArrayList<>();

        try (Jedis jedis = redisManager.getResource()) {
            List<Tuple> topPlayers = jedis.zrevrangeWithScores(BALTOP_KEY, 0, count - 1);

            int rank = 1;
            for (Tuple tuple : topPlayers) {
                RankEntry entry = processRankEntry(tuple, rank);
                if (entry != null) {
                    result.add(entry);
                    rank++;
                }
            }
        } catch (Exception e) {
            logger.severe("Failed to get top rank: " + e.getMessage());
        }

        return result;
    }

    /**
     * Processes a single rank entry from Redis tuple.
     */
    private RankEntry processRankEntry(Tuple tuple, int rank) {
        try {
            UUID uuid = UUID.fromString(tuple.getElement());
            String name = resolvePlayerName(uuid);

            return new RankEntry(rank, uuid, name, tuple.getScore());
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid UUID in baltop: " + tuple.getElement());
            return null;
        }
    }

    /**
     * Resolves player name from multiple sources.
     */
    private String resolvePlayerName(UUID uuid) {
        String name = nameResolver.getName(uuid);

        if (name == null) {
            name = queryPlayerNameFromDatabase(uuid);
            if (name != null) {
                nameResolver.cacheName(name, uuid);
            }
        }

        if (name == null) {
            var offlinePlayer = org.bukkit.Bukkit.getServer().getOfflinePlayer(uuid);
            if (offlinePlayer != null && offlinePlayer.getName() != null) {
                name = offlinePlayer.getName();
            }
        }

        if (name == null) {
            nameResolver.triggerAsyncNameLookup(uuid);
            name = "Unknown-" + uuid.toString().substring(0, 8);
        }

        return name;
    }

    /**
     * Updates the rank cache.
     */
    private void updateRankCache(int count, List<RankEntry> result) {
        if (topCache.size() >= Constants.MAX_TOP_CACHE_ENTRIES) {
            topCache.clear();
        }
        topCache.put(count, result);
        lastCacheTime = System.currentTimeMillis();
    }

    /**
     * Gets top rank from local SQLite (LOCAL mode).
     */
    private List<RankEntry> getTopRankLocal(int count) {
        List<RankEntry> result = new ArrayList<>();

        if (localEconomyHandler == null) {
            return result;
        }

        try {
            List<Map<String, Object>> balances = localEconomyHandler.getAllBalancesSorted(count);

            int rank = 1;
            for (Map<String, Object> entry : balances) {
                String uuidStr = (String) entry.get("uuid");
                String name = (String) entry.get("name");
                double balance = (Double) entry.get("balance");

                try {
                    UUID uuid = UUID.fromString(uuidStr);

                    boolean isValidName = name != null && !name.isEmpty() &&
                            !name.matches(
                                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

                    if (!isValidName) {
                        name = nameResolver.getName(uuid);
                        if (name == null) {
                            var offlinePlayer = org.bukkit.Bukkit.getServer().getOfflinePlayer(uuid);
                            if (offlinePlayer != null && offlinePlayer.getName() != null) {
                                name = offlinePlayer.getName();
                            }
                        }
                        if (name == null) {
                            name = "Unknown-" + uuid.toString().substring(0, 8);
                        }
                    }

                    result.add(new RankEntry(rank, uuid, name, balance));
                    rank++;
                } catch (IllegalArgumentException e) {
                    logger.warning("Invalid UUID in local baltop: " + uuidStr);
                }
            }

            logger.fine("Loaded " + result.size() + " players from local baltop.");
        } catch (Exception e) {
            logger.severe("Failed to get local top rank: " + e.getMessage());
        }

        if (topCache.size() >= MAX_TOP_CACHE_ENTRIES) {
            topCache.clear();
        }
        topCache.put(count, result);
        lastCacheTime = System.currentTimeMillis();

        return result;
    }

    /**
     * Gets player rank position (1-indexed).
     * 
     * @return Rank position, -1 if not exists
     */
    public int getPlayerRank(UUID uuid) {
        if (!enabled) {
            return -1;
        }

        if (isLocalMode) {
            return getPlayerRankLocal(uuid);
        }

        try (Jedis jedis = redisManager.getResource()) {
            Long rank = jedis.zrevrank(BALTOP_KEY, uuid.toString());
            return rank != null ? (int) (rank + 1) : -1;
        } catch (Exception e) {
            logger.warning("Failed to get player rank: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Gets player rank from local SQLite.
     */
    private int getPlayerRankLocal(UUID uuid) {
        if (localEconomyHandler == null) {
            return -1;
        }

        try {
            List<Map<String, Object>> balances = localEconomyHandler.getAllBalancesSorted(10000);
            int rank = 1;
            for (Map<String, Object> entry : balances) {
                if (entry.get("uuid").equals(uuid.toString())) {
                    return rank;
                }
                rank++;
            }
        } catch (Exception e) {
            logger.warning("Failed to get local player rank: " + e.getMessage());
        }

        return -1;
    }

    /**
     * Gets player balance.
     */
    public double getPlayerBalance(UUID uuid) {
        if (!enabled) {
            return 0.0;
        }

        if (isLocalMode) {
            return getPlayerBalanceLocal(uuid);
        }

        try (Jedis jedis = redisManager.getResource()) {
            Double score = jedis.zscore(BALTOP_KEY, uuid.toString());
            return score != null ? score : 0.0;
        } catch (Exception e) {
            logger.warning("Failed to get player balance: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Gets player balance from local SQLite.
     */
    private double getPlayerBalanceLocal(UUID uuid) {
        if (localEconomyHandler == null) {
            return 0.0;
        }

        try {
            BigDecimal balance = localEconomyHandler.getBalance(uuid);
            return balance.doubleValue();
        } catch (Exception e) {
            logger.warning("Failed to get local player balance: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Gets server total currency amount.
     */
    public double getTotalSupply() {
        if (!enabled) {
            return 0.0;
        }

        if (isLocalMode) {
            return getTotalSupplyLocal();
        }

        try (Jedis jedis = redisManager.getResource()) {
            String total = jedis.get(TOTAL_SUPPLY_KEY);
            return total != null ? Double.parseDouble(total) : 0.0;
        } catch (Exception e) {
            logger.warning("Failed to get total supply: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Gets total supply from local SQLite.
     */
    private double getTotalSupplyLocal() {
        if (localEconomyHandler == null) {
            return 0.0;
        }

        try {
            BigDecimal total = localEconomyHandler.getTotalBalance();
            return total.doubleValue();
        } catch (Exception e) {
            logger.warning("Failed to get local total supply: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Gets total leaderboard player count.
     */
    public int getTotalPlayers() {
        if (!enabled) {
            return 0;
        }

        if (isLocalMode) {
            return getTotalPlayersLocal();
        }

        try (Jedis jedis = redisManager.getResource()) {
            return (int) jedis.zcard(BALTOP_KEY);
        } catch (Exception e) {
            logger.warning("Failed to get total players: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets total players from local SQLite.
     */
    private int getTotalPlayersLocal() {
        if (localEconomyHandler == null) {
            return 0;
        }

        try {
            return localEconomyHandler.getTotalPlayerCount();
        } catch (Exception e) {
            logger.warning("Failed to get local total players: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets total registered player count from database.
     * This includes ALL players with balance > 0, not just top 1000.
     * Supports all modes: LOCAL (SQLite), LOCAL_REDIS, and SYNC.
     */
    public int getTotalRegisteredPlayers() {
        
        if (isLocalMode) {
            return getTotalPlayersLocal();
        }

        
        if (dataSource == null) {
            logger.fine("BaltopManager: No dataSource, falling back to getTotalPlayers()");
            return getTotalPlayers();
        }

        String sql = "SELECT COUNT(*) FROM players WHERE balance > 0";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                int count = rs.getInt(1);
                logger.fine("BaltopManager: Total registered players from DB: " + count);
                return count;
            }
        } catch (SQLException e) {
            logger.warning("Failed to get total registered players from database: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Smart number formatting using config.
     */
    public String formatNumberSmart(double value) {
        BigDecimal bd = BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP);

        for (NumberFormatEntry entry : numberFormatList) {
            if (bd.compareTo(new BigDecimal(entry.threshold())) >= 0) {
                BigDecimal divisor = new BigDecimal(entry.threshold());
                return bd.divide(divisor, 1, RoundingMode.HALF_UP) + entry.label();
            }
        }

        return FormatUtil.formatCurrency(value);
    }

    /**
     * Smart number formatting (English abbreviations).
     */
    public String formatNumberAbbreviated(double value) {
        for (NumberFormatEntry entry : numberFormatList) {
            if (value >= entry.threshold()) {
                return String.format("%.2f%s", value / entry.threshold(), entry.label());
            }
        }

        return FormatUtil.formatCurrency(value);
    }

    /**
     * Clears cache.
     */
    public void invalidateCache() {
        topCache.clear();
        lastCacheTime = 0;
    }

    /**
     * Checks if cache is valid.
     */
    private boolean isCacheValid() {
        return lastCacheTime > 0 &&
                (System.currentTimeMillis() - lastCacheTime) < cacheExpirationMs;
    }

    /**
     * Syncs player rank from balance.
     */
    public void syncFromBalance(UUID uuid, double balance) {
        updatePlayerRank(uuid, balance);
    }

    /**
     * Synchronously queries player name from database.
     * 
     * @param uuid Player UUID
     * @return Player name, or null if not found
     */
    private String queryPlayerNameFromDatabase(UUID uuid) {
        if (dataSource == null) {
            return null;
        }

        String sql = "SELECT player_name FROM players WHERE player_uuid = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("player_name");
                }
            }
        } catch (SQLException e) {
            logger.warning("Failed to query player name from database: " + e.getMessage());
        }
        return null;
    }
}
