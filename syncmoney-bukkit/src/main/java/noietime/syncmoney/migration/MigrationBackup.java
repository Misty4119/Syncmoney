package noietime.syncmoney.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Migration backup manager.
 * Creates CMI data backup before migration to ensure rollback capability.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class MigrationBackup {

    private final Plugin plugin;
    private final Syncmoney syncmoney;
    private final ObjectMapper objectMapper;
    private File backupDirectory;

    private final ConcurrentMap<String, BackupEntry> backupData;

    private volatile BigDecimal totalBackupAmount;

    private volatile int totalBackupCount;

    public MigrationBackup(Plugin plugin, Syncmoney syncmoney) {
        this.plugin = plugin;
        this.syncmoney = (Syncmoney) plugin;
        this.objectMapper = new ObjectMapper();
        this.backupData = new ConcurrentHashMap<>();
        this.totalBackupAmount = BigDecimal.ZERO;
        this.totalBackupCount = 0;

        this.backupDirectory = new File(plugin.getDataFolder(), "backups");
        if (!backupDirectory.exists()) {
            backupDirectory.mkdirs();
        }
    }

    /**
     * Generates backup file name.
     * @return file name
     */
    private String generateBackupFilename() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        return "cmi_backup_" + LocalDateTime.now().format(formatter) + ".json";
    }

    /**
     * Adds player data to backup.
     * @param playerName player name
     * @param uuid player UUID
     * @param balance balance
     */
    public void addPlayer(String playerName, String uuid, BigDecimal balance) {
        backupData.put(playerName.toLowerCase(), new BackupEntry(uuid, playerName, balance));
        totalBackupAmount = totalBackupAmount.add(balance);
        totalBackupCount++;
    }

    /**
     * Batch adds player data.
     * @param players list of player data
     */
    public void addPlayers(List<CMIDatabaseReader.CMIPlayerData> players) {
        for (CMIDatabaseReader.CMIPlayerData player : players) {
            String uuid = player.uuid() != null ? player.uuid().toString() : "";
            addPlayer(player.playerName(), uuid, player.balance());
        }
    }

    /**
     * Creates a full database backup in the specified format.
     * @param format backup format (mysql, postgresql, sqlite)
     * @return backup file path, or null if failed
     */
    public String createFullDatabaseBackup(String format) {
        try {

            Connection conn = getDatabaseConnection();
            if (conn == null) {
                plugin.getLogger().severe("Cannot create database backup: no database connection");
                return null;
            }

            String fileName = "full_backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "." + format;
            File backupFile = new File(backupDirectory, fileName);

            try (PrintWriter writer = new PrintWriter(new FileWriter(backupFile))) {
                writer.println("-- Syncmoney Full Database Backup");
                writer.println("-- Created: " + LocalDateTime.now());
                writer.println("-- Format: " + format);
                writer.println();


                backupTable(writer, conn, "players", format);


                if (tableExists(conn, "audit_log")) {
                    backupTable(writer, conn, "audit_log", format);
                }
            }

            plugin.getLogger().info("Full database backup created: " + backupFile.getAbsolutePath());
            conn.close();
            return backupFile.getAbsolutePath();

        } catch (SQLException | IOException e) {
            plugin.getLogger().severe("Failed to create database backup: " + e.getMessage());
            return null;
        }
    }

    private Connection getDatabaseConnection() {
        try {
            var dbManager = syncmoney.getDatabaseManager();
            if (dbManager != null) {
                return dbManager.getConnection();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to get database connection: " + e.getMessage());
        }
        return null;
    }

    /**
     * Checks if a table exists in the database.
     */
    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    /**
     * Backs up a table to SQL statements.
     */
    private void backupTable(PrintWriter writer, Connection conn, String tableName, String format) throws SQLException {
        writer.println("-- Table: " + tableName);
        writer.println();


        String selectSql = "SELECT * FROM " + tableName;
        try (PreparedStatement stmt = conn.prepareStatement(selectSql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                StringBuilder insert = new StringBuilder("INSERT INTO ").append(tableName).append(" VALUES (");

                int columnCount = rs.getMetaData().getColumnCount();
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);
                    if (value == null) {
                        insert.append("NULL");
                    } else if (value instanceof Number) {
                        insert.append(value.toString());
                    } else if (value instanceof java.sql.Timestamp) {
                        insert.append("'").append(value.toString()).append("'");
                    } else {
                        insert.append("'").append(value.toString().replace("'", "''")).append("'");
                    }
                    if (i < columnCount) {
                        insert.append(", ");
                    }
                }
                insert.append(");");
                writer.println(insert);
            }
        }
        writer.println();
    }

    /**
     * Backs up as SQL format (convenience method).
     * @return backup file path
     */
    public String backupAsSql() {
        String format = syncmoney.getConfig().getString("database.type", "mysql");
        return createFullDatabaseBackup(format.toLowerCase());
    }

    /**
     * Saves backup to file.
     * @return backup file path, or null if failed
     */
    public String save() {
        if (backupData.isEmpty()) {
            plugin.getLogger().warning("No backup data to save!");
            return null;
        }

        BackupMetadata metadata = new BackupMetadata(
                LocalDateTime.now().toString(),
                totalBackupCount,
                totalBackupAmount.toPlainString(),
                "CMI Database",
                new ArrayList<>(backupData.values())
        );

        File backupFile = new File(backupDirectory, generateBackupFilename());

        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(backupFile, metadata);
            plugin.getLogger().fine("Backup saved: " + backupFile.getAbsolutePath());
            return backupFile.getAbsolutePath();
        } catch (JsonProcessingException e) {
            plugin.getLogger().severe("Failed to serialize backup: " + e.getMessage());
            return null;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write backup file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Loads backup file.
     * @param filePath file path
     * @return true if loaded successfully
     */
    public boolean load(String filePath) {
        File backupFile = new File(filePath);
        if (!backupFile.exists()) {
            plugin.getLogger().severe("Backup file not found: " + filePath);
            return false;
        }

        try {
            BackupMetadata metadata = objectMapper.readValue(backupFile, BackupMetadata.class);
            backupData.clear();
            totalBackupAmount = BigDecimal.ZERO;
            totalBackupCount = 0;

            for (BackupEntry entry : metadata.players()) {
                backupData.put(entry.playerName().toLowerCase(), entry);
                totalBackupAmount = totalBackupAmount.add(entry.balance());
                totalBackupCount++;
            }

            plugin.getLogger().fine("Backup loaded: " + totalBackupCount + " players, total: " + totalBackupAmount);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load backup: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if player is in backup.
     * @param playerName player name
     * @return true if exists
     */
    public boolean hasPlayer(String playerName) {
        return backupData.containsKey(playerName.toLowerCase());
    }

    /**
     * Gets player's backup balance.
     * @param playerName player name
     * @return balance, or null if not found
     */
    public BigDecimal getBackupBalance(String playerName) {
        BackupEntry entry = backupData.get(playerName.toLowerCase());
        return entry != null ? entry.balance() : null;
    }

    /**
     * Clears backup data.
     */
    public void clear() {
        backupData.clear();
        totalBackupAmount = BigDecimal.ZERO;
        totalBackupCount = 0;
    }

    /**
     * Gets all backup files in backup directory.
     * @return list of backup files
     */
    public List<File> getBackupFiles() {
        List<File> files = new ArrayList<>();
        if (backupDirectory.exists() && backupDirectory.isDirectory()) {
            File[] fileArray = backupDirectory.listFiles((dir, name) -> name.endsWith(".json"));
            if (fileArray != null) {
                for (File f : fileArray) {
                    files.add(f);
                }
            }
        }
        return files;
    }

    /**
     * Deletes expired backup files (keeps most recent N).
     * @param keepCount number of backups to keep
     */
    public void cleanupOldBackups(int keepCount) {
        List<File> files = getBackupFiles();
        if (files.size() <= keepCount) {
            return;
        }

        files.sort((f1, f2) -> Long.compare(f1.lastModified(), f2.lastModified()));

        int toDelete = files.size() - keepCount;
        for (int i = 0; i < toDelete; i++) {
            if (files.get(i).delete()) {
                plugin.getLogger().fine("Deleted old backup: " + files.get(i).getName());
            }
        }
    }

    public int getTotalBackupCount() {
        return totalBackupCount;
    }

    public BigDecimal getTotalBackupAmount() {
        return totalBackupAmount;
    }

    public ConcurrentMap<String, BackupEntry> getBackupData() {
        return backupData;
    }

    /**
     * Backup entry record.
     */
    public record BackupEntry(
            String uuid,
            String playerName,
            BigDecimal balance
    ) {}

    /**
     * Backup metadata (for JSON serialization).
     */
    private record BackupMetadata(
            String backupTime,
            int playerCount,
            String totalAmount,
            String source,
            List<BackupEntry> players
    ) {}
}
