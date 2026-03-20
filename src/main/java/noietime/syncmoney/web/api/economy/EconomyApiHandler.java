package noietime.syncmoney.web.api.economy;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.baltop.BaltopManager;
import noietime.syncmoney.baltop.RankEntry;
import noietime.syncmoney.economy.EconomyFacade;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.HttpHandlerRegistry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API handler for economy statistics endpoints.
 * Provides access to total supply, player counts, and transaction statistics.
 */
public class EconomyApiHandler {

    /** Path index for route: api/economy/player/{uuid}/balance */
    private static final int PATH_IDX_PLAYER_UUID = 3;

    private final Syncmoney plugin;
    private final EconomyFacade economyFacade;
    private final BaltopManager baltopManager;
    private final AuditLogger auditLogger;
    private final LocalEconomyHandler localEconomyHandler;

    public EconomyApiHandler(Syncmoney plugin, EconomyFacade economyFacade,
            BaltopManager baltopManager, AuditLogger auditLogger, LocalEconomyHandler localEconomyHandler) {
        this.plugin = plugin;
        this.economyFacade = economyFacade;
        this.baltopManager = baltopManager;
        this.auditLogger = auditLogger;
        this.localEconomyHandler = localEconomyHandler;
    }

    /**
     * Register all economy API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.get("api/economy/stats", exchange -> {
            handleGetStats(exchange);
        });

        router.get("api/economy/player/{uuid}/balance", exchange -> {
            handleGetPlayerBalance(exchange);
        });

        router.get("api/economy/top", exchange -> {
            handleGetTopPlayers(exchange);
        });
    }

    /**
     * Handle get player balance request.
     * Route: api/economy/player/{uuid}/balance
     */
    private void handleGetPlayerBalance(HttpServerExchange exchange) {
        String uuidParam = extractPathParamAt(exchange, PATH_IDX_PLAYER_UUID);

        if (uuidParam == null || uuidParam.isBlank()) {
            exchange.setStatusCode(400);
            sendJson(exchange, ApiResponse.error("PLAYER_UUID_REQUIRED", "Player UUID is required"));
            return;
        }

        try {
            java.util.UUID uuid = java.util.UUID.fromString(uuidParam);

            if (economyFacade != null) {
                BigDecimal balance = economyFacade.getBalance(uuid);
                Map<String, Object> data = new HashMap<>();
                data.put("uuid", uuid.toString());
                data.put("balance", balance);
                data.put("currencyName", plugin.getSyncmoneyConfig().getCurrencyName());
                sendJson(exchange, ApiResponse.success(data));
            } else {
                exchange.setStatusCode(503);
                sendJson(exchange, ApiResponse.error("ECONOMY_NOT_AVAILABLE", "Economy system not available"));
            }
        } catch (IllegalArgumentException e) {
            exchange.setStatusCode(400);
            sendJson(exchange, ApiResponse.error("INVALID_UUID", "Invalid player UUID format"));
        }
    }

    /**
     * Handle get top players request.
     */
    private void handleGetTopPlayers(HttpServerExchange exchange) {
        if (baltopManager != null) {
            try {
                List<Map<String, Object>> topPlayers = new ArrayList<>();
                List<RankEntry> topRanks = baltopManager.getTopRank(10);
                for (RankEntry rank : topRanks) {
                    Map<String, Object> player = new HashMap<>();
                    player.put("rank", rank.rank());
                    player.put("uuid", rank.uuid().toString());
                    player.put("balance", rank.balance());
                    topPlayers.add(player);
                }
                Map<String, Object> data = new HashMap<>();
                data.put("topPlayers", topPlayers);
                data.put("currencyName", plugin.getSyncmoneyConfig().getCurrencyName());
                sendJson(exchange, ApiResponse.success(data));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get top players: " + e.getMessage());
                exchange.setStatusCode(500);
                sendJson(exchange, ApiResponse.error("TOP_PLAYERS_ERROR", "Failed to retrieve top players"));
            }
        } else {
            exchange.setStatusCode(503);
            sendJson(exchange, ApiResponse.error("BALTOP_NOT_AVAILABLE", "Leaderboard not available"));
        }
    }

    /**
     * Extract path parameter at a fixed position index.
     * e.g. api/economy/player/{uuid}/balance → index 3 gives the uuid segment
     */
    private String extractPathParamAt(HttpServerExchange exchange, int index) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        return (parts.length > index) ? parts[index] : null;
    }

    /**
     * Handle get economy statistics request.
     */
    private void handleGetStats(HttpServerExchange exchange) {
        Map<String, Object> stats = new HashMap<>();

        if (baltopManager != null) {
            double totalSupply = baltopManager.getTotalSupply();
            stats.put("totalSupply", totalSupply);
        } else {
            stats.put("totalSupply", 0.0);
        }

        if (baltopManager != null) {
            int totalPlayers = baltopManager.getTotalPlayers();
            stats.put("totalPlayers", totalPlayers);
        } else {
            stats.put("totalPlayers", 0);
        }

        if (auditLogger != null) {
            int todayTransactions = getTodayTransactionCount();
            stats.put("todayTransactions", todayTransactions);
        } else {
            stats.put("todayTransactions", 0);
        }

        if (economyFacade != null) {
            int cachedPlayers = economyFacade.getCachedPlayerCount();
            stats.put("cachedPlayers", cachedPlayers);
        } else {
            stats.put("cachedPlayers", 0);
        }

        stats.put("currencyName", plugin.getSyncmoneyConfig().getCurrencyName());

        sendJson(exchange, ApiResponse.success(stats));
    }

    /**
     * Get today's transaction count from audit log or local economy handler.
     * Uses SELECT COUNT(*) — no LIMIT — so the result is always accurate.
     */
    private int getTodayTransactionCount() {

        if (auditLogger != null && auditLogger.isEnabled()) {
            try {
                long todayStart = getTodayStartTimestamp();
                return auditLogger.countByTimeRange(todayStart, System.currentTimeMillis());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get today's transaction count from audit log: " + e.getMessage());
            }
        }


        if (localEconomyHandler != null) {
            try {
                return localEconomyHandler.getTodayTransactionCount();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get today's transaction count from local handler: " + e.getMessage());
            }
        }

        return 0;
    }

    /**
     * Get the start timestamp of today (00:00:00.000) in the configured web-admin timezone.
     * Falls back to UTC if the timezone string is invalid.
     */
    private long getTodayStartTimestamp() {
        ZoneId zone = ZoneId.of("UTC");
        try {
            var webAdminConfig = plugin.getWebAdminConfig();
            if (webAdminConfig != null) {
                String tzString = webAdminConfig.getTimezone();
                if (tzString != null && !tzString.isBlank()) {
                    zone = ZoneId.of(tzString);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Invalid timezone in web-admin config, using UTC: " + e.getMessage());
        }

        return LocalDate.now(zone)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
    }

    /**
     * Send JSON response.
     */
    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8");
        exchange.getResponseSender().send(json);
    }
}
