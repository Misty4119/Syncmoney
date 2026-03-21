# Syncmoney Developer Guide

A comprehensive guide for developers who want to integrate with Syncmoney or extend its functionality.

> **See also:** [Architecture Overview](ARCHITECTURE.md) for system-level design and data flow diagrams.
>
> **Version**: v1.1.1

---

## Table of Contents

1. [Event System](#event-system)
2. [REST API](#rest-api)
3. [Configuration](#configuration)
4. [Vault API Integration](#vault-api-integration)
5. [PlaceholderAPI Expansion](#placeholderapi-expansion)
6. [SSE API](#sse-api)
7. [Commands](#commands)
8. [Building from Source](#building-from-source)
9. [Coding Standards](#coding-standards)
10. [Known Limitations](#known-limitations)

---

## Event System

Syncmoney provides several events that developers can listen to for integrating with other plugins.

### Available Events

| Event Class | Description |
|------------|-------------|
| `AsyncPreTransactionEvent` | Fired before a transaction is processed (cancellable) |
| `PostTransactionEvent` | Fired after a transaction completes |
| `ShadowSyncEvent` | Fired when background sync operations occur |
| `TransactionCircuitBreakEvent` | Fired when circuit breaker triggers |

### Listening to Events

```java
import noietime.syncmoney.event.AsyncPreTransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class MyPluginListener implements Listener {

    @EventHandler
    public void onPreTransaction(AsyncPreTransactionEvent event) {
        // Get transaction details
        String playerName = event.getPlayerName();
        java.math.BigDecimal amount = event.getAmount();
        AsyncPreTransactionEvent.TransactionType type = event.getType();
        
        // Your custom logic here
        getLogger().info("Transaction pending: " + playerName + " - " + amount);
        
        // Cancel transaction if needed
        // event.setCancelled(true);
    }
}
```

### Event Class Reference

#### AsyncPreTransactionEvent

> **⚠️ v1.1.1 Known Limitation:** `AsyncPreTransactionEvent` is defined but **not yet fired** by `EconomyFacade` in v1.1.1. Calling `event.setCancelled(true)` has **no effect** on actual transactions. This event will be fully wired in a future release. Use `PostTransactionEvent` for reliable transaction monitoring.

```java
// Fields
UUID getPlayerUuid();
String getPlayerName();
TransactionType getType(); // DEPOSIT, WITHDRAW, SET_BALANCE, TRANSFER
java.math.BigDecimal getAmount();
java.math.BigDecimal getCurrentBalance();
String getSource();
UUID getTargetUuid();
String getTargetName();
String getReason();

// Cancellation (currently no-op — see warning above)
boolean isCancelled();
void setCancelled(boolean cancelled);
void setCancelled(boolean cancelled, String reason);
String getCancelReason();
```

#### PostTransactionEvent

```java
// Fields
UUID getPlayerUuid();
String getPlayerName();
TransactionType getType();
java.math.BigDecimal getAmount();
java.math.BigDecimal getBalanceBefore();
java.math.BigDecimal getBalanceAfter();
String getSource(); // See EconomyEvent.EventSource values below
UUID getTargetUuid();
String getTargetName();
String getReason();
boolean isSuccess();
String getErrorMessage();

// Utility
java.math.BigDecimal getBalanceChange(); // Net change (can be negative)
```

**`EconomyEvent.EventSource` values** (passed as `source` string):

| Value | Description |
|-------|-------------|
| `VAULT_DEPOSIT` | Triggered via Vault API `depositPlayer()` |
| `VAULT_WITHDRAW` | Triggered via Vault API `withdrawPlayer()` |
| `COMMAND_PAY` | Player `/pay` command |
| `COMMAND_ADMIN` | Admin command (`/syncmoney admin`) |
| `ADMIN_SET` | Admin set balance |
| `ADMIN_GIVE` | Admin give currency |
| `ADMIN_TAKE` | Admin take currency |
| `PLAYER_TRANSFER` | Direct EconomyFacade transfer |
| `MIGRATION` | Data migration process |
| `SHADOW_SYNC` | Background shadow sync |
| `TEST` | Stress test command |
| `PLUGIN_DEPOSIT` | Third-party plugin deposit (bypasses Vault pairing) |
| `PLUGIN_WITHDRAW` | Third-party plugin withdrawal (bypasses Vault pairing) |

#### ShadowSyncEvent

Called when shadow sync operations are performed.

**Event Methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getSyncType()` | `SyncType` | The type of sync operation (FULL, INCREMENTAL, MANUAL) |
| `getStatus()` | `SyncStatus` | The status of the sync (STARTED, IN_PROGRESS, COMPLETED, FAILED) |
| `getPlayersProcessed()` | `int` | Number of players processed |
| `getTotalPlayers()` | `int` | Total number of players to sync |
| `getProgressPercentage()` | `int` | Progress percentage (0-100) |
| `getServerName()` | `String` | Source/destination server name |
| `getErrorMessage()` | `String` | Error message if failed, null otherwise |
| `getDuration()` | `Duration` | Duration of the sync operation |
| `getAffectedPlayers()` | `Set<UUID>` | Set of affected player UUIDs |
| `isFinalStatus()` | `boolean` | Whether this is a final status (COMPLETED or FAILED) |
| `isSuccessful()` | `boolean` | Whether the sync completed successfully |

**Example:**

```java
@EventHandler
public void onShadowSync(ShadowSyncEvent event) {
    if (event.getStatus() == SyncStatus.COMPLETED) {
        plugin.getLogger().info("Sync completed: " +
            event.getPlayersProcessed() + "/" + event.getTotalPlayers());
    }
}
```

#### TransactionCircuitBreakEvent

Called when the economic circuit breaker triggers or changes state.

**Event Methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getPreviousState()` | `CircuitState` | State before the transition |
| `getCurrentState()` | `CircuitState` | State after the transition |
| `getReason()` | `TriggerReason` | Why the circuit changed (`SINGLE_TRANSACTION_LIMIT`, `RATE_LIMIT`, `INFLATION_DETECTED`, `SUDDEN_CHANGE`, `MANUAL_LOCK`) |
| `getMessage()` | `String` | Human-readable description |
| `getAffectedPlayers()` | `Set<UUID>` | Set of affected player UUIDs |
| `getThreshold()` | `BigDecimal` | Threshold value that was exceeded |
| `getActualValue()` | `BigDecimal` | Actual value that triggered the event |
| `isStateTransition()` | `boolean` | Whether previousState ≠ currentState |
| `isLocked()` | `boolean` | Whether current state is LOCKED |
| `isUnlocked()` | `boolean` | Whether transitioning away from LOCKED |

**CircuitState values:** `NORMAL`, `WARNING`, `LOCKED`

---

## REST API

Syncmoney exposes a REST API via the built-in Undertow web server.

### Authentication

Most API endpoints require an API key in the Authorization header:

```
Authorization: Bearer <your-api-key>
```

The `/health` endpoint does not require authentication.

### Endpoints

#### Health Check (No Auth Required)

```
GET /health
```

Response:
```json
{"success":true,"data":{"status":"ok","version":"1.1.1"}}
```

#### System API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/system/status` | Plugin status, uptime, player counts, database status |
| GET | `/api/system/redis` | Redis connection status |
| GET | `/api/system/breaker` | Circuit breaker state |
| GET | `/api/system/metrics` | Memory usage, thread count, TPS |

#### Economy API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/economy/stats` | Total supply, player counts, today's transactions, currency name |
| GET | `/api/economy/player/{uuid}/balance` | Get a specific player's balance by UUID |
| GET | `/api/economy/top` | Top 10 players by balance |

#### Audit API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/audit/player/{playerName}` | Get audit records for a player (paginated) |
| GET | `/api/audit/search` | Search audit records with filters |
| GET | `/api/audit/stats` | Audit module buffer size and enabled status |

Query parameters for search: `player`, `type`, `startTime`, `endTime`, `page`, `pageSize`

#### Nodes API (Central Mode)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/nodes` | List all configured nodes |
| POST | `/api/nodes` | Create a new node |
| PUT | `/api/nodes/{index}` | Update a node |
| DELETE | `/api/nodes/{index}` | Delete a node |
| POST | `/api/nodes/{index}/ping` | Manually ping a node |
| GET | `/api/nodes/status` | Get detailed status of all nodes |
| POST | `/api/nodes/{index}/proxy` | Proxy HTTP request to remote node |
| POST | `/api/nodes/sync` | Push config to all nodes (Central Mode) |
| POST | `/api/nodes/{index}/sync` | Push config to single node (Central Mode) |
| POST | `/api/config/sync` | Receive config from central (Node-side) |

#### Cross-Server Statistics API (Central Mode)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/economy/cross-server-stats` | Aggregated stats from all nodes |
| GET | `/api/economy/cross-server-top` | Aggregated leaderboard across nodes |

#### Config API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/config` | Get current configuration (passwords hidden) |
| POST | `/api/config/reload` | Reload configuration from disk |

#### Settings API

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/settings` | Get theme and language preferences |
| POST | `/api/settings/theme` | Update theme (`dark` or `light`) |
| POST | `/api/settings/language` | Update language (`zh-TW` or `en-US`) |

### Response Format

All responses include a `meta` field:

```json
{
  "success": true,
  "data": { ... },
  "meta": { "timestamp": 1709337000000, "version": "1.1.1" }
}
```

For complete request/response examples, see [API_REFERENCE.md](API_REFERENCE.md).

---

## Configuration

### Main Configuration (config.yml)

```yaml
# ==========================================
# 1. Core and Basic Settings
# ==========================================
server-name: ""              # Multi-server identification
queue-capacity: 50000        # Event queue capacity
pubsub-enabled: true         # Enable pub/sub
db-enabled: true            # Enable database
debug: false                # Debug mode

# ==========================================
# 2. Database and Redis
# ==========================================
redis:
  enabled: true
  host: "localhost"
  port: 6379
  password: ""
  database: 0
  pool-size: 30

database:
  enabled: true
  type: "mysql"             # MySQL, PostgreSQL
  host: "localhost"
  port: 3306
  username: "root"
  password: ""
  database: "syncmoney"

# ==========================================
# 3. Economy and Transaction Settings
# ==========================================
economy:
  mode: "auto"              # auto, local, local_redis, sync, cmi
  sync:
    vault-intercept: true
  cmi:
    balance-mode: "internal"
    debounce-ticks: 5

display:
  currency-name: "$"
  decimal-places: 2

pay:
  cooldown-seconds: 30
  min-amount: 1
  max-amount: 1000000
  confirm-threshold: 100000

baltop:
  enabled: true
  cache-seconds: 30
  format: "smart"

# ==========================================
# 4. Security and Protection
# ==========================================
circuit-breaker:
  enabled: true
  max-single-transaction: 100000000
  max-transactions-per-second: 10
  rapid-inflation-threshold: 0.2
  sudden-change-threshold: 100
  redis-disconnect-lock-seconds: 5
  memory-warning-threshold: 80

player-protection:
  enabled: true
  rate-limit:
    max-transactions-per-second: 5
    max-transactions-per-minute: 50
    max-amount-per-minute: 1000000

# Discord Webhook
discord-webhook:
  enabled: false
  webhooks:
    - name: "admin-alerts"
      url: "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL_HERE"
      type: "private"
      events:
        - "player_warning"
        - "player_locked"
        - "player_unlocked"
        - "global_lock"

# Audit log system
audit:
  enabled: true
  batch-size: 1
  retention-days: 90

# ==========================================
# 8. Web Admin Dashboard
# ==========================================
web-admin:
  enabled: false
  central-mode: false
  nodes: []
  bundled-version: "1.1.1"
  server:
    host: "localhost"
    port: 8080
  web:
    path: "syncmoney-web"
    auto-update: false
    github-repo: "Misty4119/Syncmoney"
  security:
    api-key: "change-me-in-production"
    rate-limit:
      enabled: true
      requests-per-minute: 60
  ui:
    theme: "dark"
    language: "zh-TW"
```

### Messages Configuration (messages.yml)

All player-facing messages are configurable via `messages.yml`. Use MiniMessage format:

```yaml
# Example: Customizing payment success message
pay:
  success-sender: '<prefix>You sent {amount} to {player}'
```

---

## Vault API Integration

Syncmoney registers as a Vault Economy provider. Other plugins can use:

```java
// Get economy service
Economy economy = VaultAPI.getEconomy();

// Check balance
if (economy.hasAccount(player)) {
    double balance = economy.getBalance(player);
}

// Deposit money
economy.depositPlayer(player, amount);

// Withdraw money
economy.withdrawPlayer(player, amount);
```

#### Plugin API (Recommended for Third-Party Plugins)

For third-party plugins (e.g., chest shops, auction houses), use the `SyncmoneyVaultProvider` extended API directly to bypass the Vault pairing mechanism. This prevents "orphan VAULT_DEPOSIT" issues during high-frequency transactions.

```java
import net.milkbowl.vault.economy.Economy;
import noietime.syncmoney.vault.SyncmoneyVaultProvider;
import org.bukkit.plugin.RegisteredServiceProvider;

// Get economy service
Economy economy = VaultAPI.getEconomy();
if (!(economy instanceof SyncmoneyVaultProvider)) {
    // Not Syncmoney
    return;
}
SyncmoneyVaultProvider syncmoney = (SyncmoneyVaultProvider) economy;

// Deposit for plugin (bypasses Vault pairing)
EconomyResponse resp = syncmoney.depositPlayerForPlugin(player, amount, "MyPlugin");

// Withdraw for plugin (bypasses Vault pairing)
EconomyResponse resp = syncmoney.withdrawPlayerForPlugin(player, amount, "MyPlugin");

// Atomic transfer between players (plugin-level attribution)
EconomyResponse resp = syncmoney.pluginTransfer(fromPlayer, toPlayer, amount, "MyPlugin");
```

**When to use Plugin API vs Standard Vault API:**

| Scenario | Recommended API | Reason |
|----------|-----------------|--------|
| Chest shop buy/sell | `depositPlayerForPlugin` / `withdrawPlayerForPlugin` | Avoids Vault pairing race conditions |
| Auction house transfers | `pluginTransfer` | Atomic operation, no pairing needed |
| Standard economy operations | Standard Vault API | Full compatibility |

### Vault Permissions

#### Player Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `syncmoney.money` | `true` (all) | View own balance |
| `syncmoney.money.others` | `op` | View other players' balance |
| `syncmoney.pay` | `true` (all) | Transfer money to others |
| `syncmoney.baltop` | `true` (all) | View wealth rankings |

#### Admin Permissions (Basic)

| Permission | Default | Description |
|------------|---------|-------------|
| `syncmoney.admin` | `op` | General admin commands (top-level) |
| `syncmoney.admin.set` | `op` | Set player balance |
| `syncmoney.admin.give` | `op` | Give money to players |
| `syncmoney.admin.take` | `op` | Take money from players |
| `syncmoney.admin.audit` | `op` | View audit logs |
| `syncmoney.admin.monitor` | `op` | View system monitoring |
| `syncmoney.admin.econstats` | `op` | View economic statistics |
| `syncmoney.admin.reload` | `op` | Reload configuration |
| `syncmoney.admin.test` | `op` | Execute stress test commands |

#### Admin Tier Permissions (Daily Limits)

| Permission | Default | Description | Daily Give Limit | Daily Take Limit |
|------------|---------|-------------|-----------------|-----------------|
| `syncmoney.admin.observe` | `false` | Read-only observer (no economic operations) | 0 | 0 |
| `syncmoney.admin.reward` | `false` | Reward manager | 100,000 | 0 |
| `syncmoney.admin.general` | `false` | General admin | 1,000,000 | 1,000,000 |
| `syncmoney.admin.full` | `op` | Full admin (unlimited) | Unlimited | Unlimited |

> **Note:** The four tier permissions (`observe`, `reward`, `general`, `full`) control daily transaction limits for `give` and `take` operations. The `syncmoney.admin` node is a top-level convenience node (equivalent to `op` by default).

---

## PlaceholderAPI Expansion

Syncmoney provides the following placeholders:

### Player Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%syncmoney_balance%` | Player's balance (raw number) |
| `%syncmoney_balance_formatted%` | Player's balance (smart formatting) |
| `%syncmoney_balance_abbreviated%` | Player's balance (abbreviated, e.g., 1.5K) |
| `%syncmoney_rank%` | Player's wealth rank |
| `%syncmoney_my_rank%` | Player's wealth rank (alias) |
| `%syncmoney_balance_<player>%` | Get specified player's balance |

### Server Placeholders

| Placeholder | Description |
|-------------|-------------|
| `%syncmoney_total_supply%` | Total money in economy |
| `%syncmoney_total_players%` | Total players in leaderboard |
| `%syncmoney_online_players%` | Currently online players |
| `%syncmoney_version%` | Plugin version |
| `%syncmoney_top_<n>%` | Balance of player at rank n |

### Example Usage

```
# Player's balance
%syncmoney_balance%

# Player's rank
%syncmoney_rank%

# Server total supply
%syncmoney_total_supply%

# Top 5 player
%syncmoney_top_5%

# Check player's balance
%syncmoney_balance_Steve%
```

---

## WebSocket API

**Note:** Full WebSocket support is not currently implemented. The documentation below describes the planned API.

For real-time updates, please use the SSE (Server-Sent Events) API instead.

### SSE API

Server-Sent Events provide one-way server-push notifications.

### Connection

```
GET http://<host>:<port>/sse
```

Authentication via API key query parameter or Authorization header.

### Example

```javascript
const eventSource = new EventSource('http://localhost:8080/sse?apiKey=your-key');

eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    console.log('SSE event:', data);
};

eventSource.onerror = (error) => {
    console.error('SSE error:', error);
    // Note: The built-in frontend uses Exponential Backoff with Jitter for reconnections
    // to prevent thundering herd upon server restarts.
};
```

### SSE Event Types

| `type` value | Triggered by | Key `data` fields |
|---|---|---|
| `connected` | Client connects | `message` |
| `transaction` | `PostTransactionEvent` fires | `playerName`, `type`, `amount`, `balanceBefore`, `balanceAfter`, `success`, `timestamp` (epoch ms) |
| `circuit_break` | `TransactionCircuitBreakEvent` fires | `previousState`, `currentState`, `reason`, `message` |
| `system_alert` | Manual broadcast / internal alerts | `level`, `message` |

**Transaction event example:**
```json
{
  "type": "transaction",
  "event": "PostTransactionEvent",
  "data": {
    "playerName": "Steve",
    "type": "DEPOSIT",
    "amount": "1000",
    "balanceBefore": "5000",
    "balanceAfter": "6000",
    "success": true,
    "timestamp": 1709337000000
  }
}
```

---

## Commands

### Player Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/money [player]` | Check own or other player's balance | `syncmoney.money` |
| `/pay <player> <amount>` | Transfer money to another player | `syncmoney.pay` |
| `/baltop [page\|me]` | Wealth ranking (`me` shows your own rank) | `syncmoney.money` |

### Admin Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/syncmoney admin set <player> <amount>` | Set player's balance | `syncmoney.admin` + `canExecute(set)` |
| `/syncmoney admin give <player> <amount>` | Give money to player | `syncmoney.admin` + `canExecute(give)` |
| `/syncmoney admin take <player> <amount>` | Take money from player | `syncmoney.admin` + `canExecute(take)` |
| `/syncmoney admin reset <player>` | Reset player's balance to zero | `syncmoney.admin` + `canExecute(set)` |
| `/syncmoney admin view <player>` | View player's balance | `syncmoney.money.others` |
| `/syncmoney admin confirm` | Confirm large admin operation | `syncmoney.admin` |
| `/syncmoney breaker status` | View circuit breaker status | `syncmoney.admin` |
| `/syncmoney breaker reset` | Reset circuit breaker | `syncmoney.admin` |
| `/syncmoney breaker info` | View circuit breaker detailed info | `syncmoney.admin` |
| `/syncmoney breaker resources` | View resource status | `syncmoney.admin` |
| `/syncmoney breaker player <player>` | View player's protection status | `syncmoney.admin` |
| `/syncmoney breaker unlock <player>` | Manually unlock a player | `syncmoney.admin` |
| `/syncmoney audit <player> [page]` | View player's audit log | `syncmoney.admin.audit` |
| `/syncmoney audit search [--player <name>] [--type <type>] [--start <time>] [--end <time>] [--limit <n>]` | Advanced audit search | `syncmoney.admin.audit` |
| `/syncmoney audit stats` | View audit statistics | `syncmoney.admin.audit` |
| `/syncmoney audit cleanup` | Clean up old audit logs | `syncmoney.admin.full` |
| `/syncmoney monitor [overview]` | System overview | `syncmoney.admin` |
| `/syncmoney monitor redis` | Redis detailed status | `syncmoney.admin` |
| `/syncmoney monitor cache` | Cache status | `syncmoney.admin` |
| `/syncmoney monitor db` | Database status | `syncmoney.admin` |
| `/syncmoney monitor memory` | Memory status | `syncmoney.admin` |
| `/syncmoney monitor messages` | Message cache status | `syncmoney.admin` |
| `/syncmoney econstats [overview]` | Economy statistics overview | `syncmoney.admin.econstats` |
| `/syncmoney econstats supply` | Currency supply statistics | `syncmoney.admin.econstats` |
| `/syncmoney econstats players` | Player statistics | `syncmoney.admin.econstats` |
| `/syncmoney econstats transactions` | Transaction statistics | `syncmoney.admin.econstats` |
| `/syncmoney reload [all]` | Reload configuration | `syncmoney.admin.reload` |
| `/syncmoney reload config` | Reload config.yml | `syncmoney.admin.reload` |
| `/syncmoney reload messages` | Reload messages.yml | `syncmoney.admin.reload` |
| `/syncmoney reload permissions` | Reload permissions | `syncmoney.admin.reload` |
| `/syncmoney web download [latest]` | Download web frontend | `syncmoney.admin` |
| `/syncmoney web build` | Build web frontend (requires Node.js + pnpm) | `syncmoney.admin` |
| `/syncmoney web reload` | Reload web server | `syncmoney.admin` |
| `/syncmoney web open` | Open web admin in browser | `syncmoney.admin` |
| `/syncmoney web status` | View web frontend status | `syncmoney.admin` |
| `/syncmoney web check` | Check for updates | `syncmoney.admin` |
| `/syncmoney web version` | Alias for `web check` | `syncmoney.admin` |
| `/syncmoney migrate cmi [-force] [-no-backup] [-preview]` | Migrate CMI economy data | `syncmoney.admin` |
| `/syncmoney migrate local-to-sync [-force] [-no-backup]` | Migrate LOCAL mode to SYNC | `syncmoney.admin` |
| `/syncmoney migrate status` | View migration status | `syncmoney.admin` |
| `/syncmoney migrate stop` | Stop running migration | `syncmoney.admin` |
| `/syncmoney migrate resume` | Resume interrupted migration | `syncmoney.admin` |
| `/syncmoney migrate clear` | Clear migration checkpoint | `syncmoney.admin` |
| `/syncmoney shadow status` | View shadow sync status | `syncmoney.admin` |
| `/syncmoney shadow now` | Trigger immediate sync | `syncmoney.admin` |
| `/syncmoney shadow logs` | View recent sync logs | `syncmoney.admin` |
| `/syncmoney shadow history <player> [page]` | View player's sync history | `syncmoney.admin` |
| `/syncmoney shadow export <player> [startDate] [endDate]` | Export sync records to JSONL | `syncmoney.admin` |
| `/syncmoney debug player <player>` | Diagnose player's balance across all layers | `syncmoney.admin` |
| `/syncmoney debug system` | Diagnose system state | `syncmoney.admin` |
| `/syncmoney sync-balance <player>` | Force sync player's balance to Redis/DB | `syncmoney.admin` |
| `/syncmoney test concurrent-pay <threads> <iterations>` | Stress test (requires non-LOCAL mode) | `syncmoney.admin.test` |
| `/syncmoney test total-supply` | Verify total supply consistency | `syncmoney.admin.test` |

---

## Building from Source

### Prerequisites

- Java 21+
- Gradle (wrapper included)
- Node.js 20+ & pnpm (for web frontend)

### Build Commands

```bash
# Clone
git clone https://github.com/Misty4119/Syncmoney.git
cd Syncmoney

# Build plugin JAR (includes shadow relocation)
./gradlew shadowJar
# Output: build/libs/Syncmoney-1.1.1.jar

# Build PAPI expansion
cd syncmoney-papi-expansion && ../gradlew jar
# Output: build/libs/SyncmoneyExpansion-1.1.1.jar

# Build web frontend
cd syncmoney-web
npm install
npm run build
# Output: syncmoney-web/dist/

# Run tests
./gradlew test              # Java unit tests
cd syncmoney-web && npm run test:unit   # Frontend unit tests
cd syncmoney-web && npm run test:e2e    # Frontend E2E tests
```

### Shadow JAR Relocation

All runtime dependencies are relocated to `noietime.libs.*` in `build.gradle` to prevent classpath conflicts with other Minecraft plugins. When adding new runtime dependencies, you **must** add a corresponding `relocate()` rule.

---

## Coding Standards

### Commenting Standard
To maintain a high-quality, professional, and globally accessible codebase, Syncmoney strictly enforces the following commenting rules:
- **Block Comments (`/** */`)**: All classes, interfaces, and significant methods must have a block comment starting with a unique tag:
  - Backend Layer: `[SYNC-<CATEGORY>-<NUM>]` (e.g., `[SYNC-CMD-001]`, `[SYNC-CONFIG-005]`)
  - Web Frontend Layer: `[SYNC-WEB-<NUM>]`
  - PAPI Expansion: `[SYNC-PAPI-<NUM>]`
- **Inline Comments (`//`)**: The use of inline comments is strongly discouraged. Code should be self-documenting. Use inline comments only for explaining highly complex algorithms, race-condition preventions, or non-obvious mathematical formulas.
- **Language**: All comments, variable names, and documentation must be in **English**. Chinese or other non-English comments are strictly forbidden in the codebase (except for translation locale files like `zh-TW.json`).
- **Tone**: Comments must be concise, accurate, and rigorous. Avoid redundant statements that simply repeat what the code does.

---

## Known Limitations

| Limitation | Status | Workaround |
|------------|--------|------------|
| `AsyncPreTransactionEvent` not fired | v1.1.1 | Event defined but not yet wired in `EconomyFacade`. Use `PostTransactionEvent` instead. |
| WebSocket not fully implemented | v1.1.1 | `/ws` accepts connections but dispatch is incomplete. Use SSE (`/sse`) for production. |
| `event.setCancelled(true)` no-op | v1.1.1 | Pre-transaction cancellation has no effect. Will be wired in future release. |

---

## Support

- GitHub Issues: Report bugs and feature requests
- Discord: Join our community for support
