# Syncmoney

<p align="center">
  <a href="https://github.com/Misty4119/Syncmoney">
    <img src="https://img.shields.io/github/stars/Misty4119/Syncmoney" alt="Stars">
    <img src="https://img.shields.io/github/downloads/Misty4119/Syncmoney/total" alt="Downloads">
  </a>
  <br>
  <img src="https://img.shields.io/badge/Platform-Paper%201.20%2B-orange" alt="Platform">
  <img src="https://img.shields.io/badge/Java-21%2B-blue" alt="Java">
  <img src="https://img.shields.io/github/v/release/Misty4119/Syncmoney" alt="Release">
  <img src="https://img.shields.io/github/license/Misty4119/Syncmoney" alt="License">
</p>

<p align="center">
  <b>Enterprise-grade cross-server economy synchronization for Minecraft</b>
  <br>
  <a href="https://official.noie.fun">
    <img src="https://img.shields.io/discord/1453099158884978850?label=Discord&logo=discord" alt="Discord">
  </a>
  <a href="https://paypal.me/NoieSrv">
    <img src="https://img.shields.io/badge/Donate-PayPal-blue" alt="Donate">
  </a>
</p>

---

## Overview

Syncmoney is a high-performance Minecraft economy plugin designed for multi-server networks. It synchronizes player balances across all servers in real-time using Redis Pub/Sub, with comprehensive protection against economic exploits and data loss.

**Perfect for**: Survival servers, factions, SMPs, minigame networks, and any multi-server economy ecosystem.

### Key Highlights

- ⚡ **Sub-millisecond reads** — In-memory caching with O(1) balance lookups
- 🔄 **Instant cross-server sync** — Redis Pub/Sub propagation
- 🛡️ **4-layer circuit breaker** — Enterprise-grade economic protection
- 🌐 **Built-in Web Admin** — Vue 3 dashboard with real-time monitoring
- 🔧 **Zero-config Vault** — Works with any Vault-enabled economy plugin
- 📊 **Full audit trail** — Complete transaction history with cursor-based pagination
- 🎮 **Folia compatible** — Region-based scheduling support
- 💾 **Shadow Sync** — Automatic background backup to external databases
- 🔔 **Discord Webhooks** — Real-time security notifications

---

## Features

### Core Synchronization

- **Real-time Cross-Server Sync** — Balance changes instantly propagate to all servers via Redis Pub/Sub
- **Vault Integration** — Full compatibility with any Vault-enabled economy plugin (EssentialsX, CMI, etc.)
- **Atomic Transactions** — 7 Redis Lua scripts prevent money duplication during race conditions
- **Graceful Degradation** — Automatic fallback chain: Memory → Redis → Database → LocalSQLite
- **Bank Support** — Full Vault bank API implementation with Redis-backed storage
- **Version-based Optimistic Locking** — Prevents ABA problems across distributed nodes

### Security & Protection

- **4-Layer Circuit Breaker**
  - Single transaction limits
  - Rate limiting (transactions per second)
  - Sudden balance change detection
  - Periodic inflation monitoring
- **Per-Player Protection (L1-L4)**
  - L1: Rate limiting per player
  - L2: Anomaly detection with warning state
  - L3: Auto-lock for suspicious activity
  - L4: Global economy lock
- **Event Overflow Protection** — WAL-based graceful fallback for queue saturation
- **Transfer Guard** — Prevents money loss when players teleport during transactions
- **Rollback Protection** — Guards against failed database writes
- **Discord Webhook Alerts** — Real-time security notifications for 11 event types

### Web Admin Dashboard

- **Real-time Monitoring** — Server status, Redis/DB health, circuit breaker state
- **Connection Resiliency** — Intelligent SSE reconnections with Exponential Backoff & Jitter
- **Economy Dashboard** — Total supply, player counts, transaction statistics
- **Audit Log Viewer** — Search, filter, cursor-based pagination for massive datasets
- **Configuration Editor** — Edit server config without file access
- **Internationalization** — Full zh-TW and en-US support
- **Dark/Light Theme** — User preference persistence
- **PWA Support** — Installable as a standalone app

### Migration & Backup

- **CMI Migration Tool** — One-command import from CMI economy
- **Multi-Server Merge** — Combines economy data from multiple CMI servers (LATEST/SUM/MAX strategies)
- **Shadow Sync** — Automatic background backup to external databases
- **Checkpoint Resume** — Large migrations can resume if interrupted
- **Automatic Backup** — Creates JSON/SQL backup before migration

### Leaderboards & Integration

- **Global Baltop** — Redis-powered sorted set leaderboard with smart formatting
- **PlaceholderAPI Expansion** — 10+ dynamic placeholders for scoreboards
- **Folia Support** — Region-based scheduling compatibility
- **Audit Trail** — Cursor-based pagination with millisecond sequence ordering

---

## Requirements

| Component | Version |
|-----------|---------|
| Server | Paper 1.20+ / Spigot 1.20+ / Folia 1.20+ |
| Java | 21+ |
| Redis | 5.0+ (for multi-server sync) |
| Database | MySQL 8.0+ / MariaDB 10.5+ / PostgreSQL 13+ / SQLite |
| Plugin | Vault (required) |
| Plugin | PlaceholderAPI (optional) |

> **Note:** Redis and a database (MySQL/PostgreSQL) are required for cross-server sync. For single-server use, Syncmoney works with SQLite only (no Redis needed).

---

## Quick Start

### 1. Install

Place `Syncmoney.jar` in your server's `plugins/` folder.

### 2. Configure

Edit `plugins/Syncmoney/config.yml`:

```yaml
server-name: "survival-01"

redis:
  enabled: true
  host: "localhost"
  port: 6379

database:
  enabled: true
  type: "mysql"
  host: "localhost"
  port: 3306
  username: "root"
  password: ""
  database: "syncmoney"
```

### 3. Multi-Server Setup

1. Install Syncmoney on **all** servers
2. Connect all servers to the **same Redis instance**
3. Set a unique `server-name` for each server
4. Restart all servers — economy syncs automatically

### 4. PlaceholderAPI (Optional)

For scoreboard placeholders:

1. Download `SyncmoneyExpansion.jar` from [Releases](https://github.com/Misty4119/Syncmoney/releases)
2. Place in `plugins/PlaceholderAPI/expansions/`
3. Restart the server

---

## Configuration

### Economy Modes

| Mode | Description |
|------|-------------|
| `auto` | Auto-detect based on Redis/DB availability (recommended) |
| `local` | Single server only, SQLite backup |
| `local_redis` | Multi-server sync via Redis (no MySQL) |
| `sync` | Full cross-server sync with Redis + MySQL |
| `cmi` | Direct CMI database integration |

### Pay Settings

```yaml
pay:
  cooldown-seconds: 30
  min-amount: 1
  max-amount: 1000000
  confirm-threshold: 100000
```

### Circuit Breaker

```yaml
circuit-breaker:
  enabled: true
  max-single-transaction: 100000000
  max-transactions-per-second: 10
  rapid-inflation-threshold: 0.2
  sudden-change-threshold: 100

player-protection:
  enabled: true
  rate-limit:
    max-transactions-per-second: 5
    max-transactions-per-minute: 50
    max-amount-per-minute: 1000000
```

### Discord Webhook

```yaml
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
        - "circuit_breaker_trigger"
```

---

## Web Admin

Syncmoney includes a built-in web administration panel for monitoring economy status and managing transactions.

### Access

By default, the Web Admin is available at `http://localhost:8080` (when running locally).

### Configuration

```yaml
web-admin:
  enabled: true
  server:
    host: "0.0.0.0"
    port: 8080
  security:
    api-key: "your-secure-api-key-here"
    cors-allowed-origins: "*"
    rate-limit:
      enabled: true
      requests-per-minute: 60
```

### In-Game Management

```
/syncmoney web download [latest]    # Download web frontend from GitHub
/syncmoney web build                # Build frontend from source
/syncmoney web open                 # Open the web admin server
/syncmoney web status               # Show web frontend status
/syncmoney web check                # Check for available updates
```

### Production HTTPS Setup

Syncmoney Web Admin does not include built-in SSL support. For production environments, use a reverse proxy:

#### Nginx Configuration

```nginx
server {
    listen 443 ssl;
    server_name syncmoney.yourdomain.com;
    
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
    
    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # WebSocket / SSE support
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}

# HTTP to HTTPS redirect
server {
    listen 80;
    server_name syncmoney.yourdomain.com;
    return 301 https://$server_name$request_uri;
}
```

#### Caddy Configuration

```caddy
syncmoney.yourdomain.com {
    reverse_proxy localhost:8080
    tls /path/to/cert.pem /path/to/key.pem
}
```

**Note:** When using a reverse proxy, update `config.yml` to bind to localhost only:

```yaml
web-admin:
  server:
    host: "127.0.0.1"
    port: 8080
```

---

## Commands

### Players

| Command | Description | Permission |
|---------|-------------|------------|
| `/money` | View your balance | `syncmoney.money` |
| `/money <player>` | View another player's balance | `syncmoney.money.others` |
| `/pay <player> <amount>` | Transfer money to player | `syncmoney.pay` |
| `/pay confirm` | Confirm large transfer | — |
| `/baltop [page]` | View global leaderboard | `syncmoney.money` |
| `/baltop me` | View your rank | `syncmoney.money` |

### Administrators

| Command | Description | Permission |
|---------|-------------|------------|
| `/syncmoney admin give <player> <amount>` | Give money to player | `syncmoney.admin.give` |
| `/syncmoney admin take <player> <amount>` | Take money from player | `syncmoney.admin.take` |
| `/syncmoney admin set <player> <amount>` | Set player balance | `syncmoney.admin.set` |
| `/syncmoney admin reset <player>` | Reset balance to zero | `syncmoney.admin.set` |
| `/syncmoney migrate cmi` | Migrate from CMI | `syncmoney.admin` |
| `/syncmoney migrate local-to-sync` | Migrate from LOCAL to SYNC | `syncmoney.admin` |
| `/syncmoney breaker status` | View protection status | `syncmoney.admin` |
| `/syncmoney breaker unlock <player>` | Unlock locked player | `syncmoney.admin` |
| `/syncmoney audit <player>` | View transaction history | `syncmoney.admin.audit` |
| `/syncmoney audit search` | Advanced search | `syncmoney.admin.audit` |
| `/syncmoney econstats` | View economy statistics | `syncmoney.admin` |
| `/syncmoney monitor` | View system monitoring | `syncmoney.admin` |
| `/syncmoney shadow status` | View shadow sync status | `syncmoney.admin` |
| `/syncmoney web status` | View web admin status | `syncmoney.admin` |
| `/syncmoney reload` | Reload configuration | `syncmoney.admin` |
| `/syncmoney test concurrent-pay` | Stress test | `syncmoney.admin.test` |

### Admin Permission Tiers

| Tier | Permission | Daily Give Limit | Daily Take Limit |
|------|-----------|-----------------|-----------------|
| Observe | `syncmoney.admin.observe` | 0 | 0 |
| Reward | `syncmoney.admin.reward` | 100,000 | 0 |
| General | `syncmoney.admin.general` | 1,000,000 | 1,000,000 |
| Full | `syncmoney.admin.full` | Unlimited | Unlimited |

---

## Placeholders

Requires [SyncmoneyExpansion](https://github.com/Misty4119/Syncmoney/releases) + PlaceholderAPI.

| Placeholder | Description |
|-------------|-------------|
| `%syncmoney_balance%` | Player's current balance |
| `%syncmoney_balance_formatted%` | Balance with smart formatting |
| `%syncmoney_balance_abbreviated%` | Balance abbreviated (1.5K, 2.3億) |
| `%syncmoney_rank%` | Player's leaderboard rank |
| `%syncmoney_my_rank%` | Player's own rank |
| `%syncmoney_total_supply%` | Total money in circulation |
| `%syncmoney_total_players%` | Total players in leaderboard |
| `%syncmoney_online_players%` | Currently online players |
| `%syncmoney_version%` | Plugin version |
| `%syncmoney_top_<n>%` | Balance of player at rank n |
| `%syncmoney_balance_<player>%` | Specific player's balance by name |

---

## Migration from CMI

```bash
# Preview migration data
/syncmoney migrate cmi -preview

# Start migration
/syncmoney migrate cmi

# Force migration (skip validation)
/syncmoney migrate cmi -force

# Force migration without backup
/syncmoney migrate cmi -force -no-backup
```

**Features**:
- PostgreSQL, MySQL, and SQLite support
- Multi-server data merge (LATEST/SUM/MAX strategies)
- Automatic backup before migration
- Checkpoint resume for large datasets
- Auto-disable CMI economy after migration

---

## Performance

Syncmoney is optimized for high-throughput scenarios:

- **Redis connection pooling** (default: 30 connections)
- **Async write queues** — prevents main thread blocking (capacity: 50,000 events)
- **In-memory caching** — O(1) balance reads with ConcurrentHashMap
- **Batch database writes** — efficient audit log persistence (500 records/batch)
- **Lock-free event driven** — single-writer pattern avoids contention
- **Redis Lua atomics** — 7 scripts ensure consistency without locks
- **OverflowLog WAL** — prevents data loss when queues saturate

---

## API for Developers

Syncmoney provides multiple integration points:

### Vault API

```java
// Get economy via Vault
Economy economy = Bukkit.getServicesManager()
    .getRegistration(Economy.class).getProvider();

// Standard operations
economy.getBalance(player);
economy.depositPlayer(player, amount);
economy.withdrawPlayer(player, amount);
```

### Event System

```java
// Listen for completed transactions
@EventHandler
public void onTransaction(PostTransactionEvent event) {
    String player = event.getPlayerName();
    BigDecimal amount = event.getAmount();
    // type: DEPOSIT, WITHDRAW, TRANSFER, SET_BALANCE
}
```

### REST API

Full REST API available when Web Admin is enabled. See [API Reference](docs/API_REFERENCE.md).

```bash
# Health check (no auth)
curl http://localhost:8080/health

# Economy stats
curl -H "Authorization: Bearer <api-key>" http://localhost:8080/api/economy/stats
```

For complete documentation, see:
- [API Reference](docs/API_REFERENCE.md)
- [Developer Guide](docs/DEVELOPER_GUIDE.md)
- [Changelog](docs/CHANGELOG.md)

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "Server name not configured" | Set `server-name` in config.yml |
| Redis connection failed | Verify host/port/password in config |
| Balances not syncing | Ensure all servers use same Redis instance |
| Performance issues | Increase `pool-size` and `queue-capacity` |
| Circuit breaker locked | Use `/syncmoney breaker reset` to unlock |
| Player account locked | Use `/syncmoney breaker unlock <player>` |
| Web admin not loading | Check `web-admin.enabled: true` and port availability |

Enable `debug: true` in config.yml for detailed logs.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/Misty4119/Syncmoney.git
cd Syncmoney

# Build the plugin (Shadow JAR)
./gradlew shadowJar
# Output: build/libs/Syncmoney-1.1.2.jar

# Build PlaceholderAPI expansion
cd syncmoney-papi-expansion && ../gradlew jar
# Output: build/libs/SyncmoneyExpansion-1.1.2.jar

# Build web frontend
cd syncmoney-web && pnpm install && pnpm build
```

---

## Support

- **Discord**: [Join](https://official.noie.fun)
- **Issues**: [GitHub](https://github.com/Misty4119/Syncmoney/issues)

---

## License

[Apache License 2.0](LICENSE) — Free to use, modify, and distribute.

---

<p align="center">
  <sub>Built for high-scale Minecraft networks</sub>
</p>
