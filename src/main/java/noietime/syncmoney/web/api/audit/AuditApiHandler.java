package noietime.syncmoney.web.api.audit;

import io.undertow.server.HttpServerExchange;
import noietime.syncmoney.Syncmoney;
import noietime.syncmoney.audit.AuditLogger;
import noietime.syncmoney.audit.AuditRecord;
import noietime.syncmoney.audit.HybridAuditManager;
import noietime.syncmoney.economy.EconomyEvent;
import noietime.syncmoney.economy.LocalEconomyHandler;
import noietime.syncmoney.uuid.NameResolver;
import noietime.syncmoney.web.api.ApiResponse;
import noietime.syncmoney.web.server.HttpHandlerRegistry;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API handler for audit log endpoints.
 * Integrates with existing AuditLogger system and HybridAuditManager.
 */
public class AuditApiHandler {

    /** Path index for route: api/audit/player/{name} */
    private static final int PATH_IDX_PLAYER_NAME = 3;

    private final Syncmoney plugin;
    private final AuditLogger auditLogger;
    private final HybridAuditManager hybridAuditManager;
    private NameResolver nameResolver;
    private LocalEconomyHandler localEconomyHandler;

    public AuditApiHandler(Syncmoney plugin, AuditLogger auditLogger, HybridAuditManager hybridAuditManager) {
        this.plugin = plugin;
        this.auditLogger = auditLogger;
        this.hybridAuditManager = hybridAuditManager;
    }

    /**
     * Set optional NameResolver for player name → UUID resolution.
     */
    public void setNameResolver(NameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * Set LocalEconomyHandler for LOCAL mode fallback.
     */
    public void setLocalEconomyHandler(LocalEconomyHandler localEconomyHandler) {
        this.localEconomyHandler = localEconomyHandler;
    }

    /**
     * Register all audit API routes.
     */
    public void registerRoutes(HttpHandlerRegistry router) {
        router.get("api/audit/player/{name}", exchange -> {
            String playerName = extractPathParamAt(exchange, PATH_IDX_PLAYER_NAME);
            int page = getQueryParamAsInt(exchange, "page", 1);
            int pageSize = getQueryParamAsInt(exchange, "pageSize", 20);

            pageSize = Math.min(Math.max(pageSize, 1), 100);

            handleGetPlayerAudit(exchange, playerName, page, pageSize);
        });

        router.get("api/audit/search", exchange -> {

            String cursor = getQueryParam(exchange, "cursor");
            if (cursor != null && !cursor.isEmpty()) {
                handleSearchAuditCursor(exchange);
            } else {
                int page = getQueryParamAsInt(exchange, "page", 1);
                int pageSize = getQueryParamAsInt(exchange, "pageSize", 20);
                String type = getQueryParam(exchange, "type");
                String startTimeStr = getQueryParam(exchange, "startTime");
                String endTimeStr = getQueryParam(exchange, "endTime");

                pageSize = Math.min(Math.max(pageSize, 1), 100);

                handleSearchAudit(exchange, page, pageSize, type, startTimeStr, endTimeStr);
            }
        });


        router.get("api/audit/search-cursor", exchange -> {
            handleSearchAuditCursor(exchange);
        });

        router.get("api/audit/stats", exchange -> {
            handleGetStats(exchange);
        });
    }

    /**
     * Handle get player audit records request.
     */
    private void handleGetPlayerAudit(HttpServerExchange exchange, String playerName, int page, int pageSize) {
        if (playerName == null || playerName.isBlank()) {
            exchange.setStatusCode(400);
            sendJson(exchange, ApiResponse.error("INVALID_PLAYER", "Player name is required"));
            return;
        }

        Player player = plugin.getServer().getPlayer(playerName);
        UUID playerUuid;
        if (player != null) {
            playerUuid = player.getUniqueId();
        } else {
            var offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(playerName);
            if (offlinePlayer != null) {
                playerUuid = offlinePlayer.getUniqueId();
            } else {
                exchange.setStatusCode(404);
                sendJson(exchange, ApiResponse.error("PLAYER_NOT_FOUND", "Player not found: " + playerName));
                return;
            }
        }

        int offset = (page - 1) * pageSize;



        List<Map<String, Object>> recordsData;
        if (hybridAuditManager != null && hybridAuditManager.isEnabled()) {

            List<AuditRecord> records = hybridAuditManager.queryAudit(playerUuid, pageSize, offset);
            recordsData = records.stream().map(this::recordToMap).toList();
        } else if (localEconomyHandler != null) {

            List<Map<String, Object>> records = localEconomyHandler.getTransactionHistory(playerUuid, pageSize, offset);

            recordsData = records.stream().map(this::localRecordToMap).toList();
        } else {

            List<AuditRecord> records = auditLogger.getPlayerRecords(playerUuid, pageSize, offset);
            recordsData = records.stream().map(this::recordToMap).toList();
        }



        boolean hasMore = recordsData.size() == pageSize;

        sendJson(exchange, ApiResponse.paginated(recordsData, page, pageSize, hasMore ? (offset + pageSize + 1) : (offset + recordsData.size())));
    }

    /**
     * Handle search audit records request.
     */
    private void handleSearchAudit(HttpServerExchange exchange, int page, int pageSize,
                                   String type, String startTimeStr, String endTimeStr) {
        UUID playerUuid = null;
        long startTime = 0;
        long endTime = 0;
        AuditRecord.AuditType auditType = null;

        String playerName = getQueryParam(exchange, "player");
        if (playerName != null && !playerName.isBlank()) {
            var offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(playerName);
            if (offlinePlayer != null) {
                playerUuid = offlinePlayer.getUniqueId();
            }
        }

        if (startTimeStr != null && !startTimeStr.isBlank()) {
            try {
                startTime = Long.parseLong(startTimeStr);
            } catch (NumberFormatException ignored) {}
        }

        if (endTimeStr != null && !endTimeStr.isBlank()) {
            try {
                endTime = Long.parseLong(endTimeStr);
            } catch (NumberFormatException ignored) {}
        }

        if (type != null && !type.isBlank() && !type.equalsIgnoreCase("all")) {
            try {
                auditType = AuditRecord.AuditType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        var criteria = new AuditLogger.AuditSearchCriteria(
                playerUuid, startTime, endTime, auditType, 1000, null
        );



        List<Map<String, Object>> recordsData;
        if (hybridAuditManager != null && hybridAuditManager.isEnabled()) {

            List<AuditRecord> records = queryHybridWithCriteria(criteria);
            recordsData = records.stream().map(this::recordToMap).toList();
        } else if (localEconomyHandler != null) {


            List<Map<String, Object>> allRecords = localEconomyHandler.getTransactionHistory(
                playerUuid != null ? playerUuid : UUID.fromString("00000000-0000-0000-0000-000000000000"),
                1000, 0);

            List<Map<String, Object>> transformedRecords = allRecords.stream()
                .map(this::localRecordToMap)
                .toList();
            recordsData = filterLocalRecords(transformedRecords, playerUuid, startTime, endTime, auditType);
        } else {

            List<AuditRecord> records = auditLogger.search(criteria);
            recordsData = records.stream().map(this::recordToMap).toList();
        }

        int totalItems = recordsData.size();
        int offset = (page - 1) * pageSize;

        int endIndex = Math.min(offset + pageSize, recordsData.size());
        List<Map<String, Object>> pageRecords = offset < recordsData.size()
                ? recordsData.subList(offset, endIndex)
                : List.of();

        sendJson(exchange, ApiResponse.paginated(pageRecords, page, pageSize, totalItems));
    }

    /**
     * Handle search audit records with cursor pagination.
     * Queries both Redis (real-time) and Database (history), then merges results.
     *
     * Sort order: newest first (timestamp DESC, sequence DESC).
     * Cursor filter is applied to the COMBINED sorted result to ensure correct
     * pagination even when Redis always returns the same newest-N window.
     */
    private void handleSearchAuditCursor(HttpServerExchange exchange) {
        String cursor = getQueryParam(exchange, "cursor");
        int pageSize = getQueryParamAsInt(exchange, "pageSize", 20);
        String type = getQueryParam(exchange, "type");
        String startTimeStr = getQueryParam(exchange, "startTime");
        String endTimeStr = getQueryParam(exchange, "endTime");

        pageSize = Math.min(Math.max(pageSize, 1), 100);

        UUID playerUuid = null;
        long startTime = 0;
        long endTime = 0;
        AuditRecord.AuditType auditType = null;

        String playerFilter = getQueryParam(exchange, "player");
        if (playerFilter != null && !playerFilter.isBlank()) {
            try {
                playerUuid = UUID.fromString(playerFilter);
            } catch (IllegalArgumentException e) {

                var offlinePlayer = plugin.getServer().getOfflinePlayerIfCached(playerFilter);
                if (offlinePlayer != null) {
                    playerUuid = offlinePlayer.getUniqueId();
                } else if (nameResolver != null) {

                    playerUuid = nameResolver.resolve(playerFilter).orElse(null);
                }

                if (playerUuid == null) {
                    sendJson(exchange, ApiResponse.success(Map.of(
                        "data", List.of(),
                        "pagination", Map.of("nextCursor", "", "hasMore", false)
                    )));
                    return;
                }
            }
        }

        if (startTimeStr != null && !startTimeStr.isBlank()) {
            try {
                startTime = Long.parseLong(startTimeStr);
            } catch (NumberFormatException ignored) {
            }
        }

        if (endTimeStr != null && !endTimeStr.isBlank()) {
            try {
                endTime = Long.parseLong(endTimeStr);
            } catch (NumberFormatException ignored) {
            }
        }

        if (type != null && !type.isBlank()) {
            try {
                auditType = AuditRecord.AuditType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }


        List<AuditRecord> allRecords = new ArrayList<>();


        int totalNeeded = pageSize * 2;



        if (hybridAuditManager != null && hybridAuditManager.isEnabled()) {
            List<AuditRecord> redisRecords = hybridAuditManager.getRecentFromRedisFiltered(
                playerUuid, auditType, startTime, endTime, totalNeeded
            );
            allRecords.addAll(redisRecords);
        }



        if (localEconomyHandler != null && (hybridAuditManager == null || !hybridAuditManager.isEnabled())) {

            long cursorTimestamp = Long.MAX_VALUE;
            int cursorSequence = Integer.MAX_VALUE;

            if (cursor != null && !cursor.isEmpty()) {
                String[] cursorParts = cursor.split(",");
                if (cursorParts.length >= 2) {
                    try {
                        cursorTimestamp = Long.parseLong(cursorParts[0]);
                        cursorSequence = Integer.parseInt(cursorParts[1]);
                    } catch (NumberFormatException ignored) {
                        cursorTimestamp = Long.MAX_VALUE;
                        cursorSequence = Integer.MAX_VALUE;
                    }
                }
            }


            final UUID filterUuid = playerUuid;
            final long filterStartTime = startTime;
            final long filterEndTime = endTime;
            final AuditRecord.AuditType filterAuditType = auditType;
            final long finalCursorTimestamp = cursorTimestamp;
            final int finalCursorSequence = cursorSequence;

            try {

                List<Map<String, Object>> localRecords = localEconomyHandler.getTransactionsBefore(
                    filterUuid, filterStartTime, filterEndTime,
                    filterAuditType != null ? filterAuditType.name() : null,
                    finalCursorTimestamp, finalCursorSequence, totalNeeded
                );


                List<AuditRecord> localAuditRecords = localRecords.stream()
                    .map(record -> {
                        String uuidStr = record.get("uuid") != null ? record.get("uuid").toString() : "";
                        String playerName = record.get("name") != null ? record.get("name").toString() : "Unknown";
                        Object amount = record.get("amount");
                        Object balanceAfter = record.get("balance_after");
                        Object timestamp = record.get("timestamp");
                        Object sourceObj = record.get("source");

                        double amt = amount instanceof Number ? ((Number) amount).doubleValue() : 0.0;
                        double balAfter = balanceAfter instanceof Number ? ((Number) balanceAfter).doubleValue() : 0.0;
                        long ts = timestamp instanceof Number ? ((Number) timestamp).longValue() : System.currentTimeMillis();

                        String typeStr = record.get("type") != null ? record.get("type").toString() : "DEPOSIT";

                        if ("PLAYER_TRANSFER".equals(sourceObj != null ? sourceObj.toString() : "")) {
                            typeStr = "TRANSFER";
                        }
                        AuditRecord.AuditType recAuditType;
                        try {
                            recAuditType = AuditRecord.AuditType.valueOf(typeStr.toUpperCase());
                        } catch (IllegalArgumentException e) {
                            recAuditType = AuditRecord.AuditType.DEPOSIT;
                        }


                        EconomyEvent.EventSource source;
                        String sourceStr = sourceObj != null ? sourceObj.toString() : "LOCAL";
                        try {
                            source = EconomyEvent.EventSource.valueOf(sourceStr);
                        } catch (IllegalArgumentException e) {

                            source = switch (typeStr.toUpperCase()) {
                                case "DEPOSIT" -> EconomyEvent.EventSource.ADMIN_GIVE;
                                case "WITHDRAW" -> EconomyEvent.EventSource.ADMIN_TAKE;
                                case "SET_BALANCE" -> EconomyEvent.EventSource.ADMIN_SET;
                                default -> EconomyEvent.EventSource.COMMAND_ADMIN;
                            };
                        }

                        UUID playerUuidValue;
                        try {
                            playerUuidValue = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException e) {
                            playerUuidValue = UUID.fromString("00000000-0000-0000-0000-000000000000");
                        }


                        double signedAmt = amt;
                        if ("WITHDRAW".equals(typeStr) || "TRANSFER_OUT".equals(typeStr)) {
                            signedAmt = -Math.abs(amt);
                        } else if ("PLAYER_TRANSFER".equals(sourceObj != null ? sourceObj.toString() : "")) {
                            signedAmt = amt;
                        } else {
                            signedAmt = Math.abs(amt);
                        }

                        Object seqObj = record.get("sequence");
                        int recordSeq = seqObj instanceof Number ? ((Number) seqObj).intValue() : 0;

                        return new AuditRecord(
                            "local-" + record.get("id"),
                            ts,
                            recordSeq,
                            recAuditType,
                            playerUuidValue,
                            playerName,
                            BigDecimal.valueOf(signedAmt),
                            BigDecimal.valueOf(balAfter),
                            source,
                            "LOCAL",
                            null,
                            null,
                            null,
                            1
                        );
                    })
                    .collect(java.util.stream.Collectors.toList());

                allRecords.addAll(localAuditRecords);
            } catch (Exception e) {
                plugin.getLogger().severe("Error querying local audit records: " + e.getMessage());
            }
        }


        var criteria = new AuditLogger.AuditSearchCriteria(
                playerUuid,
                startTime,
                endTime,
                auditType,
                totalNeeded,
                cursor
        );
        var dbResult = auditLogger.searchWithCursor(criteria);
        allRecords.addAll(dbResult.records());


        Map<String, AuditRecord> deduplicated = new LinkedHashMap<>();
        for (AuditRecord record : allRecords) {
            deduplicated.putIfAbsent(record.id(), record);
        }




        List<AuditRecord> sortedRecords = deduplicated.values().stream()
            .sorted((a, b) -> {
                int cmp = Long.compare(b.timestamp(), a.timestamp());
                if (cmp != 0) return cmp;
                return Integer.compare(b.sequence(), a.sequence());
            })
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));





        if (cursor != null && !cursor.isEmpty()) {
            String[] parts = cursor.split(",");
            if (parts.length >= 2) {
                try {
                    long cursorTimestamp = Long.parseLong(parts[0]);
                    int cursorSequence = Integer.parseInt(parts[1]);
                    sortedRecords = sortedRecords.stream()
                        .filter(r -> r.timestamp() < cursorTimestamp ||
                                    (r.timestamp() == cursorTimestamp && r.sequence() < cursorSequence))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                } catch (NumberFormatException ignored) {
                }
            }
        }


        List<AuditRecord> pageRecords = sortedRecords.stream()
            .limit(pageSize)
            .toList();


        boolean hasMore = sortedRecords.size() > pageSize;


        String nextCursor = "";
        if (hasMore && !pageRecords.isEmpty()) {
            AuditRecord lastRecord = pageRecords.get(pageRecords.size() - 1);
            nextCursor = lastRecord.timestamp() + "," + lastRecord.sequence();
        }

        List<Map<String, Object>> recordsData = pageRecords.stream()
                .map(this::recordToMap)
                .toList();

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("nextCursor", nextCursor);
        pagination.put("hasMore", hasMore);
        pagination.put("pageSize", pageSize);

        sendJson(exchange, ApiResponse.cursorPaginated(recordsData, pagination));
    }

    /**
     * Query both Redis and Database with criteria, then merge and deduplicate.
     * This ensures consistent data source regardless of playerUuid parameter.
     */
    private List<AuditRecord> queryHybridWithCriteria(AuditLogger.AuditSearchCriteria criteria) {
        List<AuditRecord> allRecords = new ArrayList<>();
        int totalNeeded = 1000;


        if (hybridAuditManager != null && hybridAuditManager.isEnabled()) {

            List<AuditRecord> redisRecords = hybridAuditManager.getRecentFromRedisFiltered(
                criteria.playerUuid(),
                criteria.type(),
                criteria.startTime(),
                criteria.endTime(),
                totalNeeded
            );
            allRecords.addAll(redisRecords);
        }


        var dbCriteria = new AuditLogger.AuditSearchCriteria(
                criteria.playerUuid(),
                criteria.startTime(),
                criteria.endTime(),
                criteria.type(),
                totalNeeded,
                null
        );
        List<AuditRecord> dbRecords = auditLogger.search(dbCriteria);
        allRecords.addAll(dbRecords);


        Map<String, AuditRecord> deduplicated = new LinkedHashMap<>();
        for (AuditRecord record : allRecords) {
            deduplicated.putIfAbsent(record.id(), record);
        }


        return deduplicated.values().stream()
            .sorted((a, b) -> {
                int cmp = Long.compare(b.timestamp(), a.timestamp());
                if (cmp != 0) return cmp;
                return Integer.compare(b.sequence(), a.sequence());
            })
            .toList();
    }

    /**
     * Handle get audit statistics request.
     */
    private void handleGetStats(HttpServerExchange exchange) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("bufferSize", auditLogger.getBufferSize());
        stats.put("enabled", auditLogger.isEnabled());

        sendJson(exchange, ApiResponse.success(stats));
    }

    /**
     * Convert AuditRecord to Map for JSON serialization.
     */
    private Map<String, Object> recordToMap(AuditRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", record.id());
        map.put("timestamp", record.timestamp());
        map.put("sequence", record.sequence());
        map.put("type", record.type().name());
        map.put("playerUuid", record.playerUuid().toString());
        map.put("playerName", record.playerName());
        map.put("amount", record.amount().toPlainString());
        map.put("balanceAfter", record.balanceAfter().toPlainString());
        map.put("source", record.source().name());
        map.put("serverName", record.server());
        if (record.targetUuid() != null) {
            map.put("targetUuid", record.targetUuid().toString());
            map.put("targetName", record.targetName());
        }
        if (record.reason() != null) {
            map.put("reason", record.reason());
        }
        if (record.mergedCount() > 1) {
            map.put("mergedCount", record.mergedCount());
        }
        return map;
    }

    /**
     * Convert local SQLite transaction record to Map for JSON serialization.
     * Handles PLAYER_TRANSFER type display consistently with SYNC mode.
     */
    private Map<String, Object> localRecordToMap(Map<String, Object> record) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", record.get("id"));
        map.put("timestamp", record.get("timestamp"));
        map.put("sequence", record.get("sequence"));


        String type = (String) record.get("type");
        String source = (String) record.get("source");
        String displayType = type;

        if ("PLAYER_TRANSFER".equals(source)) {

            displayType = "TRANSFER";
        }

        map.put("type", displayType);
        map.put("originalType", type);
        map.put("playerUuid", record.get("uuid"));
        map.put("playerName", record.get("name"));
        map.put("amount", record.get("amount"));
        map.put("balanceAfter", record.get("balance_after"));
        map.put("source", source);


        Object targetName = record.get("target_name");
        if (targetName != null) {
            map.put("targetName", targetName);
        }

        return map;
    }

    /**
     * Extract a path segment by zero-based index from the relative URL.
     * Example: "api/audit/player/Steve" → index 3 returns "Steve".
     */
    private String extractPathParamAt(HttpServerExchange exchange, int index) {
        String path = exchange.getRelativePath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        return (parts.length > index) ? parts[index] : "";
    }

    /**
     * Get query parameter as integer with default.
     */
    private int getQueryParamAsInt(HttpServerExchange exchange, String param, int defaultValue) {
        Deque<String> values = exchange.getQueryParameters().get(param);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.getFirst());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Get query parameter as String.
     */
    private String getQueryParam(HttpServerExchange exchange, String param) {
        Deque<String> values = exchange.getQueryParameters().get(param);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    /**
     * Send JSON response.
     */
    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(
                io.undertow.util.Headers.CONTENT_TYPE,
                "application/json;charset=UTF-8"
        );
        exchange.getResponseSender().send(json);
    }

    /**
     * Filter local (SQLite) records by criteria.
     */
    private List<Map<String, Object>> filterLocalRecords(List<Map<String, Object>> records,
            UUID playerUuid, long startTime, long endTime, AuditRecord.AuditType auditType) {
        return records.stream()
                .filter(record -> {

                    if (playerUuid != null) {
                        String uuid = (String) record.get("uuid");
                        if (uuid == null || !uuid.equals(playerUuid.toString())) {
                            return false;
                        }
                    }

                    if (auditType != null) {
                        String type = (String) record.get("type");
                        if (type == null || !type.equalsIgnoreCase(auditType.name())) {
                            return false;
                        }
                    }

                    if (startTime > 0) {
                        long timestamp = ((Number) record.get("timestamp")).longValue();
                        if (timestamp < startTime) {
                            return false;
                        }
                    }
                    if (endTime > 0) {
                        long timestamp = ((Number) record.get("timestamp")).longValue();
                        if (timestamp > endTime) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
    }
}
