package noietime.syncmoney.permission;

import noietime.syncmoney.config.SyncmoneyConfig;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Admin permission service.
 * Implements permission levels and daily limit checking.
 *
 * [ThreadSafe] This class is thread-safe for concurrent operations.
 */
public final class AdminPermissionService {

    private final SyncmoneyConfig config;
    private final Map<UUID, DailyLimit> dailyLimits;

    public AdminPermissionService(SyncmoneyConfig config) {
        this.config = config;
        this.dailyLimits = new ConcurrentHashMap<>();
    }

    /**
     * Permission level enum.
     */
    public enum PermissionLevel {
        OBSERVE(1, "syncmoney.admin.observe"),
        REWARD(2, "syncmoney.admin.reward"),
        GENERAL(3, "syncmoney.admin.general"),
        FULL(4, "syncmoney.admin.full");

        private final int level;
        private final String permission;

        PermissionLevel(int level, String permission) {
            this.level = level;
            this.permission = permission;
        }

        public int getLevel() {
            return level;
        }

        public String getPermission() {
            return permission;
        }
    }

    /**
     * Get admin permission level.
     */
    public PermissionLevel getPermissionLevel(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return PermissionLevel.FULL;
        }

        if (sender.hasPermission(PermissionLevel.FULL.getPermission())) {
            return PermissionLevel.FULL;
        }
        if (sender.hasPermission(PermissionLevel.GENERAL.getPermission())) {
            return PermissionLevel.GENERAL;
        }
        if (sender.hasPermission(PermissionLevel.REWARD.getPermission())) {
            return PermissionLevel.REWARD;
        }
        if (sender.hasPermission(PermissionLevel.OBSERVE.getPermission())) {
            return PermissionLevel.OBSERVE;
        }

        return null;
    }

    /**
     * Check if sender has permission to execute command.
     */
    public boolean canExecute(CommandSender sender, String command) {
        PermissionLevel level = getPermissionLevel(sender);
        if (level == null) {
            return false;
        }

        return switch (command.toLowerCase()) {
            case "money", "baltop" -> level.getLevel() >= PermissionLevel.OBSERVE.getLevel();
            case "give" -> level.getLevel() >= PermissionLevel.REWARD.getLevel();
            case "take" -> level.getLevel() >= PermissionLevel.GENERAL.getLevel();
            case "set", "audit", "breaker", "migrate", "shadow" ->
                level.getLevel() >= PermissionLevel.FULL.getLevel();
            default -> true;
        };
    }

    /**
     * Check daily limit.
     */
    public boolean checkDailyLimit(CommandSender sender, String action, double amount) {
        if (!config.isAdminDailyLimitEnabled()) {
            return true;
        }

        PermissionLevel level = getPermissionLevel(sender);

        if (level == null || level == PermissionLevel.FULL) {
            return true;
        }

        if (!(sender instanceof Player)) {
            return true;
        }

        UUID adminUuid = ((Player) sender).getUniqueId();
        DailyLimit limit = dailyLimits.computeIfAbsent(adminUuid, k -> new DailyLimit());

        if (!limit.isToday()) {
            limit.reset();
        }

        double maxDaily = switch (action.toLowerCase()) {
            case "give" -> switch (level) {
                case REWARD -> config.getAdminRewardDailyGiveLimit();
                case GENERAL -> config.getAdminGeneralDailyGiveLimit();
                default -> 0;
            };
            case "take" -> switch (level) {
                case GENERAL -> config.getAdminGeneralDailyTakeLimit();
                default -> 0;
            };
            default -> 0;
        };

        if (maxDaily <= 0) {
            return true;
        }

        double used = action.equalsIgnoreCase("give") ? limit.getGivenToday() : limit.getTakenToday();
        return (used + amount) <= maxDaily;
    }

    /**
     * Record daily usage.
     */
    public void recordUsage(CommandSender sender, String action, double amount) {
        if (!config.isAdminDailyLimitEnabled()) {
            return;
        }

        if (!(sender instanceof Player)) {
            return;
        }

        PermissionLevel level = getPermissionLevel(sender);
        if (level == null || level == PermissionLevel.FULL) {
            return;
        }

        UUID adminUuid = ((Player) sender).getUniqueId();
        DailyLimit limit = dailyLimits.get(adminUuid);
        if (limit == null) {
            return;
        }

        if (!limit.isToday()) {
            limit.reset();
        }

        if (action.equalsIgnoreCase("give")) {
            limit.addGiven(amount);
        } else if (action.equalsIgnoreCase("take")) {
            limit.addTaken(amount);
        }
    }

    /**
     * Get remaining limit.
     */
    public double getRemainingLimit(CommandSender sender, String action) {
        if (!(sender instanceof Player)) {
            return Double.MAX_VALUE;
        }

        PermissionLevel level = getPermissionLevel(sender);
        if (level == null || level == PermissionLevel.FULL) {
            return Double.MAX_VALUE;
        }

        UUID adminUuid = ((Player) sender).getUniqueId();
        DailyLimit limit = dailyLimits.get(adminUuid);
        if (limit == null || !limit.isToday()) {
            return switch (action.toLowerCase()) {
                case "give" -> switch (level) {
                    case REWARD -> config.getAdminRewardDailyGiveLimit();
                    case GENERAL -> config.getAdminGeneralDailyGiveLimit();
                    default -> 0;
                };
                case "take" -> switch (level) {
                    case GENERAL -> config.getAdminGeneralDailyTakeLimit();
                    default -> 0;
                };
                default -> 0;
            };
        }

        double maxDaily = switch (action.toLowerCase()) {
            case "give" -> switch (level) {
                case REWARD -> config.getAdminRewardDailyGiveLimit();
                case GENERAL -> config.getAdminGeneralDailyGiveLimit();
                default -> 0;
            };
            case "take" -> switch (level) {
                case GENERAL -> config.getAdminGeneralDailyTakeLimit();
                default -> 0;
            };
            default -> 0;
        };

        double used = action.equalsIgnoreCase("give") ? limit.getGivenToday() : limit.getTakenToday();
        return Math.max(0, maxDaily - used);
    }

    /**
     * [SYNC-PERM-001] Daily limit record (using AtomicReference<BigDecimal> for thread-safe precise arithmetic).
     * [L-3 FIX] Replaced AtomicLong with BigDecimal to eliminate floating-point precision errors.
     */
    private static class DailyLimit {

        private volatile LocalDate date;
        private final AtomicReference<BigDecimal> givenToday = new AtomicReference<>(BigDecimal.ZERO);
        private final AtomicReference<BigDecimal> takenToday = new AtomicReference<>(BigDecimal.ZERO);

        DailyLimit() {
            this.date = LocalDate.now();
        }

        boolean isToday() {
            return date.equals(LocalDate.now());
        }

        void reset() {
            this.date = LocalDate.now();
            givenToday.set(BigDecimal.ZERO);
            takenToday.set(BigDecimal.ZERO);
        }

        double getGivenToday() {
            return givenToday.get().doubleValue();
        }

        void addGiven(double amount) {
            givenToday.updateAndGet(v -> v.add(BigDecimal.valueOf(amount)));
        }

        double getTakenToday() {
            return takenToday.get().doubleValue();
        }

        void addTaken(double amount) {
            takenToday.updateAndGet(v -> v.add(BigDecimal.valueOf(amount)));
        }
    }
}
