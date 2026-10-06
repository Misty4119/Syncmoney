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
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final List<HikariDataSource> dataSources = new CopyOnWriteArrayList<>();
    private final List<String> dbPaths = new CopyOnWriteArrayList<>();
    private MergeStrategy mergeStrategy;

    private static final String BALANCE_COLUMN = "Balance";

    private static final String UUID_COLUMN = "player_uuid";

    private static final String USERNAME_COLUMN = "username";

    private static final String LASTLOGOFF_COLUMN = "LastLogoffTime";

    private static final String TABLE_NAME = "users";

    public CMIMultiServerReader(Plugin plugin, SyncmoneyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.mergeStrategy = parseStrategy(config.migration().getCMIMergeStrategy());

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
        List<String> paths = config.migration().getCMISqlitePaths();

        if (paths == null || paths.isEmpty()) {
            String singlePath = config.migration().getCMISqlitePath();
            if (singlePath != null && !singlePath.isEmpty()) {
                dbPaths.add(normalizePath(singlePath));
            }
        } else {
            for (String path : paths) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("CMI multi-server database paths must not contain blank entries");
                }
                dbPaths.add(normalizePath(path));
            }
        }

        plugin.getLogger().fine("CMI Multi-Server: " + dbPaths.size() + " database paths configured");
        plugin.getLogger().fine("CMI Multi-Server: strategy=" + mergeStrategy.name());
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
            if (dataSources.size() != dbPaths.size()) {
                throw new IllegalStateException("Only part of the configured CMI multi-server databases are connected");
            }
            return;
        }

        if (dbPaths.isEmpty()) {
            throw new IllegalStateException("No CMI multi-server database paths are configured");
        }

        try {
            for (String dbPath : dbPaths) {
                File dbFile = new File(dbPath);
                if (!dbFile.isFile()) {
                    throw new IllegalStateException("Configured CMI database does not exist: " + dbPath);
                }

                HikariDataSource ds = createSQLiteDataSource(dbPath);
                dataSources.add(ds);
                plugin.getLogger().fine("CMI Multi-Server: connected to " + dbPath);
            }

            if (dataSources.size() != dbPaths.size()) {
                throw new IllegalStateException("Not all configured CMI databases were connected");
            }
        } catch (RuntimeException | Error failure) {
            shutdown();
            throw failure;
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

        if (dbPaths.isEmpty() || dataSources.size() != dbPaths.size()) {
            return false;
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
        if (dataSources.size() != dbPaths.size() || dataSources.isEmpty()) {
            throw new IllegalStateException("Cannot read a partial CMI multi-server source set");
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
                        if (playerName == null || playerName.isBlank()) {
                            throw new IllegalStateException("CMI source " + dbPath + " contains a row without a username");
                        }

                        UUID uuid = null;
                        try {
                            Object uuidObj = rs.getObject(UUID_COLUMN);
                            if (uuidObj != null) {
                                uuid = UUID.fromString(uuidObj.toString());
                            }
                        } catch (IllegalArgumentException ignored) {
                        }

                        BigDecimal balance;
                        try {
                            balance = CMIDatabaseReader.parseRequiredBalance(rs.getObject(BALANCE_COLUMN), playerName);
                        } catch (Exception failure) {
                            throw new IllegalStateException("Failed to read CMI balance for "
                                    + playerName + " from " + dbPath, failure);
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
                        } catch (Exception failure) {
                            throw new IllegalStateException("Failed to read CMI last logoff time for "
                                    + playerName + " from " + dbPath, failure);
                        }

                        CMIDatabaseReader.CMIPlayerData playerData =
                                new CMIDatabaseReader.CMIPlayerData(uuid, playerName, balance, lastLogoffTime);

                        playerMap.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                                .add(playerData);
                        count++;
                    }

                    plugin.getLogger().fine("CMI Multi-Server: read " + count + " players from " + dbPath);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to read from CMI database " + dbPath + ": " + e.getMessage());
                throw new IllegalStateException("Failed to read CMI database " + dbPath, e);
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

        plugin.getLogger().fine("CMI Multi-Server: merged to " + merged.size() + " unique players (strategy=" + mergeStrategy.name() + ")");
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

    /** Stable source order, paths, and strategy used to validate a resumed snapshot. */
    public String getSourceIdentity() {
        String paths = dbPaths.stream()
                .map(path -> new File(path).getAbsoluteFile().toPath().normalize().toString())
                .collect(java.util.stream.Collectors.joining("\u001f"));
        return "cmi-multi|" + mergeStrategy.name() + "|" + paths;
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
        plugin.getLogger().fine("CMI Multi-Server: all connections closed");
    }
}
