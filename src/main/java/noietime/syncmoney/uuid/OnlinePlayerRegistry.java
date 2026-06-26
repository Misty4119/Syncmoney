package noietime.syncmoney.uuid;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import noietime.syncmoney.config.SyncmoneyConfig;
import noietime.syncmoney.storage.RedisManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class OnlinePlayerRegistry {

    private static final String ONLINE_SET_KEY = "syncmoney:online:players";
    private static final String PLAYER_HASH_PREFIX = "syncmoney:online:player:";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_SERVER = "server";

    private static final long TTL_SECONDS = 90L;
    private static final long HEARTBEAT_PERIOD_SECONDS = 60L;
    private static final long HEARTBEAT_INITIAL_DELAY_SECONDS = 5L;

    private static final String NPC_METADATA = "NPC";

    private final Plugin plugin;
    private final SyncmoneyConfig config;
    private final RedisManager redisManager;

    private volatile Set<String> crossServerNameCache = Set.of();

    private volatile ScheduledTask heartbeatTask;

    public OnlinePlayerRegistry(Plugin plugin, SyncmoneyConfig config, RedisManager redisManager) {
        this.plugin = plugin;
        this.config = config;
        this.redisManager = redisManager;
    }

    private boolean isRedisAvailable() {
        return redisManager != null && !redisManager.isDegraded();
    }

    public void register(Player player) {
        if (player == null) {
            return;
        }
        if (player.hasMetadata(NPC_METADATA)) {
            return;
        }
        if (!isRedisAvailable()) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        final String server = config.getServerName();

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> writeRegister(uuid, name, server));
    }

    private void writeRegister(UUID uuid, String name, String server) {
        if (!isRedisAvailable()) {
            return;
        }
        String uuidStr = uuid.toString();
        String hashKey = PLAYER_HASH_PREFIX + uuidStr;
        try (var jedis = redisManager.getResource()) {
            jedis.sadd(ONLINE_SET_KEY, uuidStr);
            Map<String, String> data = new HashMap<>();
            data.put(FIELD_NAME, name);
            data.put(FIELD_SERVER, server);
            jedis.hset(hashKey, data);
            jedis.expire(hashKey, TTL_SECONDS);
        } catch (Exception e) {
            plugin.getLogger().fine("OnlinePlayerRegistry register failed for " + name + ": " + e.getMessage());
        }
    }

    public void unregister(UUID uuid) {
        if (uuid == null) {
            return;
        }
        if (!isRedisAvailable()) {
            return;
        }
        final String uuidStr = uuid.toString();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            if (!isRedisAvailable()) {
                return;
            }
            try (var jedis = redisManager.getResource()) {
                jedis.srem(ONLINE_SET_KEY, uuidStr);
                jedis.del(PLAYER_HASH_PREFIX + uuidStr);
            } catch (Exception e) {
                plugin.getLogger().fine("OnlinePlayerRegistry unregister failed for " + uuidStr + ": " + e.getMessage());
            }
        });
    }

    public void startHeartbeat() {
        if (heartbeatTask != null) {
            return;
        }
        this.heartbeatTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!isRedisAvailable()) {
                return;
            }
            plugin.getServer().getGlobalRegionScheduler().run(plugin, regionTask -> {
                Map<UUID, String> snapshot = new HashMap<>();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.hasMetadata(NPC_METADATA)) {
                        continue;
                    }
                    snapshot.put(p.getUniqueId(), p.getName());
                }
                plugin.getServer().getAsyncScheduler().runNow(plugin, t -> {
                    heartbeatWrite(snapshot);
                    refreshCrossServerCache();
                });
            });
        }, HEARTBEAT_INITIAL_DELAY_SECONDS, HEARTBEAT_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private void heartbeatWrite(Map<UUID, String> snapshot) {
        if (snapshot.isEmpty() || !isRedisAvailable()) {
            return;
        }
        String server = config.getServerName();
        try (var jedis = redisManager.getResource()) {
            for (Map.Entry<UUID, String> entry : snapshot.entrySet()) {
                String uuidStr = entry.getKey().toString();
                String hashKey = PLAYER_HASH_PREFIX + uuidStr;
                jedis.sadd(ONLINE_SET_KEY, uuidStr);
                Map<String, String> data = new HashMap<>();
                data.put(FIELD_NAME, entry.getValue());
                data.put(FIELD_SERVER, server);
                jedis.hset(hashKey, data);
                jedis.expire(hashKey, TTL_SECONDS);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("OnlinePlayerRegistry heartbeat failed: " + e.getMessage());
        }
    }

    public Set<String> getCrossServerOnlineNames() {
        Set<String> result = new HashSet<>();
        if (!isRedisAvailable()) {
            return result;
        }
        try (var jedis = redisManager.getResource()) {
            Set<String> uuids = jedis.smembers(ONLINE_SET_KEY);
            if (uuids == null || uuids.isEmpty()) {
                return result;
            }
            List<String> stale = new ArrayList<>();
            for (String uuidStr : uuids) {
                String name = jedis.hget(PLAYER_HASH_PREFIX + uuidStr, FIELD_NAME);
                if (name == null || name.isBlank()) {
                    stale.add(uuidStr);
                    continue;
                }
                result.add(name);
            }
            if (!stale.isEmpty()) {
                jedis.srem(ONLINE_SET_KEY, stale.toArray(new String[0]));
            }
        } catch (Exception e) {
            plugin.getLogger().fine("OnlinePlayerRegistry getCrossServerOnlineNames failed: " + e.getMessage());
        }
        return result;
    }

    private void refreshCrossServerCache() {
        if (!isRedisAvailable()) {
            this.crossServerNameCache = Set.of();
            return;
        }
        this.crossServerNameCache = Set.copyOf(getCrossServerOnlineNames());
    }

    public List<String> suggestOnlinePlayerNames(String prefix, boolean preferCrossServer) {
        Set<String> names = new HashSet<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasMetadata(NPC_METADATA)) {
                continue;
            }
            names.add(p.getName());
        }
        if (preferCrossServer && isRedisAvailable()) {
            names.addAll(crossServerNameCache);
        }
        String lower = prefix == null ? "" : prefix.toLowerCase();
        return names.stream()
                .filter(n -> n.toLowerCase().startsWith(lower))
                .distinct()
                .sorted()
                .toList();
    }

    public void shutdown() {
        if (heartbeatTask != null) {
            try {
                heartbeatTask.cancel();
            } catch (Exception ignored) {
            }
            heartbeatTask = null;
        }
    }
}
