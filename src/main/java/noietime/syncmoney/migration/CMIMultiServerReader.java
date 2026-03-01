package noietime.syncmoney.migration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * CMI Multi-Server Database Reader.
 * Supports merging player balance data from multiple SQLite databases.
 *
 * Merge strategies:
 * - latest: Select the most recent record based on last login time
 * - sum: Sum balances from all servers
 * - max: Select maximum balance from all servers
 */
public final class CMIMultiServerReader {


    public enum MergeStrategy {
        LATEST,
        SUM,
        MAX
    }

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<String> dbPaths = new ArrayList<>();
    private MergeStrategy mergeStrategy;


    private static final String BALANCE_COLUMN = "Balance";


    private static final String UUID_COLUMN = "player_uuid";


    private static final String USERNAME_COLUMN = "username";


    private static final String LASTLOGOFF_COLUMN = "LastLogoffTime";


    private static final String TABLE_NAME = "users";

    public CMIMultiServerReader(Plugin plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.mergeStrategy = parseStrategy(config.getCMIMergeStrategy());

        initDbPaths();
    }

    /**
     * Parses merge strategy
     */
    private MergeStrategy parseStrategy(String strategy) {
        if (strategy == null) {
            return MergeStrategy.LATEST;
        }
        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "sum" -> MergeStrategy.SUM;
            case "max" -> MergeStrategy.MAX;
            default -> MergeStrategy.LATEST;
        };
    }

    /**
     * Initializes database path list
     */
    private void initDbPaths() {
        List<String> paths = config.getCMISqlitePaths();

        if (paths == null || paths.isEmpty()) {
            String singlePath = config.getCMISqlitePath();
            if (singlePath != null && !singlePath.isEmpty()) {
                dbPaths.add(normalizePath(singlePath));
            }
        } else {
            for (String path : paths) {
                if (path != null && !path.isEmpty()) {
                    dbPaths.add(normalizePath(path));
                }
            }
        }

        plugin.getLogger().info("CMI Multi-Server: " + dbPaths.size() + " database paths configured");
        plugin.getLogger().info("CMI Multi-Server: strategy=" + mergeStrategy.name());
    }

    /**
     * Normalizes path (handles Windows/Linux path formats)
     */
    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        path = path.replace("\\", "/");
        return path.trim();
    }

    /**
     * Initializes all database connections
     */
    public synchronized void initialize() {
        if (!dataSources.isEmpty()) {
            return;
        }

        for (String dbPath : dbPaths) {
            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                plugin.getLogger().warning("CMI database not found: " + dbPath);
                continue;
            }

            try {
                HikariDataSource ds = createSQLiteDataSource(dbPath);
                dataSources.add(ds);
                plugin.getLogger().info("CMI Multi-Server: connected to " + dbPath);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to connect to CMI database " + dbPath + ": " + e.getMessage());
            }
        }

        if (dataSources.isEmpty()) {
            plugin.getLogger().severe("No CMI databases could be connected!");
        }
    }

    /**
     * Creates SQLite data source
     */
    private HikariDataSource createSQLiteDataSource(String dbPath) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(3);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("Syncmoney-CMI-Multi-" + dbPath.hashCode());
        return new HikariDataSource(hikariConfig);
    }

    /**
     * Tests all database connections
     */
    public boolean testConnections() {
        if (dataSources.isEmpty()) {
            initialize();
        }

        boolean allOk = true;
        for (int i = 0; i < dataSources.size(); i++) {
            try (Connection conn = dataSources.get(i).getConnection()) {
                if (!conn.isValid(5)) {
                    plugin.getLogger().warning("CMI database [" + i + "] connection invalid: " + dbPaths.get(i));
                    allOk = false;
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("CMI database [" + i + "] connection failed: " + e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * Reads and merges player data from all databases
     */
    public List<CMIDatabaseReader.CMIPlayerData> readAndMergePlayers() {
        if (dataSources.isEmpty()) {
            initialize();
        }

        Map<String, List<CMIDatabaseReader.CMIPlayerData>> playerMap = new LinkedHashMap<>();

        for (int i = 0; i < dataSources.size(); i++) {
            HikariDataSource ds = dataSources.get(i);
            String dbPath = dbPaths.get(i);

            try (Connection conn = ds.getConnection()) {
                String sql = "SELECT * FROM " + TABLE_NAME;
                try (PreparedStatement stmt = conn.prepareStatement(sql);
                     ResultSet rs = stmt.executeQuery()) {

                    int count = 0;
                    while (rs.next()) {
                        String playerName = rs.getString(USERNAME_COLUMN);
                        if (playerName == null || playerName.isEmpty()) {
                            continue;
                        }

                        UUID uuid = null;
                        try {
                            Object uuidObj = rs.getObject(UUID_COLUMN);
                            if (uuidObj != null) {
                                uuid = UUID.fromString(uuidObj.toString());
                            }
                        } catch (Exception ignored) {
                        }

                        BigDecimal balance = BigDecimal.ZERO;
                        try {
                            Object balanceObj = rs.getObject(BALANCE_COLUMN);
                            if (balanceObj != null) {
                                if (balanceObj instanceof Number) {
                                    balance = BigDecimal.valueOf(((Number) balanceObj).doubleValue())
                                            .setScale(2, java.math.RoundingMode.HALF_UP);
                                } else {
                                    balance = new BigDecimal(balanceObj.toString())
                                            .setScale(2, java.math.RoundingMode.HALF_UP);
                                }
                            }
                        } catch (Exception ignored) {
                        }

                        long lastLogoffTime = 0;
                        try {
                            Object lastLogoffObj = rs.getObject(LASTLOGOFF_COLUMN);
                            if (lastLogoffObj != null) {
                                if (lastLogoffObj instanceof Number) {
                                    lastLogoffTime = ((Number) lastLogoffObj).longValue();
                                } else {
                                    lastLogoffTime = Long.parseLong(lastLogoffObj.toString());
                                }
                            }
                        } catch (Exception ignored) {
                        }

                        CMIDatabaseReader.CMIPlayerData playerData =
                                new CMIDatabaseReader.CMIPlayerData(uuid, playerName, balance, lastLogoffTime);

                        playerMap.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                                .add(playerData);
                        count++;
                    }

                    plugin.getLogger().info("CMI Multi-Server: read " + count + " players from " + dbPath);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read from CMI database " + dbPath + ": " + e.getMessage());
            }
        }

        return mergePlayers(playerMap);
    }

    /**
     * Merges player data according to merge strategy
     */
    private List<CMIDatabaseReader.CMIPlayerData> mergePlayers(Map<String, List<CMIDatabaseReader.CMIPlayerData>> playerMap) {
        List<CMIDatabaseReader.CMIPlayerData> merged = new ArrayList<>();

        for (Map.Entry<String, List<CMIDatabaseReader.CMIPlayerData>> entry : playerMap.entrySet()) {
            List<CMIDatabaseReader.CMIPlayerData> playerList = entry.getValue();

            if (playerList.size() == 1) {
                merged.add(playerList.get(0));
                continue;
            }

            CMIDatabaseReader.CMIPlayerData mergedData = switch (mergeStrategy) {
                case LATEST -> mergeLatest(playerList);
                case SUM -> mergeSum(playerList);
                case MAX -> mergeMax(playerList);
            };

            merged.add(mergedData);
        }

        plugin.getLogger().info("CMI Multi-Server: merged to " + merged.size() + " unique players (strategy=" + mergeStrategy.name() + ")");
        return merged;
    }

    /**
     * Latest strategy: Select record with most recent last login time
     */
    private CMIDatabaseReader.CMIPlayerData mergeLatest(List<CMIDatabaseReader.CMIPlayerData> players) {
        CMIDatabaseReader.CMIPlayerData latest = players.get(0);
        for (CMIDatabaseReader.CMIPlayerData p : players) {
            if (p.lastLogoffTime() > latest.lastLogoffTime()) {
                latest = p;
            }
        }
        return latest;
    }

    /**
     * Sum strategy: Sum all balances
     */
    private CMIDatabaseReader.CMIPlayerData mergeSum(List<CMIDatabaseReader.CMIPlayerData> players) {
        BigDecimal totalBalance = BigDecimal.ZERO;
        UUID uuid = null;
        String playerName = players.get(0).playerName();
        long latestLogoff = 0;

        for (CMIDatabaseReader.CMIPlayerData p : players) {
            totalBalance = totalBalance.add(p.balance());
            if (p.lastLogoffTime() > latestLogoff) {
                latestLogoff = p.lastLogoffTime();
            }
            if (p.uuid() != null) {
                uuid = p.uuid();
            }
        }

        return new CMIDatabaseReader.CMIPlayerData(uuid, playerName, totalBalance, latestLogoff);
    }

    /**
     * Max strategy: Select record with maximum balance
     */
    private CMIDatabaseReader.CMIPlayerData mergeMax(List<CMIDatabaseReader.CMIPlayerData> players) {
        CMIDatabaseReader.CMIPlayerData max = players.get(0);
        for (CMIDatabaseReader.CMIPlayerData p : players) {
            if (p.balance().compareTo(max.balance()) > 0) {
                max = p;
            }
        }
        return max;
    }

    /**
     * Gets total merged player count
     */
    public int getTotalPlayerCount() {
        return readAndMergePlayers().size();
    }

    /**
     * Gets database path list
     */
    public List<String> getDbPaths() {
        return Collections.unmodifiableList(dbPaths);
    }

    /**
     * Gets merge strategy
     */
    public MergeStrategy getMergeStrategy() {
        return mergeStrategy;
    }

    /**
     * Closes all database connections
     */
    public synchronized void shutdown() {
        for (HikariDataSource ds : dataSources) {
            if (ds != null && !ds.isClosed()) {
                ds.close();
            }
        }
        dataSources.clear();
        plugin.getLogger().info("CMI Multi-Server: all connections closed");
    }
}
