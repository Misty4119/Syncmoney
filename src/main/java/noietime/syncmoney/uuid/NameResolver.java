package noietime.syncmoney.uuid;

import noietime.syncmoney.storage.CacheManager;
import noietime.syncmoney.storage.db.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Name-to-UUID resolver with caching.
 * Priority: Online players -> Memory cache -> Database -> Optional Mojang API
 *
 * All writes use UUID as key, not name as unique key.
 * Cache has size limit to prevent unbounded memory growth.
 *
 * [AsyncScheduler] Database queries should be executed on async threads.
 */
public final class NameResolver {

    private static final int MAX_CACHE_SIZE = 10_000;

    private final Plugin plugin;
    private final CacheManager cacheManager;
    private final DatabaseManager databaseManager;

    private final ConcurrentMap<String, UUID> nameToUuidCache;
    private final ConcurrentMap<UUID, String> uuidToNameCache;

    public NameResolver(Plugin plugin, CacheManager cacheManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
        this.databaseManager = databaseManager;
        this.nameToUuidCache = new ConcurrentHashMap<>();
        this.uuidToNameCache = new ConcurrentHashMap<>();
    }

    private void evictIfOverCapacity() {
        if (nameToUuidCache.size() >= MAX_CACHE_SIZE || uuidToNameCache.size() >= MAX_CACHE_SIZE) {
            nameToUuidCache.clear();
            uuidToNameCache.clear();
        }
    }

    /**
     * Resolve player name to UUID.
     *
     * @param name Player name
     * @return UUID (if resolved successfully)
     */
    public Optional<UUID> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        String nameLower = name.toLowerCase();

        UUID cached = nameToUuidCache.get(nameLower);
        if (cached != null) {
            return Optional.of(cached);
        }

        var player = Bukkit.getServer().getPlayerExact(name);
        if (player != null) {
            UUID uuid = player.getUniqueId();
            evictIfOverCapacity();
            nameToUuidCache.put(nameLower, uuid);
            return Optional.of(uuid);
        }

        var offlinePlayer = Bukkit.getOfflinePlayerIfCached(name);
        if (offlinePlayer != null) {
            UUID uuid = offlinePlayer.getUniqueId();
            evictIfOverCapacity();
            nameToUuidCache.put(nameLower, uuid);
            return Optional.of(uuid);
        }

        if (databaseManager == null) {
            return Optional.empty();
        }

        var record = databaseManager.getPlayerByName(nameLower);
        if (record.isPresent()) {
            UUID uuid = record.get().uuid();
            evictIfOverCapacity();
            nameToUuidCache.put(nameLower, uuid);
            return Optional.of(uuid);
        }

        return Optional.empty();
    }

    /**
     * Cache player name mapping.
     */
    public void cacheName(String name, UUID uuid) {
        if (name != null && uuid != null) {
            evictIfOverCapacity();
            nameToUuidCache.put(name.toLowerCase(), uuid);
        }
    }

    /**
     * Clear cache.
     */
    public void invalidate(String name) {
        if (name != null) {
            nameToUuidCache.remove(name.toLowerCase());
        }
    }

    /**
     * Get cache size (for monitoring).
     */
    public int getCacheSize() {
        return nameToUuidCache.size();
    }

    /**
     * Resolve player name to UUID (simplified version).
     *
     * @param name Player name
     * @return UUID (if resolved successfully), otherwise returns null
     */
    public UUID resolveUUID(String name) {
        return resolve(name).orElse(null);
    }

    /**
     * Get player name by UUID.
     *
     * @param uuid Player UUID
     * @return Player name, or null if not found
     */
    public String getName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        var player = Bukkit.getServer().getPlayer(uuid);
        if (player != null) {
            String name = player.getName();
            evictIfOverCapacity();
            uuidToNameCache.put(uuid, name.toLowerCase());
            return name;
        }
        String cachedName = uuidToNameCache.get(uuid);
        if (cachedName != null) {
            var offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(cachedName);
            if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                String name = offlinePlayer.getName();
                if (name != null) {
                    return name;
                }
            }
        }

        triggerAsyncNameLookup(uuid);
        return "Unknown-" + uuid.toString().substring(0, 8);
    }

    /**
     * Trigger async name lookup (when cache miss occurs).
     *
     * @param uuid Player UUID
     */
    public void triggerAsyncNameLookup(UUID uuid) {
        if (uuidToNameCache.containsKey(uuid)) {
            return;
        }

        if (databaseManager == null) {
            return;
        }

        if (!plugin.isEnabled()) {
            return;
        }

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                var record = databaseManager.getPlayer(uuid);
                if (record.isPresent()) {
                    String name = record.get().name();
                    if (name != null) {
                        evictIfOverCapacity();
                        uuidToNameCache.put(uuid, name.toLowerCase());
                        plugin.getLogger().fine("Async name lookup completed for " + uuid + ": " + name);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Async name lookup failed for " + uuid + ": " + e.getMessage());
            }
        });
    }

    /**
     * [SYNC-UUID-002] Get name from cache only (non-blocking).
     *
     * @param uuid Player UUID
     * @return Player name, or null if not found
     */
    public String getNameCachedOnly(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        String cached = uuidToNameCache.get(uuid);
        if (cached != null) {
            var player = Bukkit.getServer().getPlayer(uuid);
            if (player != null) {
                return player.getName();
            }
            return cached.substring(0, 1).toUpperCase() + cached.substring(1);
        }

        var player = Bukkit.getServer().getPlayer(uuid);
        if (player != null) {
            String name = player.getName();
            evictIfOverCapacity();
            uuidToNameCache.put(uuid, name.toLowerCase());
            return name;
        }

        var offlinePlayer = Bukkit.getServer().getOfflinePlayer(uuid);
        if (offlinePlayer != null) {
            String name = offlinePlayer.getName();
            if (name != null) {
                evictIfOverCapacity();
                uuidToNameCache.put(uuid, name.toLowerCase());
                return name;
            }
        }

        return null;
    }

    /**
     * [SYNC-UUID-003] Get all cached player names (for tab completion).
     */
    public java.util.Collection<String> getAllCachedNames() {
        return nameToUuidCache.keySet();
    }
}
