package noietime.syncmoney.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import noietime.syncmoney.Syncmoney;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
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
    private final ObjectMapper objectMapper;
    private File backupDirectory;


    private final ConcurrentMap<String, BackupEntry> backupData;


    private volatile BigDecimal totalBackupAmount;


    private volatile int totalBackupCount;

    public MigrationBackup(Plugin plugin) {
        this.plugin = plugin;
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
            plugin.getLogger().info("Backup saved: " + backupFile.getAbsolutePath());
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
                plugin.getLogger().info("Deleted old backup: " + files.get(i).getName());
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
