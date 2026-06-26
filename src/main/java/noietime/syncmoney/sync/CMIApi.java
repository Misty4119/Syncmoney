package noietime.syncmoney.sync;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class CMIApi {

    private final Logger logger;
    private final boolean available;

    private Method cmiGetInstance;
    private Method getPlayerManager;
    private Method getUserByUuid;
    private Method getUserByOffline;
    private Method userGetBalance;
    private Method userGetEconomyAccount;
    private Method userAddForDelayedSave;
    private Method accountGetBalance;
    private Method accountGetBalances;
    private Method accountGetCurrentWorldGroup;

    private volatile Object cachedCmiInstance;
    private volatile Object cachedPlayerManager;

    public CMIApi(Logger logger) {
        this.logger = logger;
        this.available = initReflection();
    }

    private boolean initReflection() {
        try {
            Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
            Class<?> pmClass = Class.forName("com.Zrips.CMI.PlayerManager");
            Class<?> userClass = Class.forName("com.Zrips.CMI.Containers.CMIUser");
            Class<?> accountClass = Class.forName("com.Zrips.CMI.Modules.Economy.CMIEconomyAcount");

            cmiGetInstance = cmiClass.getMethod("getInstance");
            getPlayerManager = cmiClass.getMethod("getPlayerManager");
            getUserByUuid = pmClass.getMethod("getUser", UUID.class);
            getUserByOffline = pmClass.getMethod("getUser", OfflinePlayer.class, boolean.class);
            userGetBalance = userClass.getMethod("getBalance");
            userGetEconomyAccount = userClass.getMethod("getEconomyAccount");
            userAddForDelayedSave = userClass.getMethod("addForDelayedSave");
            accountGetBalance = accountClass.getMethod("getBalance");
            accountGetBalances = accountClass.getMethod("getBalances");
            accountGetCurrentWorldGroup = accountClass.getMethod("getCurrentWorldGroup");
            return true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.fine("CMI API not available (reflection init failed): " + t.getMessage());
            }
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }


    private Object resolvePlayerManager() throws Exception {
        Object pm = cachedPlayerManager;
        if (pm != null) {
            return pm;
        }
        Object instance = cachedCmiInstance;
        if (instance == null) {
            instance = cmiGetInstance.invoke(null);
            cachedCmiInstance = instance;
        }
        pm = getPlayerManager.invoke(instance);
        cachedPlayerManager = pm;
        return pm;
    }


    public Double getBalance(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }
        try {
            Object user = resolveUser(uuid, false);
            if (user == null) {
                user = resolveUser(uuid, true);
            }
            if (user == null) {
                return null;
            }
            return ((Number) userGetBalance.invoke(user)).doubleValue();
        } catch (Throwable t) {
            if (logger != null) {
                logger.fine("CMI getBalance failed for " + uuid + ": " + t.getMessage());
            }
            return null;
        }
    }

    private Object resolveUser(UUID uuid, boolean createIfAbsent) throws Exception {
        Object pm = resolvePlayerManager();
        Object user = getUserByUuid.invoke(pm, uuid);
        if (user != null || !createIfAbsent) {
            return user;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return getUserByOffline.invoke(pm, offline, true);
    }

    @SuppressWarnings("unchecked")
    public boolean setBalance(UUID uuid, double balance) {
        if (!available || uuid == null) {
            return false;
        }
        try {
            Object user = resolveUser(uuid, false);
            if (user == null) {
                user = resolveUser(uuid, true);
            }
            if (user == null) {
                if (logger != null) {
                    logger.fine("[CMI setBalance] user not resolvable for " + uuid
                            + " (not loaded by CMI on this server)");
                }
                return false;
            }
            Object account = userGetEconomyAccount.invoke(user);
            if (account == null) {
                if (logger != null) {
                    logger.warning("[CMI setBalance] economy account null for " + uuid);
                }
                return false;
            }
            double current = ((Number) accountGetBalance.invoke(account)).doubleValue();
            if (Double.compare(current, balance) == 0) {
                try {
                    userAddForDelayedSave.invoke(user);
                } catch (Throwable ignored) {
                }
                if (logger != null) {
                    logger.fine("[CMI setBalance] " + uuid + " already at " + balance + " (no-op)");
                }
                return false;
            }
            Map<Object, Object> balances = (Map<Object, Object>) accountGetBalances.invoke(account);
            if (balances == null) {
                if (logger != null) {
                    logger.warning("[CMI setBalance] balances map null for " + uuid);
                }
                return false;
            }
            Object worldGroup = null;
            try {
                worldGroup = accountGetCurrentWorldGroup.invoke(account);
            } catch (Throwable ignored) {
            }
            if (worldGroup != null) {
                balances.put(worldGroup, balance);
            } else {
                if (balances.isEmpty()) {

                    balances.put("default", balance);
                } else {
                    for (Object key : balances.keySet()) {
                        balances.put(key, balance);
                    }
                }
            }
            userAddForDelayedSave.invoke(user);
            if (logger != null) {
                logger.fine("[CMI setBalance] " + uuid + ": " + current + " -> " + balance
                        + " (worldGroup=" + (worldGroup != null ? worldGroup : "ALL") + ")");
            }
            return true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.warning("[CMI setBalance] failed for " + uuid + ": " + t.getMessage());
            }
            return false;
        }
    }
}
